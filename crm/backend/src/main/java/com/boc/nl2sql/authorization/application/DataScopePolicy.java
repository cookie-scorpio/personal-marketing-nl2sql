package com.boc.nl2sql.authorization.application;

import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 将角色数据范围强制追加到客户别名上，前端参数无法覆盖此策略。 */
@Component
public class DataScopePolicy {

    public record Scope(String column, String value, String parameterName) {
    }

    public String condition(String customerAlias, CurrentUser user, Map<String, Object> parameters) {
        Scope scope = scopeOf(user);
        parameters.put(scope.parameterName(), scope.value());
        return customerAlias + "." + scope.column() + " = :" + scope.parameterName();
    }

    /** 模型提示与 AST 校验也必须读取同一角色映射，不能再维护各自的 switch。 */
    public static Scope scopeOf(CurrentUser user) {
        if (user == null || user.role() == null) {
            throw new BusinessException(401001, "请先登录后再访问");
        }
        if (!user.role().queryIdentity()) {
            throw new BusinessException(403106, "当前身份不具备业务数据查询权限");
        }
        /**
         * 质量审计员需要在审计身份下独立使用问数和保存会话，因此不能借用客户经理身份令牌。
         * 审计范围定义为全部在册客户，并以 status_code='ACTIVE' 这一条真实、可证明的谓词进入
         * 规则计划、模型提示和 SQL AST 校验链；这比跳过范围校验更安全，也会自动排除非在册记录。
         */
        if (user.role().normalized() == com.boc.nl2sql.authorization.domain.RoleCode.QUALITY_AUDITOR) {
            return new Scope("status_code", "ACTIVE", "scopeCustomerStatus");
        }
        com.boc.nl2sql.authorization.domain.BusinessDataScopeLevel level = user.businessScopeLevel();
        if (level == null) throw new BusinessException(403103, "客户经理身份缺少业务数据范围等级");
        Scope scope = switch (level) {
            case CUSTOMER_MANAGER -> new Scope("manager_id", user.managerId(), "scopeManagerId");
            case TEAM_LEAD -> new Scope("branch_id", user.branchId(), "scopeBranchId");
            case ORG_MANAGER -> new Scope("region_code", user.regionCode(), "scopeRegionCode");
        };
        if (scope.value() == null || !scope.value().matches("[A-Za-z0-9_-]+")) {
            throw new BusinessException(403103, "当前账号的数据范围配置无效");
        }
        return scope;
    }

    public static String literalCondition(String customerAlias, CurrentUser user) {
        Scope scope = scopeOf(user);
        return customerAlias + "." + scope.column() + " = '" + scope.value() + "'";
    }
}
