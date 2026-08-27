package com.boc.nl2sql.execution.infrastructure;

import com.boc.nl2sql.execution.QueryExecutionGateway;
import com.boc.nl2sql.execution.QueryTerminatedException;
import com.boc.nl2sql.execution.application.SqlSafetyValidator;
import com.boc.nl2sql.execution.domain.PlannedQuery;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

@Repository
public class MySqlQueryExecutionGateway implements QueryExecutionGateway {
    private final NamedParameterJdbcTemplate jdbc;
    private final SqlSafetyValidator safety;
    private final int timeoutSeconds;
    private final int maxRows;
    private final Map<String, RunningQuery> running = new ConcurrentHashMap<>();
    private final ScheduledExecutorService watcher = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "nl2sql-cancel-watch");
        thread.setDaemon(true);
        return thread;
    });

    public MySqlQueryExecutionGateway(NamedParameterJdbcTemplate jdbc, SqlSafetyValidator safety,
            @Value("${app.query.execution-timeout-seconds:60}") int timeoutSeconds,
            @Value("${app.query.max-result-rows:100}") int maxRows) {
        if (timeoutSeconds < 1 || maxRows < 1) throw new IllegalArgumentException("SQL超时和行数上限必须为正数");
        this.jdbc = jdbc;
        this.safety = safety;
        this.timeoutSeconds = timeoutSeconds;
        this.maxRows = maxRows;
    }

    @Override
    public List<Map<String, Object>> execute(String taskId, PlannedQuery query, BooleanSupplier active) {
        safety.validate(query.sql());
        if (!active.getAsBoolean()) throw new QueryTerminatedException(false);
        return jdbc.execute(query.sql(), query.parameters(), statement -> {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(maxRows);
            RunningQuery handle = new RunningQuery(statement, active, timeoutSeconds);
            if (running.putIfAbsent(taskId, handle) != null) throw new IllegalStateException("同一任务不能并行执行SQL");
            var watch = watcher.scheduleWithFixedDelay(handle::check, 0, 200, TimeUnit.MILLISECONDS);
            try {
                handle.check();
                handle.throwIfStopped();
                try (var resultSet = statement.executeQuery()) {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    var rowMapper = new ColumnMapRowMapper();
                    while (resultSet.next() && rows.size() < maxRows) {
                        handle.throwIfStopped();
                        rows.add(rowMapper.mapRow(resultSet, rows.size()));
                    }
                    handle.check();
                    handle.throwIfStopped();
                    return rows;
                }
            } catch (SQLException exception) {
                handle.throwIfStopped();
                if (exception instanceof SQLTimeoutException || exception.getErrorCode() == 3024) {
                    throw new QueryTerminatedException(true);
                }
                throw exception;
            } finally {
                // 先停止取消回调，再交回连接池，防止晚到的cancel影响下一条SQL。
                handle.close();
                watch.cancel(false);
                running.remove(taskId, handle);
            }
        });
    }

    @Override
    public void cancel(String taskId) {
        RunningQuery handle = running.get(taskId);
        if (handle != null) watcher.execute(() -> handle.stop(false));
    }

    @PreDestroy
    public void close() { watcher.shutdownNow(); }

    private static final class RunningQuery {
        private final PreparedStatement statement;
        private final BooleanSupplier active;
        private final long deadline;
        private boolean closed;
        private volatile boolean stopped;
        private volatile boolean timedOut;

        RunningQuery(PreparedStatement statement, BooleanSupplier active, int seconds) {
            this.statement = statement;
            this.active = active;
            this.deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        }

        synchronized void check() {
            if (closed) return;
            try {
                if (!active.getAsBoolean()) stop(false);
                else if (System.nanoTime() >= deadline) stop(true);
                else if (stopped) stop(timedOut);
            } catch (RuntimeException unavailable) {
                // 无法核实任务状态时停止取数。
                stop(false);
            }
        }

        synchronized void stop(boolean timeout) {
            if (closed) return;
            if (!stopped) timedOut = timeout;
            stopped = true;
            try { statement.cancel(); } catch (SQLException ignored) { /* JDBC超时仍是第二道保护。 */ }
        }

        void throwIfStopped() {
            if (stopped) throw new QueryTerminatedException(timedOut);
        }
        synchronized void close() { closed = true; }
    }
}
