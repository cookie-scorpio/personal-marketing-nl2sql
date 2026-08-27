package com.boc.nl2sql.execution.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record QueryResult(
        String resultType,
        String title,
        String summary,
        List<ColumnMeta> columns,
        List<Map<String, Object>> rows,
        List<Map<String, Object>> metrics,
        String sqlPreview,
        LocalDate dataAsOf
) {
}
