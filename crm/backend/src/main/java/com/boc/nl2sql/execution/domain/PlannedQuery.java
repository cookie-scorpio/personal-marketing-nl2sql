package com.boc.nl2sql.execution.domain;

import java.util.Map;

public record PlannedQuery(
        String sql,
        Map<String, Object> parameters,
        String resultType,
        String title,
        QueryRisk risk
) {
    /** 兼容固定模板构造方式，后续还会由风险评估器补充SQL复杂度风险。 */
    public PlannedQuery(String sql, Map<String, Object> parameters, String resultType, String title,
                        boolean highRisk) {
        this(sql, parameters, resultType, title,
                highRisk ? QueryRisk.high("查询范围包含全部或不限范围数据") : QueryRisk.low());
    }

    public boolean highRisk() {
        return risk != null && risk.requiresConfirmation();
    }

    public PlannedQuery withRisk(QueryRisk assessedRisk) {
        return new PlannedQuery(sql, parameters, resultType, title, assessedRisk);
    }
}
