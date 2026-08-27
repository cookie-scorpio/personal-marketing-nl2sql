package com.boc.nl2sql.execution;

import com.boc.nl2sql.execution.domain.PlannedQuery;

import java.util.List;
import java.util.Map;

/** 当前由 MySQL 实现；未来 Spark/Hive 实现沿用相同输入输出契约。 */
public interface QueryExecutionGateway {
    List<Map<String, Object>> execute(PlannedQuery query);
}
