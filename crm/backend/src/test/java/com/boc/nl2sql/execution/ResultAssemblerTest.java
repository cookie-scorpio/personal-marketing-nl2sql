package com.boc.nl2sql.execution;

import com.boc.nl2sql.execution.application.ResultAssembler;
import com.boc.nl2sql.execution.domain.PlannedQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResultAssemblerTest {
    @Test void exposesTotalAndPaginationInsteadOfSilentTruncation(){
        var rows=java.util.stream.IntStream.range(0,100).mapToObj(i->Map.<String,Object>of("customer_id",String.format("C%08d",i))).toList();
        var page=new com.boc.nl2sql.execution.domain.PagedQueryRows(rows,205,
                new com.boc.nl2sql.execution.domain.QueryPage(1,100,0));
        var result=new ResultAssembler(null).assemble(new PlannedQuery("SELECT customer_id FROM dim_customer",Map.of(),"TABLE","分页",false),page,"RULE",1.0);
        assertThat(result.total()).isEqualTo(205);assertThat(result.pageNo()).isEqualTo(1);
        assertThat(result.pageSize()).isEqualTo(100);assertThat(result.hasMore()).isTrue();
        assertThat(result.summary()).contains("共 205 条","当前第 1 页返回 100 条");
    }
    @Test void masksCustomerFieldsBeforeRowsAndAnalysisAreBuilt(){
        var result=assemble("TABLE",List.of(Map.of("CUSTOMER_NAME","李验甲","mobile_masked","90000008877","asset_wan",10)));
        assertThat(result.rows().get(0)).containsEntry("customer_name","李*甲").containsEntry("mobile_masked","900****8877");
        assertThat(result.analysis().insights().toString()).doesNotContain("李验甲");
    }
    @Test void comparesGroupsOverTimeWithoutAggregatingMissingPoints(){
        var result=assemble("AUTO",List.of(Map.of("month","2026-01","channel_code","APP","transaction_count",2),Map.of("month","2026-02","channel_code","BRANCH","transaction_count",4)));
        assertThat(result.charts().get(0).type()).isEqualTo("LINE");
        assertThat(result.charts().get(0).secondaryDimensionKey()).isEqualTo("channel_code");
        assertThat(result.rows()).hasSize(2);
    }
    private com.boc.nl2sql.execution.domain.QueryResult assemble(String type, List<Map<String, Object>> rows) {
        return new ResultAssembler(null).assemble(new PlannedQuery("SELECT age_band_code FROM dim_customer LIMIT 100",
                Map.of(), type, "测试分析", false), rows, "DEEPSEEK", 0.95);
    }

    @Test
    void coversCountAndAverageAssetsWithIndependentUnitsAndWeightedAverage() {
        var result = assemble("BAR", List.of(
                Map.of("age_band_code", "20-29", "customer_count", 10, "avg_asset_amount", 100),
                Map.of("age_band_code", "30-39", "customer_count", 30, "avg_asset_amount", 300)));
        assertThat(result.charts()).hasSize(2);
        assertThat(result.charts().stream().flatMap(chart -> chart.series().stream()).map(series -> series.key()))
                .containsExactlyInAnyOrder("customer_count", "avg_asset_amount");
        assertThat(result.metrics()).anySatisfy(metric -> {
            assertThat(metric.get("key")).isEqualTo("avg_asset_amount");
            assertThat(metric.get("value")).isEqualTo(250.0);
            assertThat(metric.get("unit")).isEqualTo("元");
        });
        assertThat(result.analysis().insights()).anyMatch(text -> text.contains("平均资产"));
    }

    @Test
    void infersNumericColumnBeyondNullFirstRowAndDoesNotTurnNullIntoZero() {
        var first = new java.util.LinkedHashMap<String, Object>();
        first.put("age_band_code", "20-29"); first.put("avg_asset_amount", null);
        var result = assemble("AUTO", List.of(first, Map.of("age_band_code", "30-39", "avg_asset_amount", 300)));
        assertThat(result.charts()).hasSize(1);
        assertThat(result.rows().get(0).get("avg_asset_amount")).isNull();
        assertThat(result.metrics().get(0).get("value")).isEqualTo(300.0);
    }

    @Test
    void preservesNumericCodesAsDimensionsAndUsesTimeCharts() {
        var grouped = assemble("AUTO", List.of(Map.of("age_band_code", 1, "customer_count", 10),
                Map.of("age_band_code", 2, "customer_count", 20)));
        assertThat(grouped.metrics()).hasSize(1);
        assertThat(grouped.charts().get(0).dimensionKey()).isEqualTo("age_band_code");
        var time = assemble("AREA", List.of(Map.of("month", "2026-01", "transaction_count", 3),
                Map.of("month", "2026-02", "transaction_count", 8)));
        assertThat(time.charts().get(0).type()).isEqualTo("AREA");
    }

    @Test
    void providesCompositionAndComparisonButNeverPieForNegativeOrAverageValues() {
        var result = assemble("AUTO", List.of(Map.of("customer_level", "GOLD", "customer_count", 2),
                Map.of("customer_level", "NORMAL", "customer_count", 8)));
        assertThat(result.charts()).extracting(chart -> chart.type()).containsExactly("BAR", "PIE");
        assertThat(assemble("PIE", List.of(Map.of("group", "A", "profit_wan", -3),
                Map.of("group", "B", "profit_wan", 8))).charts()).extracting(chart -> chart.type()).containsExactly("BAR");
        assertThat(assemble("PIE", List.of(Map.of("group", "A", "avg_asset_amount", 3),
                Map.of("group", "B", "avg_asset_amount", 8))).charts()).extracting(chart -> chart.type()).containsExactly("BAR");
    }

    @Test
    void usesHeatmapForTwoDimensionsAndScatterForTwoMeasures() {
        var heatmap = assemble("AUTO", List.of(Map.of("region_code", "EAST", "gender_code", "F", "customer_count", 2),
                Map.of("region_code", "WEST", "gender_code", "F", "customer_count", 8)));
        assertThat(heatmap.charts().get(0).type()).isEqualTo("HEATMAP");
        var scatter = assemble("AUTO", List.of(Map.of("amount", 10, "profit", 2), Map.of("amount", 20, "profit", 5)));
        assertThat(scatter.charts().get(0).type()).isEqualTo("SCATTER");
    }

    @Test
    void avoidsAmbiguousDuplicateDimensionsAndHonorsTableChoice() {
        var rows = List.<Map<String, Object>>of(Map.of("group", "A", "customer_count", 2), Map.of("group", "A", "customer_count", 8));
        assertThat(assemble("AUTO", rows).charts()).isEmpty();
        assertThat(assemble("TABLE", rows).charts()).isEmpty();
        assertThat(assemble("AUTO", List.of()).charts()).isEmpty();
    }

    @Test
    void zeroWeightsDoNotProduceFabricatedMean() {
        var result = assemble("AUTO", List.of(Map.of("group", "A", "customer_count", 0, "avg_asset_amount", 2),
                Map.of("group", "B", "customer_count", 0, "avg_asset_amount", 8)));
        assertThat(result.metrics()).anySatisfy(metric -> {
            assertThat(metric.get("key")).isEqualTo("avg_asset_amount");
            assertThat(metric.get("value")).isNull();
        });
    }
    @Test
    void createsChartAndDeterministicAnalysisForGroupedRows() {
        var planned = new PlannedQuery("SELECT branch_id, COUNT(*) AS customer_count FROM dim_customer LIMIT 100",
                Map.of(), "AUTO", "机构客户分布", false);
        var rows = List.<Map<String, Object>>of(
                Map.of("branch_id", "B001", "customer_count", 18L),
                Map.of("branch_id", "B002", "customer_count", 12L));

        var result = new ResultAssembler(null).assemble(planned, rows, "RULE", 1.0);

        assertThat(result.charts()).hasSize(1);
        assertThat(result.charts().get(0).dimensionKey()).isEqualTo("branch_id");
        assertThat(result.analysis().insights()).anyMatch(text -> text.contains("B001"));
        assertThat(result.interpretationSource()).isEqualTo("RULE");
    }
}
