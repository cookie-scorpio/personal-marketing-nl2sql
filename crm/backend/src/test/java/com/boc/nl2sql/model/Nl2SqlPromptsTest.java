package com.boc.nl2sql.model;

import com.boc.nl2sql.domain.authorization.CurrentUser;
import com.boc.nl2sql.domain.authorization.RoleCode;
import com.boc.nl2sql.knowledge.BusinessTermCatalog;
import com.boc.nl2sql.knowledge.RetrievalAugmentor;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Nl2SqlPromptsTest {
    @Test
    void loadsUtf8ResourcesAndIncludesBusinessDefinitionsDateAndQuestion() {
        var terms = mock(BusinessTermCatalog.class);
        when(terms.promptContext()).thenReturn("测试口径：按触达客户去重");
        var prompts = new Nl2SqlPrompts(terms, null, null, 50);
        var user = new CurrentUser(3L, "director01", "演示负责人", RoleCode.ORG_MANAGER, "EAST", null, null);

        assertThat(prompts.systemPrompt()).contains("JSON", "needs_clarification", "GENERIC_ANALYSIS");
        assertThat(prompts.userPrompt("比较本季度不同渠道的营销转化率", user))
                .contains("fct_customer_marketing", "不提供历史资产序列", "测试口径：按触达客户去重",
                        LocalDate.now().toString(), "分页由服务端统一处理", "不得超过：50", "c.region_code = 'EAST'",
                        "用户问题：比较本季度不同渠道的营销转化率");
    }

    @Test
    void injectsManagerAndBranchScopesFromAuthenticatedUser() {
        var terms = mock(BusinessTermCatalog.class);
        when(terms.promptContext()).thenReturn("");
        var prompts = new Nl2SqlPrompts(terms, null, null, 100);
        var manager = new CurrentUser(1L, "manager01", "演示经理", RoleCode.CUSTOMER_MANAGER, "EAST", "B001", "M0001");
        var leader = new CurrentUser(2L, "leader01", "演示主管", RoleCode.TEAM_LEAD, "EAST", "B001", null);

        assertThat(prompts.userPrompt("查询", manager)).contains("必须使用的数据范围条件：c.manager_id = 'M0001'");
        assertThat(prompts.userPrompt("查询", leader)).contains("必须使用的数据范围条件：c.branch_id = 'B001'");
    }

    @Test
    void usesRetrievedTermsAndExamplesWhenAugmentorProvidesThem() {
        var terms = mock(BusinessTermCatalog.class);
        when(terms.promptContext()).thenReturn("全量兜底术语");
        var augmentor = mock(RetrievalAugmentor.class);
        when(augmentor.augment(eq("高净值客户有多少"), any())).thenReturn(new RetrievalAugmentor.Augmented(
                "高净值客户（同义表达：高净客群）：总资产不低于100万元",
                List.of(new RetrievalAugmentor.Example("高净值客户有多少人？平均资产多少",
                        "SELECT COUNT(*) AS hnw_customer_count FROM dim_customer c WHERE c.status_code = 'ACTIVE'"))));
        var prompts = new Nl2SqlPrompts(terms, null, augmentor, 100);
        var user = new CurrentUser(1L, "manager01", "演示经理", RoleCode.CUSTOMER_MANAGER, "EAST", "B001", "M0001");

        String prompt = prompts.userPrompt("高净值客户有多少", user);

        assertThat(prompt)
                .contains("高净值客户（同义表达：高净客群）")
                .doesNotContain("全量兜底术语")
                .contains("相似问题参考示例")
                .contains("示例1问题：高净值客户有多少人？平均资产多少")
                .contains("SQL：SELECT COUNT(*) AS hnw_customer_count")
                .contains("必须使用的数据范围条件：c.manager_id = 'M0001'")
                .contains("用户问题：高净值客户有多少");
        // 示例块在数据范围条件之前，保证模型最后看到的是实时范围条件
        assertThat(prompt.indexOf("相似问题参考示例")).isLessThan(prompt.indexOf("必须使用的数据范围条件"));
    }

    @Test
    void fallsBackToFullTermCatalogWhenAugmentationUnavailable() {
        var terms = mock(BusinessTermCatalog.class);
        when(terms.promptContext()).thenReturn("全量兜底术语");
        var augmentor = mock(RetrievalAugmentor.class);
        when(augmentor.augment(any(), any())).thenReturn(null);
        var prompts = new Nl2SqlPrompts(terms, null, augmentor, 100);
        var user = new CurrentUser(1L, "manager01", "演示经理", RoleCode.CUSTOMER_MANAGER, "EAST", "B001", "M0001");

        assertThat(prompts.userPrompt("随便问点什么", user))
                .contains("全量兜底术语")
                .doesNotContain("相似问题参考示例");
    }
}
