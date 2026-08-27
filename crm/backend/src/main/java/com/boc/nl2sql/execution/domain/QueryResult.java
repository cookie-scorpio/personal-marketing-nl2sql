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
        List<ChartSpec> charts,
        AnalysisSummary analysis,
        String sqlPreview,
        LocalDate dataAsOf,
        String interpretationSource,
        double confidence,
        FallbackInfo fallback
) {
    public QueryResult withFallback(FallbackInfo info) {
        return new QueryResult(resultType, title, info.dataAvailable() ? "已使用固定模板返回降级结果。" : info.reason(),
                columns, rows, metrics, charts, info.dataAvailable() ? analysis
                        : new AnalysisSummary(info.reason(), java.util.List.of("没有返回业务数据，未使用其他统计口径替代原问题。"), info.suggestions()),
                sqlPreview, dataAsOf, interpretationSource, confidence, info);
    }
}
