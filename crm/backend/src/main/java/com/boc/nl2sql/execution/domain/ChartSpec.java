package com.boc.nl2sql.execution.domain;

import java.util.List;

/** 后端根据结果形态生成的图表描述，前端据此构造ECharts配置。 */
public record ChartSpec(String type, String title, String dimensionKey, List<ChartSeries> series,
                        String dimensionLabel, String secondaryDimensionKey, String reason) {
    public ChartSpec(String type, String title, String dimensionKey, List<ChartSeries> series) {
        this(type, title, dimensionKey, series, dimensionKey, null, null);
    }
}
