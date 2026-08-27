package com.boc.nl2sql.execution;

import com.boc.nl2sql.execution.domain.PlannedQuery;

import java.util.List;
import java.util.Map;

/** MySQL查询执行边界，业务编排不直接依赖具体JDBC实现。 */
public interface QueryExecutionGateway {
    List<Map<String, Object>> execute(PlannedQuery query);
}
