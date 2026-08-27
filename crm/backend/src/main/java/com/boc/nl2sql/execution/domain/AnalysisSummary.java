package com.boc.nl2sql.execution.domain;

import java.util.List;

/** 对查询结果进行确定性计算后形成的基础分析，不依赖大模型二次总结。 */
public record AnalysisSummary(String overview, List<String> insights, List<String> suggestions) {
}
