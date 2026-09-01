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
    void fallbackCoversConfirmedAgeAssetsButRejectsUnknownFiltersAndAmbiguousTime() {
        var scope = new DataScopePolicy();
        var fallback = new com.boc.nl2sql.execution.application.FallbackPlanner(new RuleBasedSemanticParser(), new SqlPlanner(scope, 100), scope, 100);
        var user = new CurrentUser(1L, "manager01", "经理", RoleCode.CUSTOMER_MANAGER, "EAST", "B001", "M0001");
        String text = "分析近90天各年龄段客户数量和平均资产";
        assertThat(fallback.plan(text, user)).isEmpty();
        var template = fallback.plan(text + "，时间口径：按开户时间筛选，统计这些客户的当前资产", user).orElseThrow();
        assertThat(template.query().sql()).contains("AVG(c.total_asset_amount)", "c.open_date >= :startDate", "c.manager_id = :scopeManagerId");
        assertThat(template.query().parameters()).containsEntry("scopeManagerId", "M0001");
        assertThat(fallback.plan("分析南京各年龄段客户数量和平均资产", user)).isEmpty();
        assertThat(fallback.plan("分析各年龄段客户数量和平均资产及同比", user)).isEmpty();
        assertThat(fallback.plan(text + "，时间口径：不限定时间，统计当前客户与当前资产", user).orElseThrow().query().sql()).doesNotContain("c.open_date");
    }

    @Test
    void forcesManagerScopeIntoPlannedSql() {
        var planner = new SqlPlanner(new DataScopePolicy(), 100);
        var query = new RuleBasedSemanticParser().parse("找出资产超过50万元的高净值客户名单");
        var user = new CurrentUser(1L, "manager01", "林书言", RoleCode.CUSTOMER_MANAGER,
                "EAST", "B001", "M0001");

        var planned = planner.plan(query, user);

        assertThat(planned.sql()).contains("c.manager_id = :scopeManagerId");
        assertThat(planned.parameters()).containsEntry("scopeManagerId", "M0001");
        assertThat(planned.sql()).doesNotContain("LIMIT").contains("ORDER BY c.total_asset_amount DESC, c.customer_id");
    }
}
