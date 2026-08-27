package com.boc.nl2sql.execution.domain;

import java.util.List;

public record FallbackInfo(String reason, String templateId, boolean dataAvailable, List<String> suggestions) { }
