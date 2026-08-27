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
 * 高频营销问题的确定性语义解析器。
 *
 * <p>只有整句条件都在规则支持范围内才使用固定模板，其余问题交给模型，避免静默丢失限定条件。</p>
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

    /**
     * 判断当前问题是否可以由固定模板完整回答。
     *
     * <p>这里只接受边界明确的高频问法。诸如“年龄分布”“按月趋势”等虽然能识别出客户或交易主题，
     * 但固定模板无法保证回答正确，因此必须交给大模型结合数据库元数据生成 SQL。</p>
     */
    public boolean supportsDeterministicPlan(String rawText, SemanticQuery query) {
        String text = rawText == null ? "" : rawText;
        // 这些维度、统计方式和比较要求不属于固定模板，不能仅因出现“交易金额”等关键词就拦截为规则查询。
        if (containsAny(text, "年龄", "性别", "按月", "各月", "每月", "按日", "每日", "趋势", "同比", "环比",
                "渠道", "排名", "前10", "前十", "top", "中位数", "占比", "到期", "职业")) return false;
        // 当前客户/持有模板查询当前快照，不能把用户指定的历史区间悄悄丢掉。
        if ((query.intent() == IntentType.CUSTOMER_FILTER || query.intent() == IntentType.PRODUCT_HOLDING)
                && query.startDate() != null) return false;
        if ((query.intent() == IntentType.TRANSACTION_ANALYSIS || query.intent() == IntentType.MARKETING_ANALYSIS)
                && query.detailRequested()) return false;
        if (query.intent() == IntentType.PRODUCT_HOLDING
                && containsAny(text, "各机构", "各网点", "各分行")) return false;
        if (query.intent() == IntentType.MARKETING_ANALYSIS
                && containsAny(text, "各机构", "各网点", "各分行")) return false;
        if (query.productCategory() != null && query.intent() != IntentType.PRODUCT_HOLDING) return false;
        if (!hasOnlySupportedExpressions(text, query.intent())) return false;
        return switch (query.intent()) {
            case CUSTOMER_FILTER -> query.detailRequested() || query.customerLevel() != null
                    || query.minAsset() != null || query.maxAsset() != null || query.assetDropRate() != null
                    || containsAny(text, "各机构客户", "各网点客户", "各分行客户");
            case TRANSACTION_ANALYSIS -> containsAny(text, "各机构", "交易金额", "交易额", "交易笔数");
            case PRODUCT_HOLDING -> query.detailRequested() || query.productCategory() != null
                    || containsAny(text, "持有规模", "持仓规模", "产品分布");
            case MARKETING_ANALYSIS -> containsAny(text, "触达", "转化", "响应", "活动效果");
            case GENERIC_ANALYSIS, UNKNOWN -> false;
        };
    }

    /**
     * 对已识别的值和模板词逐项消耗；仍有剩余文本就视为未知约束。
     * 例如“南京客户名单”“交易金额超过5万元”不能只保留客户/交易主题后直接执行。
     */
    private boolean hasOnlySupportedExpressions(String text, IntentType intent) {
        String remainder = RELATIVE_TIME.matcher(text).replaceAll("");
        remainder = MIN_ASSET.matcher(remainder).replaceAll("");
        remainder = MAX_ASSET.matcher(remainder).replaceAll("");
        remainder = DROP_RATE.matcher(remainder).replaceAll("");
        if (intent == IntentType.MARKETING_ANALYSIS) remainder = CAMPAIGN_NAME.matcher(remainder).replaceAll("");
        remainder = remainder.replaceAll("今年以来|本年度|本季度|本月|今年|最终条件为|补充条件", "");
        remainder = remainder.replaceAll("高净值客户|高净客户|高净客群|高端客户|黄金客户|普通客户|大众客户|金卡", "");
        remainder = remainder.replaceAll("全部客户|所有客户|全量客户|不限范围|各机构|各网点|各分行", "");
        String topicWords = switch (intent) {
            case CUSTOMER_FILTER -> "客户列表|客户名单|客户数量|客户数|客户|客群|资产规模|总资产|资产";
            case TRANSACTION_ANALYSIS -> "交易金额|交易笔数|交易额|交易分析|客户数量|客户数|客户";
            case PRODUCT_HOLDING -> "持有规模|持仓规模|持有市值|产品分布|持有|持仓|理财|基金|存款|产品|客户";
            case MARKETING_ANALYSIS -> "营销活动|营销|活动效果|活动|触达人数|转化人数|响应人数|转化率|响应率|触达|转化|响应|效果|客户";
            default -> "(?!)";
        };
        remainder = remainder.replaceAll(topicWords, "");
        remainder = remainder.replaceAll("帮我|请问|请|查询|统计|分析|查看|筛选|找出|列出|哪些|名单|明细|列表|汇总|情况|的|和|及|与|元", "");
        return remainder.replaceAll("[\\s，。！？、：,.:!?]", "").isBlank();
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
            case GENERIC_ANALYSIS -> "自由数据分析";
            case UNKNOWN -> "待识别";
        };
    }

    private record DateRange(LocalDate startDate, LocalDate endDate) {
    }
}
