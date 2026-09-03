package com.boc.nl2sql.service.execution;

import java.util.LinkedHashSet;
import java.util.Set;

/** 一个数据源绑定。baseTable 为 null 表示 CTE 引用或派生表，其在内部查询块已完成证明。 */
final class Binding {
    final int id;                  // 来源编号，报错文案用它在多个数据源间定位
    final String baseTable;        // 物理表名；null = CTE 引用或派生表
    final Set<String> columns;     // 可引用列（物理表白名单列，或派生表输出列）
    boolean scopeProven;           // 账号模式：已证明被账号数据范围约束
    boolean customerProven;        // 客户模式：已证明被已确认客户约束

    private Binding(int id, String baseTable, Set<String> columns, boolean preProven) {
        this.id = id;
        this.baseTable = baseTable;
        this.columns = columns;
        this.scopeProven = preProven;
        this.customerProven = preProven;
    }

    static Binding physical(int id, String baseTable, Set<String> columns) {
        return new Binding(id, baseTable, columns, false);
    }

    static Binding derived(int id, Iterable<String> columns) {
        Set<String> copied = new LinkedHashSet<>();
        columns.forEach(copied::add);
        return new Binding(id, null, copied, true);
    }

    boolean isPhysical() {
        return baseTable != null;
    }
}
