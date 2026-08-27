package com.boc.nl2sql.execution.application;

import com.boc.nl2sql.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SQL执行前的第二道防线：只读、单语句、对象白名单和显式结果上限。 */
@Component
public class SqlSafetyValidator {
    private static final Pattern TABLE_REFERENCE = Pattern.compile("(?i)\\b(?:from|join)\\s+([a-zA-Z0-9_]+)");
    private static final Set<String> ALLOWED_TABLES = Set.of(
            "dim_customer", "fct_transaction", "fct_product_holding",
            "dim_marketing_campaign", "fct_customer_marketing");
    private static final Set<String> FORBIDDEN = Set.of(
            " insert ", " update ", " delete ", " drop ", " alter ", " create ",
            " truncate ", " grant ", " revoke ", " call ", " outfile ", " load_file");

    public void validate(String sql) {
        String normalized = " " + sql.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ") + " ";
        if (!normalized.stripLeading().startsWith("select ")) {
            throw new BusinessException(422101, "SQL安全校验未通过：仅允许SELECT查询");
        }
        if (normalized.contains(";") || FORBIDDEN.stream().anyMatch(normalized::contains)) {
            throw new BusinessException(422101, "SQL安全校验未通过：检测到非只读或多语句操作");
        }
        Matcher matcher = TABLE_REFERENCE.matcher(sql);
        while (matcher.find()) {
            if (!ALLOWED_TABLES.contains(matcher.group(1).toLowerCase(Locale.ROOT))) {
                throw new BusinessException(403102, "SQL安全校验未通过：数据对象不在白名单中");
            }
        }
        if (!normalized.matches("(?s).*\\blimit\\s+\\d+.*")) {
            throw new BusinessException(422102, "SQL安全校验未通过：查询缺少结果行数限制");
        }
    }
}
