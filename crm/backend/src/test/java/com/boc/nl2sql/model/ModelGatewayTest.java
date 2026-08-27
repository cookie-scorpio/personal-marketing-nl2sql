package com.boc.nl2sql.model;

import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.authorization.domain.RoleCode;
import com.boc.nl2sql.nl2sql.application.RuleBasedSemanticParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ModelGatewayTest {
    @Test
    void ambiguousCustomerTimeIsClarifiedWithoutModelCall() {
        ModelAdapter adapter = mock(ModelAdapter.class);
        var gateway = new ModelGateway("deepseek", List.of(adapter), new RuleBasedSemanticParser());
        var user = new CurrentUser(3L, "director01", "负责人", RoleCode.ORG_MANAGER, "EAST", null, null);
        var result = gateway.interpret("分析近90天各年龄段客户数量和平均资产", user);
        assertThat(result.clarification().type()).isEqualTo("TIME_BASIS");
        verifyNoInteractions(adapter);
    }

    @Test
    void explicitOpeningDateOrClarificationGoesToModel() {
        ModelAdapter adapter = mock(ModelAdapter.class);
        when(adapter.provider()).thenReturn("deepseek"); when(adapter.available()).thenReturn(true);
        var gateway = new ModelGateway("deepseek", List.of(adapter), new RuleBasedSemanticParser());
        var user = new CurrentUser(3L, "director01", "负责人", RoleCode.ORG_MANAGER, "EAST", null, null);
        String text = "分析近90天各年龄段客户数量和平均资产，时间口径：按开户时间筛选，统计这些客户的当前资产";
        gateway.interpret(text, user);
        verify(adapter).interpret(text, user);
    }
    @Test
    void channelComparisonUsesModelInsteadOfFixedTemplate() {
        ModelAdapter adapter = mock(ModelAdapter.class);
        when(adapter.provider()).thenReturn("deepseek");
        when(adapter.available()).thenReturn(true);
        var gateway = new ModelGateway("deepseek", List.of(adapter), new RuleBasedSemanticParser());
        var user = new CurrentUser(3L, "director01", "演示负责人", RoleCode.ORG_MANAGER, "EAST", null, null);

        gateway.interpret("比较本季度不同渠道的营销转化率", user);

        verify(adapter, times(1)).interpret("比较本季度不同渠道的营销转化率", user);
    }

    @Test
    void knownHighFrequencyQuestionSkipsModelCall() {
        ModelAdapter adapter = mock(ModelAdapter.class);
        var gateway = new ModelGateway("deepseek", List.of(adapter), new RuleBasedSemanticParser());
        var user = new CurrentUser(1L, "manager01", "演示经理", RoleCode.CUSTOMER_MANAGER, "EAST", "B001", "M0001");

        var result = gateway.interpret("统计近30天各机构客户交易金额", user);

        assertThat(result.source()).isEqualTo("RULE");
        verify(adapter, never()).interpret(any(), any());
    }
}
