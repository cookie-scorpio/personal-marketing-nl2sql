package com.boc.nl2sql.common.privacy;

/** 客户字段的响应脱敏；不能替代SQL字段白名单与数据范围校验。 */
public final class CustomerMasking {
    private CustomerMasking() {}
    public static String name(String value) {
        if (value == null || value.isBlank()) return "客户";
        int first = value.offsetByCodePoints(0, 1);
        return value.substring(0, first) + "*".repeat(Math.max(1, value.codePointCount(0, value.length()) - 1));
    }
    public static String mobile(String value) {
        if (value == null || value.isBlank()) return "未提供";
        return value.length() >= 8 ? value.substring(0, 3) + "****" + value.substring(value.length() - 4) : "****";
    }
}
