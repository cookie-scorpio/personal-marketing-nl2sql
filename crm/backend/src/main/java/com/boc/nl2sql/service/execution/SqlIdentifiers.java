package com.boc.nl2sql.service.execution;

import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.FromItem;

import java.util.Locale;

/** 标识符工具：规范化与数据源别名提取。 */
final class SqlIdentifiers {
    private SqlIdentifiers() {
    }

    /**
     * 标识符规范化：转小写并按 [a-z_][a-z0-9_]* 白名单校验。
     * 反引号已在词法层整体禁止，引用语义（转义、大小写、空格）一整类分叉随之消失；
     * 此处若仍出现反引号等非法字符，regex 会自然拒绝（纵深防御）。
     */
    static String normalizeIdentifier(String name) {
        if (name == null)
            return "";
        String normalized = name.toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z_][a-z0-9_]*"))
            SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("标识符必须为英文列名或别名");
        return normalized;
    }

    /** 数据源别名：有别名用别名，物理表没有别名时用表名本身；派生表必须显式别名。 */
    static String sourceAlias(FromItem from) {
        if (from.getAlias() != null)
            return normalizeIdentifier(from.getAlias().getName());
        if (from instanceof Table table)
            return normalizeIdentifier(table.getName());
        SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("数据源需要别名");
        return "";
    }
}
