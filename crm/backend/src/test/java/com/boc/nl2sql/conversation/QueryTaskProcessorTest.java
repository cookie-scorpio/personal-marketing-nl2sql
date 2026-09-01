package com.boc.nl2sql.conversation;

import com.boc.nl2sql.audit.AuditService;
import com.boc.nl2sql.authorization.application.DataScopePolicy;
import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.authorization.domain.RoleCode;
import com.boc.nl2sql.conversation.application.*;
import com.boc.nl2sql.conversation.infrastructure.*;
import com.boc.nl2sql.execution.*;
import com.boc.nl2sql.execution.application.*;
import com.boc.nl2sql.execution.domain.*;
import com.boc.nl2sql.history.application.HistoryService;
import com.boc.nl2sql.model.*;
import com.boc.nl2sql.nl2sql.application.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QueryTaskProcessorTest {
    private final QueryTaskMapper mapper = mock(QueryTaskMapper.class);
    private final TaskStateStore states = mock(TaskStateStore.class);
    private final ModelGateway model = mock(ModelGateway.class);
    private final QueryExecutionGateway execution = mock(QueryExecutionGateway.class);
    private final HistoryService history = mock(HistoryService.class);
    private final AuditService audit = mock(AuditService.class);
    private final JsonMapper json = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    private final CurrentUser user = new CurrentUser(1L, "manager01", "经理", RoleCode.CUSTOMER_MANAGER, "EAST", "B001", "M0001");
    private final String sql = "SELECT c.age_band_code, COUNT(*) AS customer_count FROM dim_customer c WHERE c.manager_id = 'M0001' GROUP BY c.age_band_code LIMIT 100";
    private QueryTaskEntity task;
    private QueryTaskProcessor processor;

    @BeforeEach
    void setup() {
        var parser = new RuleBasedSemanticParser();
        var scope = new DataScopePolicy();
        var planner = new SqlPlanner(scope, 100);
        processor = new QueryTaskProcessor(mapper, states, model, new CompletenessValidator(), planner,
                new SqlRiskEvaluator(), new SqlSafetyValidator(), new GeneratedSqlScopeValidator(), execution,
                new ResultAssembler(null), new FallbackPlanner(parser, planner, scope, 100), history, audit, json,
                new com.boc.nl2sql.nl2sql.application.DisplayConflictGuard(), 2);
        task = new QueryTaskEntity(); task.setTaskId("task"); task.setUserId(1L); task.setSessionId("session");
        task.setQueryText("分析各年龄段客户数量和平均资产"); task.setMergedQueryText(task.getQueryText());
        task.setStatusCode("RECEIVED"); task.setStateVersion(0L); task.setRepairAttempts(0); task.setClarificationRound(0);
        when(mapper.selectById("task")).thenReturn(task);
        when(states.active("task")).thenReturn(true); when(states.trySave(task)).thenReturn(true);
        when(model.interpret(anyString(), eq(user), any())).thenReturn(plan(sql));
        when(model.repair(anyString(), eq(user), anyString(), anyString())).thenReturn(plan(sql));
        when(model.reviewResult(anyString(),eq(user),anyString(),anyMap(),anyBoolean()))
                .thenReturn(new SqlResultReview(true,"结构一致"));
    }
    private QueryInterpretation plan(String candidate) {
        return new QueryInterpretation(new RuleBasedSemanticParser().parse(task.getQueryText()),
                "DEEPSEEK", 0.95, candidate, "年龄段分析", "AUTO", null);
    }
    private BadSqlGrammarException sqlError() {
        return new BadSqlGrammarException("query", sql, new SQLException("not exposed", "42S22", 1054));
    }
    private PagedQueryRows page(List<Map<String,Object>> rows) {
        return new PagedQueryRows(rows,rows.size(),new QueryPage(1,100,0));
    }

    @Test
    void repairsExactlyTwiceThenExecutesTemplateAndStopsModelCalls() {
        when(execution.execute(eq("task"), any(), any(), any())).thenThrow(sqlError()).thenThrow(sqlError()).thenThrow(sqlError())
                .thenReturn(page(List.of(Map.of("age_band_code", "20-29", "customer_count", 3, "avg_asset_wan", 20))));
        processor.processAsync("task", user, "request");
        assertThat(task.getStatusCode()).isEqualTo("DEGRADED");
        assertThat(task.getRepairAttempts()).isEqualTo(2);
        var result = json.readValue(task.getResultJson(), QueryResult.class);
        assertThat(result.fallback().templateId()).isEqualTo("CUSTOMER_AGE_ASSETS");
        assertThat(result.fallback().dataAvailable()).isTrue();
        verify(model, times(2)).repair(anyString(), eq(user), anyString(), anyString());
        verify(execution, times(4)).execute(eq("task"), any(), any(), any());
    }

    @Test
    void succeedsOnFirstRepairWithoutFurtherCalls() {
        when(execution.execute(eq("task"), any(), any(), any())).thenThrow(sqlError()).thenReturn(page(List.of()));
        processor.processAsync("task", user, "request");
        assertThat(task.getStatusCode()).isEqualTo("SUCCESS");
        verify(model, times(1)).repair(anyString(), eq(user), anyString(), anyString());
    }

    @Test
    void timeoutAndCancellationNeverRepairOrFallback() {
        when(execution.execute(eq("task"), any(), any(), any())).thenThrow(new QueryTerminatedException(true));
        processor.processAsync("task", user, "request");
        assertThat(task.getStatusCode()).isEqualTo("TIMED_OUT");
        verify(model, never()).repair(anyString(), any(), anyString(), anyString());
        verify(execution, times(1)).execute(eq("task"), any(), any(), any());
        assertThat(task.getFallbackJson()).isNull();
    }

    @Test
    void noMatchingFallbackReturnsNoFabricatedRows() {
        task.setMergedQueryText("分析南京各年龄段客户数量和平均资产");
        when(execution.execute(eq("task"), any(), any(), any())).thenThrow(sqlError());
        processor.processAsync("task", user, "request");
        assertThat(task.getStatusCode()).isEqualTo("DEGRADED");
        var result = json.readValue(task.getResultJson(), QueryResult.class);
        assertThat(result.rows()).isEmpty(); assertThat(result.fallback().dataAvailable()).isFalse();
        verify(execution, times(3)).execute(eq("task"), any(), any(), any());
    }

    @Test
    void repairedSqlRequiresFreshConfirmationAndRetainsRepairBudget() {
        when(execution.execute(eq("task"), any(), any(), any())).thenThrow(sqlError());
        when(model.repair(anyString(), eq(user), anyString(), anyString())).thenReturn(plan(
                "SELECT c.customer_id FROM fct_transaction t JOIN dim_customer c ON c.customer_id=t.customer_id WHERE c.manager_id = 'M0001' LIMIT 100"));
        processor.processAsync("task", user, "request");
        assertThat(task.getStatusCode()).isEqualTo("CONFIRMING");
        assertThat(task.getConfirmed()).isFalse(); assertThat(task.getConfirmationToken()).isNotBlank();
        assertThat(task.getRepairAttempts()).isEqualTo(1);
        verify(execution, times(1)).execute(eq("task"), any(), any(), any());
        task.setStatusCode("RECEIVED"); task.setConfirmed(true);
        when(execution.execute(eq("task"), any(), any(), any())).thenReturn(page(List.of()));
        processor.processAsync("task", user, "request2");
        assertThat(task.getStatusCode()).isEqualTo("SUCCESS"); assertThat(task.getRepairAttempts()).isEqualTo(1);
        verify(model, times(1)).interpret(anyString(), eq(user), any());
        verify(model, times(1)).repair(anyString(), eq(user), anyString(), anyString());
    }

    @Test
    void blockedScopeInRepairIsNeverExecuted() {
        when(execution.execute(eq("task"), any(), any(), any())).thenThrow(sqlError()).thenReturn(page(List.of()));
        when(model.repair(anyString(), eq(user), anyString(), anyString())).thenReturn(plan(sql.replace("M0001", "M9999")));
        processor.processAsync("task", user, "request");
        assertThat(task.getStatusCode()).isEqualTo("DEGRADED");
        verify(model,times(2)).repair(anyString(),eq(user),anyString(),anyString());
        verify(execution, never()).execute(anyString(), argThat(query -> query.sql().contains("M9999")), any(), any());
    }

    @Test
    void validationFailureIsRepairedAndEveryCandidateIsRevalidated() {
        String invalid="SELECT c.unknown_column FROM dim_customer c WHERE c.manager_id='M0001' LIMIT 100";
        when(model.interpret(anyString(),eq(user),any())).thenReturn(plan(invalid));
        when(execution.execute(eq("task"),any(),any(),any())).thenReturn(page(List.of()));

        processor.processAsync("task",user,"request");

        assertThat(task.getStatusCode()).isEqualTo("SUCCESS");
        assertThat(task.getRepairAttempts()).isEqualTo(1);
        verify(model).repair(anyString(),eq(user),eq(invalid),contains("字段不存在"));
        verify(execution,times(1)).execute(eq("task"),argThat(query->query.sql().equals(sql)),any(),any());
    }

    @Test
    void obviousResultShapeMismatchUsesSameRepairBudgetWithoutSendingRowValues() {
        var firstRows=List.<Map<String,Object>>of(Map.of("customer_id","C00000001"));
        var repairedRows=List.<Map<String,Object>>of(Map.of("age_band_code","20-29","customer_count",3));
        when(execution.execute(eq("task"),any(),any(),any())).thenReturn(page(firstRows),page(repairedRows));
        when(model.reviewResult(anyString(),eq(user),anyString(),anyMap(),anyBoolean()))
                .thenReturn(new SqlResultReview(false,"用户要求分组统计，但返回了客户明细"),new SqlResultReview(true,"结构一致"));

        processor.processAsync("task",user,"request");

        assertThat(task.getStatusCode()).isEqualTo("SUCCESS");
        assertThat(task.getRepairAttempts()).isEqualTo(1);
        verify(model).repair(anyString(),eq(user),anyString(),contains("返回了客户明细"));
        verify(model,times(2)).reviewResult(anyString(),eq(user),anyString(),argThat(summary->
                !summary.toString().contains("C00000001")&&!summary.toString().contains("20-29")),anyBoolean());
        verify(execution,times(2)).execute(eq("task"),any(),any(),any());
    }

    @Test
    void cancelledDuringModelCallStopsBeforeSqlExecution() {
        doThrow(new TaskStateStore.TaskChangedException()).when(states).ensureActive("task");
        processor.processAsync("task", user, "request");
        verifyNoInteractions(execution);
        verify(history, never()).save(anyString(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void connectionFailureDoesNotConsumeRepairBudget() {
        when(execution.execute(eq("task"), any(), any(), any())).thenThrow(new org.springframework.jdbc.CannotGetJdbcConnectionException("internal"));
        processor.processAsync("task", user, "request");
        assertThat(task.getStatusCode()).isEqualTo("FAILED"); assertThat(task.getRepairAttempts()).isZero();
        verify(model, never()).repair(anyString(), any(), anyString(), anyString());
    }
}
