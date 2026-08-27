package com.boc.nl2sql.execution;

import com.boc.nl2sql.execution.application.SqlRiskEvaluator;
import com.boc.nl2sql.execution.domain.PlannedQuery;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqlRiskEvaluatorTest {
    private final SqlRiskEvaluator evaluator = new SqlRiskEvaluator();

    @Test
    void requiresConfirmationForUnboundedFactDetailQuery() {
        var query = new PlannedQuery("SELECT transaction_id FROM fct_transaction LIMIT 100",
                Map.of(), "TABLE", "交易明细", false);

        var risk = evaluator.assess(query);

        assertThat(risk.requiresConfirmation()).isTrue();
        assertThat(risk.reasons()).isNotEmpty();
    }
}
