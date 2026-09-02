package com.boc.nl2sql.dao.execution;

import com.boc.nl2sql.dao.execution.QueryExecutionGateway;
import com.boc.nl2sql.dao.execution.QueryTerminatedException;
import com.boc.nl2sql.service.execution.SqlSafetyValidator;
import com.boc.nl2sql.domain.execution.PlannedQuery;
import com.boc.nl2sql.domain.execution.PagedQueryRows;
import com.boc.nl2sql.domain.execution.QueryPage;
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

/**
 * 在只读 MySQL 连接上执行经过校验的分页查询。
 * 计数和数据页共享同一截止时间；运行句柄允许取消线程中断 JDBC 语句，但必须在连接归还池前关闭。
 */
@Repository
public class MySqlQueryExecutionGateway implements QueryExecutionGateway {
    private final NamedParameterJdbcTemplate jdbc;
    private final SqlSafetyValidator safety;
    private final int timeoutSeconds;
    private final int maxPageSize;
    private final Map<String, RunningQuery> running = new ConcurrentHashMap<>();
    private final ScheduledExecutorService watcher = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "nl2sql-cancel-watch");
        thread.setDaemon(true);
        return thread;
    });

    public MySqlQueryExecutionGateway(NamedParameterJdbcTemplate jdbc, SqlSafetyValidator safety,
            @Value("${app.query.execution-timeout-seconds:60}") int timeoutSeconds,
            @Value("${app.query.max-page-size:500}") int maxPageSize) {
        if (timeoutSeconds < 1 || maxPageSize < 1) throw new IllegalArgumentException("SQL超时和分页上限必须为正数");
        this.jdbc = jdbc;
        this.safety = safety;
        this.timeoutSeconds = timeoutSeconds;
        this.maxPageSize = maxPageSize;
    }

    @Override
    /** 校验 SQL 后依次执行计数和数据页，并在两步之间持续检查任务是否仍有效。 */
    public PagedQueryRows execute(String taskId, PlannedQuery query, QueryPage page, BooleanSupplier active) {
        safety.validate(query.sql());
        if (page.pageSize() > maxPageSize) throw new IllegalArgumentException("单页条数超过执行层上限");
        if (!active.getAsBoolean()) throw new QueryTerminatedException(false);
        QueryPaginationSql.Statements statements = QueryPaginationSql.build(query.sql(), page);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeoutSeconds);
        long total = run(taskId, statements.countSql(), query.parameters(), active, deadline, 1, statement -> {
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        });
        if (!active.getAsBoolean()) throw new QueryTerminatedException(false);
        List<Map<String, Object>> rows = run(taskId, statements.pageSql(), query.parameters(), active, deadline,
                page.pageSize(), statement -> {
            try (var resultSet = statement.executeQuery()) {
                List<Map<String, Object>> result = new ArrayList<>();
                var rowMapper = new ColumnMapRowMapper();
                while (resultSet.next()) {
                    RunningQuery handle = running.get(taskId);
                    if (handle != null) handle.throwIfStopped();
                    result.add(rowMapper.mapRow(resultSet, result.size()));
                }
                return result;
            }
        });
        return new PagedQueryRows(rows, total, page, statements.pageSql());
    }

    private <T> T run(String taskId, String sql, Map<String, ?> parameters, BooleanSupplier active,
                      long deadline, int maxRows, SqlWork<T> work) {
        return jdbc.execute(sql, parameters, statement -> {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(maxRows);
            RunningQuery handle = new RunningQuery(statement, active, deadline);
            if (running.putIfAbsent(taskId, handle) != null) throw new IllegalStateException("同一任务不能并行执行SQL");
            var watch = watcher.scheduleWithFixedDelay(handle::check, 0, 200, TimeUnit.MILLISECONDS);
            try {
                handle.check();
                handle.throwIfStopped();
                T result = work.execute(statement);
                handle.check();
                handle.throwIfStopped();
                return result;
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

    @FunctionalInterface
    private interface SqlWork<T> { T execute(PreparedStatement statement) throws SQLException; }

    @Override
    /** 异步停止指定任务当前正在执行的 JDBC 语句；任务未运行时保持幂等。 */
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

        RunningQuery(PreparedStatement statement, BooleanSupplier active, long deadline) {
            this.statement = statement;
            this.active = active;
            this.deadline = deadline;
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
