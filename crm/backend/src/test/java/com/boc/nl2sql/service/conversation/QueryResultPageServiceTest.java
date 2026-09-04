package com.boc.nl2sql.service.conversation;

import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.dao.conversation.QueryTaskMapper;
import com.boc.nl2sql.dao.execution.QueryExecutionGateway;
import com.boc.nl2sql.domain.authorization.CurrentUser;
import com.boc.nl2sql.domain.authorization.RoleCode;
import com.boc.nl2sql.domain.conversation.QueryTaskEntity;
import com.boc.nl2sql.domain.execution.AnalysisSummary;
import com.boc.nl2sql.domain.execution.PagedQueryRows;
import com.boc.nl2sql.domain.execution.QueryPage;
import com.boc.nl2sql.domain.execution.QueryResult;
import com.boc.nl2sql.service.authorization.AuthorizationCenter;
import com.boc.nl2sql.service.execution.GeneratedSqlScopeValidator;
import com.boc.nl2sql.service.execution.ResultAssembler;
import com.boc.nl2sql.service.execution.SqlSafetyValidator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QueryResultPageService 的单元测试不连接数据库，重点验证分页换算和安全边界。 */
class QueryResultPageServiceTest {
    private final QueryTaskMapper tasks = mock(QueryTaskMapper.class);
    private final AuthorizationCenter authorization = mock(AuthorizationCenter.class);
    private final ConversationStore conversations = mock(ConversationStore.class);
    private final SqlSafetyValidator safety = mock(SqlSafetyValidator.class);
    private final GeneratedSqlScopeValidator scope = mock(GeneratedSqlScopeValidator.class);
    private final QueryExecutionGateway execution = mock(QueryExecutionGateway.class);
    private final ResultAssembler assembler = mock(ResultAssembler.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final CurrentUser user = new CurrentUser(
            3L, "director01", "负责人", RoleCode.ORG_MANAGER, "EAST", null, null);
    private final QueryResultPageService service = new QueryResultPageService(
            tasks, authorization, conversations, safety, scope, execution, assembler, json, 500, 50, 100_000);

    @Test
    void readsRequestedPageWithoutCreatingAnotherTask() {
        QueryTaskEntity task = completedTask("RULE");
        QueryResult assembled = result(3, 20, 40, 205L);
        when(tasks.selectOne(any())).thenReturn(task);
        when(execution.execute(anyString(), any(), any(), any()))
                .thenAnswer(invocation -> new PagedQueryRows(
                        List.of(Map.of("customer_id", "C00000041")),
                        205,
                        invocation.getArgument(2, QueryPage.class)));
        when(assembler.assemble(any(), any(PagedQueryRows.class), anyString(), any(Double.class)))
                .thenReturn(assembled);

        QueryResult result = service.page("task-1", 3, 20, user);

        assertThat(result).isSameAs(assembled);
        ArgumentCaptor<QueryPage> page = ArgumentCaptor.forClass(QueryPage.class);
        verify(execution).execute(anyString(), any(), page.capture(), any());
        assertThat(page.getValue()).isEqualTo(new QueryPage(3, 20, 40));
        verify(authorization).requireBusinessDataAccess(user);
        verify(authorization).requireOwner(user, 3L, "查询任务不存在");
        verify(conversations).own("session-1", user);
        verify(safety).validate("SELECT customer_id FROM dim_customer ORDER BY customer_id");
        // 固定规则模板使用原有受控参数，不执行自由 SQL 的账号范围证明。
        verify(scope, never()).validate(anyString(), any());
    }

    @Test
    void revalidatesModelGeneratedSqlAgainstCurrentIdentity() {
        QueryTaskEntity task = completedTask("QWEN");
        when(tasks.selectOne(any())).thenReturn(task);
        when(execution.execute(anyString(), any(), any(), any()))
                .thenReturn(new PagedQueryRows(List.of(), 0, new QueryPage(1, 20, 0)));
        when(assembler.assemble(any(), any(PagedQueryRows.class), anyString(), any(Double.class)))
                .thenReturn(result(1, 20, 0, 0L));

        service.page("task-1", 1, 20, user);

        verify(scope).validate("SELECT customer_id FROM dim_customer ORDER BY customer_id", user);
    }

    @Test
    void rejectsInvalidOrUnavailablePagesBeforeExecutingSql() {
        assertThatThrownBy(() -> service.page("task-1", 0, 20, user))
                .isInstanceOf(BusinessException.class)
                .hasMessage("page_no必须大于等于1");
        assertThatThrownBy(() -> service.page("task-1", 51, 20, user))
                .isInstanceOf(BusinessException.class)
                .hasMessage("查询结果最多只能查看前50页");

        QueryTaskEntity unfinished = completedTask("RULE");
        unfinished.setStatusCode("EXECUTING");
        when(tasks.selectOne(any())).thenReturn(unfinished);
        assertThatThrownBy(() -> service.page("task-1", 1, 20, user))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("尚无可分页的完整结果");
        verify(execution, never()).execute(anyString(), any(), any(), any());
    }

    private QueryTaskEntity completedTask(String source) {
        QueryTaskEntity task = new QueryTaskEntity();
        task.setTaskId("task-1");
        task.setSessionId("session-1");
        task.setUserId(3L);
        task.setStatusCode("SUCCESS");
        task.setInterpretationSource(source);
        task.setInterpretationConfidence(0.9);
        task.setPreferredDisplay("TABLE");
        task.setSqlText("SELECT customer_id FROM dim_customer ORDER BY customer_id");
        task.setSqlParametersJson("{}");
        task.setResultJson(json.writeValueAsString(result(1, 20, 0, 205L)));
        return task;
    }

    private QueryResult result(int pageNo, int pageSize, long offset, long total) {
        return new QueryResult("TABLE", "客户列表", "查询完成", List.of(), List.of(), List.of(), List.of(),
                new AnalysisSummary("", List.of(), List.of()), "", LocalDate.of(2026, 9, 4),
                "RULE", 0.9, total, pageNo, pageSize, offset, offset + pageSize < total, null);
    }
}
