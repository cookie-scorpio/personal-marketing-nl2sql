package com.boc.nl2sql.execution.domain;

import java.util.List;

/** SQL执行前的风险评估结果；需要确认时，原因会原样展示给用户。 */
public record QueryRisk(String level, boolean requiresConfirmation, List<String> reasons) {
    public static QueryRisk low() {
        return new QueryRisk("LOW", false, List.of());
    }

    public static QueryRisk high(String reason) {
        return new QueryRisk("HIGH", true, List.of(reason));
    }
}
