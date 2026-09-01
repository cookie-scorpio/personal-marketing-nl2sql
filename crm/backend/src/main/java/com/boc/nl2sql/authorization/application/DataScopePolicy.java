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
        Scope scope = switch (user.role()) {
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
