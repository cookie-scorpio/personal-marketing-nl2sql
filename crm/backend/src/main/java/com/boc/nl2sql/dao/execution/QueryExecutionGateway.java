package com.boc.nl2sql.dao.execution;

import com.boc.nl2sql.domain.execution.PlannedQuery;
import com.boc.nl2sql.domain.execution.PagedQueryRows;
import com.boc.nl2sql.domain.execution.QueryPage;

/** MySQL查询执行边界，业务编排不直接依赖具体JDBC实现。 */
public interface QueryExecutionGateway {
    PagedQueryRows execute(String taskId, PlannedQuery query, QueryPage page,
                           java.util.function.BooleanSupplier active);

    void cancel(String taskId);
}
