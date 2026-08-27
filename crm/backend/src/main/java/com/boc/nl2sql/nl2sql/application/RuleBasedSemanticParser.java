package com.boc.nl2sql.nl2sql.application;

import com.boc.nl2sql.nl2sql.domain.IntentType;
import com.boc.nl2sql.nl2sql.domain.SemanticQuery;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MVP 的确定性语义解析器。
 *
 * <p>它只识别经过验收的营销业务表达，保证 Mock 模式可重复测试；真实模型接入后仍需输出同一 SemanticQuery。</p>
 */
@Component
public class RuleBasedSemanticParser {
    private static final Pattern RELATIVE_TIME = Pattern.compile("近\\s*([0-9一二三四五六七八九十半]+)\\s*(天|日|个?月|年)");
    private static final Pattern MIN_ASSET = Pattern.compile("资产(?:规模)?\\s*(?:超过|大于|高于|不少于|不低于|>=?)\\s*(\\d+(?:\\.\\d+)?)\\s*(万|亿)?");
    private static final Pattern MAX_ASSET = Pattern.compile("资产(?:规模)?\\s*(?:低于|小于|不超过|不高于|<=?)\\s*(\\d+(?:\\.\\d+)?)\\s*(万|亿)?");
    private static final Pattern DROP_RATE = Pattern.compile("资产(?:下降|下滑|减少)(?:超过|大于)?\\s*(\\d+(?:\\.\\d+)?)\\s*%");
    private static final Pattern CAMPAIGN_NAME = Pattern.compile("[“\"]([^”\"]+)[”\"](?:活动)?");

    public SemanticQuery parse(String rawText) {
        String text = rawText == null ? "" : rawText.trim();
        // 用户针对矛盾条件给出“最终条件”时，仅用最终片段解析过滤值，但仍用完整问题识别业务主题。
        String conditionText = text.contains("最终条件为：")
                ? text.substring(text.lastIndexOf("最终条件为：") + "最终条件为：".length())
                : text;
        IntentType intent = detectIntent(text);
        List<String> conflicts = new ArrayList<>();
        Map<String, String> slots = new LinkedHashMap<>();

        DateRange dateRange = parseDateRange(conditionText, conflicts);
        if (dateRange != null) {
            slots.put("时间范围", dateRange.startDate() + " 至 " + dateRange.endDate());
        }

        String customerLevel = detectCustomerLevel(conditionText);
        if (customerLevel != null) {
            slots.put("客户范围", customerLevelLabel(customerLevel));
        }
        BigDecimal minAsset = extractMoney(MIN_ASSET, conditionText);
        BigDecimal maxAsset = extractMoney(MAX_ASSET, conditionText);
        if (minAsset != null) {
            slots.put("最低资产", formatWan(minAsset));
        }
        if (maxAsset != null) {
            slots.put("最高资产", formatWan(maxAsset));
        }
        if (minAsset != null && maxAsset != null && minAsset.compareTo(maxAsset) > 0) {
            conflicts.add("最低资产条件高于最高资产条件");
        }

        BigDecimal assetDropRate = extractPercent(DROP_RATE, conditionText);
        if (assetDropRate != null) {
            slots.put("资产下降幅度", assetDropRate.stripTrailingZeros().toPlainString() + "%");
        }

        String productCategory = detectProductCategory(conditionText);
        if (productCategory != null) {
            slots.put("产品类型", productCategoryLabel(productCategory));
        }
        String campaignKeyword = extractCampaignKeyword(text);
        if (campaignKeyword != null) {
            slots.put("活动关键词", campaignKeyword);
        }
        if (intent != IntentType.UNKNOWN) {
            slots.put("查询主题", intentLabel(intent));
        }

        boolean detailRequested = containsAny(text, "哪些", "名单", "明细", "找出", "列出", "客户列表");
        boolean broadRequested = containsAny(text, "全部客户", "所有客户", "全量客户", "不限范围");
        return new SemanticQuery(intent,
                dateRange == null ? null : dateRange.startDate(),
                dateRange == null ? null : dateRange.endDate(),
                customerLevel, minAsset, maxAsset, assetDropRate, productCategory, campaignKeyword,
                detailRequested, broadRequested, List.copyOf(conflicts), Map.copyOf(slots));
    }

    private IntentType detectIntent(String text) {
        if (containsAny(text, "营销", "活动", "触达", "转化", "响应率")) {
            return IntentType.MARKETING_ANALYSIS;
        }
        if (containsAny(text, "交易", "消费", "转账", "取款", "流水", "交易额")) {
            return IntentType.TRANSACTION_ANALYSIS;
        }
        if (containsAny(text, "持有", "持仓", "理财", "基金", "存款", "产品")) {
            return IntentType.PRODUCT_HOLDING;
        }
        if (containsAny(text, "客户", "客群", "高净", "资产", "流失")) {
            return IntentType.CUSTOMER_FILTER;
        }
        return IntentType.UNKNOWN;
    }

    private DateRange parseDateRange(String text, List<String> conflicts) {
        List<DateRange> ranges = new ArrayList<>();
        LocalDate today = LocalDate.now();
        Matcher matcher = RELATIVE_TIME.matcher(text);
        while (matcher.find()) {
            int amount = parseTimeAmount(matcher.group(1));
            String unit = matcher.group(2);
            LocalDate start = "半".equals(matcher.group(1)) && unit.equals("年") ? today.minusMonths(6)
                    : unit.contains("月") ? today.minusMonths(amount)
                    : unit.equals("年") ? today.minusYears(amount) : today.minusDays(amount);
            ranges.add(new DateRange(start, today));
        }
        if (text.contains("本月")) {
            ranges.add(new DateRange(today.withDayOfMonth(1), today));
        }
        if (text.contains("本季度")) {
            int firstMonth = ((today.getMonthValue() - 1) / 3) * 3 + 1;
            ranges.add(new DateRange(LocalDate.of(today.getYear(), firstMonth, 1), today));
        }
        if (text.contains("今年以来") || text.contains("本年度") || text.contains("今年")) {
            ranges.add(new DateRange(today.with(TemporalAdjusters.firstDayOfYear()), today));
        }
        if (ranges.size() > 1 && ranges.stream().map(DateRange::startDate).distinct().count() > 1) {
            conflicts.add("问题中包含多个不一致的时间范围");
        }
        return ranges.isEmpty() ? null : ranges.get(ranges.size() - 1);
    }

    private String detectCustomerLevel(String text) {
        if (containsAny(text, "高净值", "高净客户", "高净客群", "高端客户")) return "PLATINUM";
        if (containsAny(text, "金卡", "黄金客户")) return "GOLD";
        if (containsAny(text, "普通客户", "大众客户")) return "NORMAL";
        return null;
    }

    private String detectProductCategory(String text) {
        if (text.contains("理财")) return "WEALTH";
        if (text.contains("基金")) return "FUND";
        if (text.contains("存款")) return "DEPOSIT";
        return null;
    }

    private String extractCampaignKeyword(String text) {
        Matcher matcher = CAMPAIGN_NAME.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private int parseTimeAmount(String value) {
        if (value.chars().allMatch(Character::isDigit)) return Integer.parseInt(value);
        if ("半".equals(value)) return 1;
        Map<Character, Integer> digits = Map.of('一', 1, '二', 2, '三', 3, '四', 4, '五', 5,
                '六', 6, '七', 7, '八', 8, '九', 9);
        if (value.equals("十")) return 10;
        if (value.startsWith("十")) return 10 + digits.getOrDefault(value.charAt(1), 0);
        if (value.endsWith("十")) return digits.getOrDefault(value.charAt(0), 1) * 10;
        if (value.contains("十")) return digits.getOrDefault(value.charAt(0), 1) * 10
                + digits.getOrDefault(value.charAt(value.length() - 1), 0);
        return digits.getOrDefault(value.charAt(0), 1);
    }

    private BigDecimal extractMoney(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return null;
        BigDecimal amount = new BigDecimal(matcher.group(1));
        String unit = matcher.group(2);
        if ("万".equals(unit)) amount = amount.multiply(BigDecimal.valueOf(10_000));
        if ("亿".equals(unit)) amount = amount.multiply(BigDecimal.valueOf(100_000_000));
        return amount;
    }

    private BigDecimal extractPercent(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? new BigDecimal(matcher.group(1)) : null;
    }

    private boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.toLowerCase(Locale.ROOT).contains(candidate.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private String formatWan(BigDecimal amount) {
        return amount.divide(BigDecimal.valueOf(10_000)).stripTrailingZeros().toPlainString() + "万元";
    }

    private String customerLevelLabel(String code) {
        return switch (code) {
            case "PLATINUM" -> "高净值客户";
            case "GOLD" -> "黄金客户";
            default -> "普通客户";
        };
    }

    private String productCategoryLabel(String code) {
        return switch (code) {
            case "WEALTH" -> "理财";
            case "FUND" -> "基金";
            default -> "存款";
        };
    }

    private String intentLabel(IntentType intent) {
        return switch (intent) {
            case CUSTOMER_FILTER -> "客户筛选";
            case TRANSACTION_ANALYSIS -> "交易分析";
            case PRODUCT_HOLDING -> "产品持有分析";
            case MARKETING_ANALYSIS -> "营销活动分析";
            case UNKNOWN -> "待识别";
        };
    }

    private record DateRange(LocalDate startDate, LocalDate endDate) {
    }
}
