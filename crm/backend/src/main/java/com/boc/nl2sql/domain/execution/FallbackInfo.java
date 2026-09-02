package com.boc.nl2sql.domain.execution;

import java.util.List;

/** 降级结果的来源和可用性说明；dataAvailable=false 时不得伪装成原问题的统计结果。 */
public record FallbackInfo(String reason, String templateId, boolean dataAvailable, List<String> suggestions) { }
