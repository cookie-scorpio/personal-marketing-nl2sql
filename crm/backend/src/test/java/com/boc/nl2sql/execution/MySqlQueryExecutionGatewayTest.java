package com.boc.nl2sql.execution;

import com.boc.nl2sql.execution.application.SqlSafetyValidator;
import com.boc.nl2sql.execution.domain.PlannedQuery;
import com.boc.nl2sql.execution.domain.QueryPage;
import com.boc.nl2sql.execution.infrastructure.MySqlQueryExecutionGateway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MySqlQueryExecutionGatewayTest {
    private final PlannedQuery plan = new PlannedQuery("SELECT customer_id FROM dim_customer LIMIT 100", Map.of(), "TABLE", "测试", false);
    private final QueryPage page = new QueryPage(1,100,0);

    @SuppressWarnings("unchecked")
    private NamedParameterJdbcTemplate jdbc(PreparedStatement statement) {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.execute(anyString(), anyMap(), any(PreparedStatementCallback.class)))
                .thenAnswer(call -> ((PreparedStatementCallback<?>) call.getArgument(2)).doInPreparedStatement(statement));
        return jdbc;
    }

    @Test
    void configuresPerStatementTimeoutAndMaxRowsAndReleasesCancelHandle() throws Exception {
        var statement = mock(PreparedStatement.class);
        var resultSet=mock(ResultSet.class);
        when(resultSet.next()).thenReturn(true,false);
        when(statement.executeQuery()).thenReturn(resultSet);
        var gateway = new MySqlQueryExecutionGateway(jdbc(statement), new SqlSafetyValidator(), 60, 100);
        try {
            assertThat(gateway.execute("task", plan, page, () -> true).rows()).isEmpty();
            verify(statement,times(2)).setQueryTimeout(60); verify(statement).setMaxRows(1); verify(statement).setMaxRows(100);
            gateway.cancel("task"); verify(statement, never()).cancel();
        } finally { gateway.close(); }
    }

    @Test
    void alreadyCancelledTaskNeverEntersJdbc() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var gateway = new MySqlQueryExecutionGateway(jdbc, new SqlSafetyValidator(), 60, 100);
        try {
            assertThatThrownBy(() -> gateway.execute("task", plan, page, () -> false)).isInstanceOf(QueryTerminatedException.class);
            verifyNoInteractions(jdbc);
        } finally { gateway.close(); }
    }

    @Test
    void interruptsActualRunningStatementWhenPersistentCancellationAppears() throws Exception {
        var statement = mock(PreparedStatement.class);
        var entered = new CountDownLatch(1); var cancelled = new CountDownLatch(1);
        var active = new AtomicBoolean(true);
        when(statement.executeQuery()).thenAnswer(call -> {
            entered.countDown();
            if (!cancelled.await(4, TimeUnit.SECONDS)) throw new AssertionError("statement was not cancelled");
            throw new SQLException("interrupted", "70100", 1317);
        });
        doAnswer(call -> { cancelled.countDown(); return null; }).when(statement).cancel();
        var gateway = new MySqlQueryExecutionGateway(jdbc(statement), new SqlSafetyValidator(), 60, 100);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var future = worker.submit(() -> gateway.execute("task", plan, page, active::get));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue(); active.set(false);
            assertThatThrownBy(() -> future.get(3, TimeUnit.SECONDS)).hasCauseInstanceOf(QueryTerminatedException.class);
            verify(statement, atLeastOnce()).cancel();
        } finally { worker.shutdownNow(); gateway.close(); }
    }

    @Test
    void jdbcTimeoutIsClassifiedSeparatelyFromSqlRepairErrors() throws Exception {
        var statement = mock(PreparedStatement.class);
        when(statement.executeQuery()).thenThrow(new SQLTimeoutException("expired"));
        var gateway = new MySqlQueryExecutionGateway(jdbc(statement), new SqlSafetyValidator(), 60, 100);
        try {
            assertThatThrownBy(() -> gateway.execute("task", plan, page, () -> true))
                    .isInstanceOfSatisfying(QueryTerminatedException.class, error -> assertThat(error.timedOut()).isTrue());
        } finally { gateway.close(); }
    }
}
