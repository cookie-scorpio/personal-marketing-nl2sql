package com.boc.nl2sql.conversation;

import com.boc.nl2sql.conversation.application.CustomerResolver;
import com.boc.nl2sql.conversation.application.TaskSnapshots;
import com.boc.nl2sql.conversation.infrastructure.QueryTaskEntity;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskSnapshotsTest {
    @Test
    void resolvedSingleCustomerIsExposedAsAReadOnlyMaskedCard(){
        var customers=mock(CustomerResolver.class);
        when(customers.card("C00000697")).thenReturn(Optional.of(
                new CustomerResolver.Candidate("C00000697","王*明","B001","900****0697")));
        var task=new QueryTaskEntity();
        task.setTaskId("task");task.setSessionId("session");task.setStatusCode("SQL_GENERATING");task.setProgress(45);
        task.setStageMessage("客户已确认，正在生成查询计划");task.setStateVersion(2L);task.setClarificationRound(0);
        task.setRepairAttempts(0);task.setThinkingEnabled(true);task.setResolvedCustomerId("C00000697");

        var snapshot=new TaskSnapshots(new JsonMapper(),60,customers).of(task);

        assertThat(snapshot.resolvedCustomer()).isEqualTo(
                new com.boc.nl2sql.conversation.api.TaskStatusResponse.CustomerCard(
                        "C00000697","王*明","B001","900****0697"));
    }
}
