package com.boc.nl2sql.execution;

import com.boc.nl2sql.execution.application.ResultAssembler;
import com.boc.nl2sql.execution.domain.PlannedQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResultAssemblerTest {
    @Test
    void createsChartAndDeterministicAnalysisForGroupedRows() {
        var planned = new PlannedQuery("SELECT branch_id, COUNT(*) AS customer_count FROM dim_customer LIMIT 100",
                Map.of(), "AUTO", "机构客户分布", false);
        var rows = List.<Map<String, Object>>of(
                Map.of("branch_id", "B001", "customer_count", 18L),
                Map.of("branch_id", "B002", "customer_count", 12L));

        var result = new ResultAssembler().assemble(planned, rows, "RULE", 1.0);

        assertThat(result.charts()).hasSize(1);
        assertThat(result.charts().get(0).dimensionKey()).isEqualTo("branch_id");
        assertThat(result.analysis().insights()).anyMatch(text -> text.contains("B001"));
        assertThat(result.interpretationSource()).isEqualTo("RULE");
    }
}
