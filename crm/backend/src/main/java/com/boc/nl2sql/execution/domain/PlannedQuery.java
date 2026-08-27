package com.boc.nl2sql.execution.domain;

import java.util.Map;

public record PlannedQuery(
        String sql,
        Map<String, Object> parameters,
        String resultType,
        String title,
        boolean highRisk
) {
}
