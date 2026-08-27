package com.boc.nl2sql.model;

import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.authorization.domain.RoleCode;
import com.boc.nl2sql.knowledge.BusinessTermCatalog;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Nl2SqlPromptsTest {
    @Test
    void loadsUtf8ResourcesAndIncludesBusinessDefinitionsDateAndQuestion() {
        var terms = mock(BusinessTermCatalog.class);
        when(terms.promptContext()).thenReturn("测试口径：按触达客户去重");
        var prompts = new Nl2SqlPrompts(terms, 50);
        var user = new CurrentUser(3L, "director01", "演示负责人", RoleCode.ORG_MANAGER, "EAST", null, null);

        assertThat(prompts.systemPrompt()).contains("JSON", "needs_clarification", "GENERIC_ANALYSIS");
        assertThat(prompts.userPrompt("比较本季度不同渠道的营销转化率", user))
                .contains("fct_customer_marketing", "不提供历史资产序列", "测试口径：按触达客户去重",
                        LocalDate.now().toString(), "最大返回行数：50", "c.region_code = 'EAST'",
                        "用户问题：比较本季度不同渠道的营销转化率");
    }

    @Test
    void injectsManagerAndBranchScopesFromAuthenticatedUser() {
        var terms = mock(BusinessTermCatalog.class);
        when(terms.promptContext()).thenReturn("");
        var prompts = new Nl2SqlPrompts(terms, 100);
        var manager = new CurrentUser(1L, "manager01", "演示经理", RoleCode.CUSTOMER_MANAGER, "EAST", "B001", "M0001");
        var leader = new CurrentUser(2L, "leader01", "演示主管", RoleCode.TEAM_LEAD, "EAST", "B001", null);

        assertThat(prompts.userPrompt("查询", manager)).contains("必须使用的数据范围条件：c.manager_id = 'M0001'");
        assertThat(prompts.userPrompt("查询", leader)).contains("必须使用的数据范围条件：c.branch_id = 'B001'");
    }
}
