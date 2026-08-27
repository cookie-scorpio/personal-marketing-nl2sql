package com.boc.nl2sql.nl2sql.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 模型/规则解析后的受控语义对象；SQL规划器只读取此对象，不直接拼接用户原文。 */
public record SemanticQuery(
        IntentType intent,
        LocalDate startDate,
        LocalDate endDate,
        String customerLevel,
        BigDecimal minAsset,
        BigDecimal maxAsset,
        BigDecimal assetDropRate,
        String productCategory,
        String campaignKeyword,
        boolean detailRequested,
        boolean broadRequested,
        List<String> conflicts,
        Map<String, String> recognizedSlots
) {
}
