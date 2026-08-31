package com.boc.nl2sql.common.privacy;

/** 客户字段的响应脱敏；不能替代SQL字段白名单与数据范围校验。 */
public final class CustomerMasking {
    private CustomerMasking() {}
    /**
     * 姓名脱敏：保首末字、中间全掩码（王小明→王*明，欧阳佳泽→欧**泽，李明→李*）。
     * 对已脱敏文本（如历史消息中的"王**"）再调用保持稳定，不产生二次变化。
     */
    public static String name(String value) {
        if (value == null || value.isBlank()) return "客户";
        int length = value.codePointCount(0, value.length());
        int first = value.offsetByCodePoints(0, 1);
        if (length <= 2) return value.substring(0, first) + "*";
        int lastStart = value.offsetByCodePoints(0, length - 1);
        return value.substring(0, first) + "*".repeat(length - 2) + value.substring(lastStart);
    }
    public static String mobile(String value) {
        if (value == null || value.isBlank()) return "未提供";
        return value.length() >= 8 ? value.substring(0, 3) + "****" + value.substring(value.length() - 4) : "****";
    }
}
