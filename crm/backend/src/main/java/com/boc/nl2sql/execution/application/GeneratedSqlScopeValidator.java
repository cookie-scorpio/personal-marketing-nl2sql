package com.boc.nl2sql.execution.application;

import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/** 确认模型生成的SQL包含服务端指定的数据范围，防止提示词约束被模型遗漏。 */
@Component
public class GeneratedSqlScopeValidator {
    public void validate(String sql, CurrentUser user) {
        String normalized = sql.toLowerCase(Locale.ROOT);
        Scope scope = switch (user.role()) {
            case CUSTOMER_MANAGER -> new Scope("manager_id", user.managerId());
            case TEAM_LEAD -> new Scope("branch_id", user.branchId());
            case ORG_MANAGER -> new Scope("region_code", user.regionCode());
        };
        if (scope.value() == null) throw new BusinessException(403103, "当前账号的数据范围配置无效");
        String expression = "\\b" + Pattern.quote(scope.column()) + "\\s*=\\s*['\"]"
                + Pattern.quote(scope.value().toLowerCase(Locale.ROOT)) + "['\"]";
        if (!Pattern.compile(expression).matcher(normalized).find()) {
            throw new BusinessException(403104, "模型生成的SQL缺少当前账号数据范围，已阻止执行");
        }
    }

    private record Scope(String column, String value) {
    }
}
