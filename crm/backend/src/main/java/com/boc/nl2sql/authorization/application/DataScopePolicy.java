package com.boc.nl2sql.authorization.application;

import com.boc.nl2sql.authorization.domain.CurrentUser;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 将角色数据范围强制追加到客户别名上，前端参数无法覆盖此策略。 */
@Component
public class DataScopePolicy {

    public String condition(String customerAlias, CurrentUser user, Map<String, Object> parameters) {
        return switch (user.role()) {
            case CUSTOMER_MANAGER -> {
                parameters.put("scopeManagerId", user.managerId());
                yield customerAlias + ".manager_id = :scopeManagerId";
            }
            case TEAM_LEAD -> {
                parameters.put("scopeBranchId", user.branchId());
                yield customerAlias + ".branch_id = :scopeBranchId";
            }
            case ORG_MANAGER -> {
                parameters.put("scopeRegionCode", user.regionCode());
                yield customerAlias + ".region_code = :scopeRegionCode";
            }
        };
    }
}
