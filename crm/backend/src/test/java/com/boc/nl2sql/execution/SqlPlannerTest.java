package com.boc.nl2sql.execution;

import com.boc.nl2sql.authorization.application.DataScopePolicy;
import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.authorization.domain.RoleCode;
import com.boc.nl2sql.execution.application.SqlPlanner;
import com.boc.nl2sql.nl2sql.application.RuleBasedSemanticParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlPlannerTest {

    @Test
    void forcesManagerScopeIntoPlannedSql() {
        var planner = new SqlPlanner(new DataScopePolicy(), 100);
        var query = new RuleBasedSemanticParser().parse("找出资产超过50万元的高净值客户名单");
        var user = new CurrentUser(1L, "manager01", "林书言", RoleCode.CUSTOMER_MANAGER,
                "EAST", "B001", "M0001");

        var planned = planner.plan(query, user);

        assertThat(planned.sql()).contains("c.manager_id = :scopeManagerId");
        assertThat(planned.parameters()).containsEntry("scopeManagerId", "M0001");
        assertThat(planned.sql()).contains("LIMIT 100");
    }
}
