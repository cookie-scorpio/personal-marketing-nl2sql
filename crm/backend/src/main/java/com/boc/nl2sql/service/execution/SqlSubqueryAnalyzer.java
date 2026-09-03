package com.boc.nl2sql.service.execution;

import net.sf.jsqlparser.statement.select.Select;

import java.util.Map;

/** 表达式校验器回写校验器的入口：表达式位置的子查询作为完整查询块递归进入（关联子查询由此支撑）。 */
interface SqlSubqueryAnalyzer {
    void analyze(Select select, Scope outer, Map<String, java.util.List<String>> ctes, int depth, Run run);
}
