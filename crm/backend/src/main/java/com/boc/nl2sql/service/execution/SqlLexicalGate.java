package com.boc.nl2sql.service.execution;

/** 词法防线：解析之前的文本形态即决（设计文档 3.1 节）。 */
final class SqlLexicalGate {
    private SqlLexicalGate() {
    }

    /** 拒绝一切无法被后续 AST 校验可靠覆盖的词法形态。 */
    static void check(String sql, SqlGuardPolicy policy) {
        if (sql == null || sql.isBlank() || sql.length() > policy.maxSqlLength())
            SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("SQL为空或超过长度限制");
        boolean insideString = false;
        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            if (insideString) {
                // MySQL 把 \' 当转义引号、解析器当字符串结束——两边对字符串边界的判定不同，
                // 证明时比较的字面量值就可能和数据库实际比较的不一致，直接拒绝。
                if (current == '\\')
                    SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("字符串转义方式未支持，请使用标准单引号转义");
                if (current == '\'') {
                    // 唯一允许的转义：标准 ''，跳过第二个引号后字符串继续
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'')
                        i++;
                    else
                        insideString = false;
                }
                continue;
            }
            if (current == '\'') {
                insideString = true;
                continue;
            }
            // MySQL 的 /*! */ 是"可执行注释"：解析器当注释丢弃、数据库照常执行，
            // 是只能在本层拦截的绕过形态；--、#、@、双引号、分号、反引号同理均不允许。
            boolean commentAhead = i + 1 < sql.length()
                    && ((current == '-' && sql.charAt(i + 1) == '-') || (current == '/' && sql.charAt(i + 1) == '*'));
            if (current == '@' || current == ';' || current == '#' || current == '"'
                    || current == '`' || commentAhead)
                SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("不允许反引号、变量、注释、多语句或双引号歧义");
        }
        if (insideString)
            SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("SQL引号未闭合");
    }
}
