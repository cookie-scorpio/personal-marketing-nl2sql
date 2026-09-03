package com.boc.nl2sql.service.execution;

/** 授权证明中的事实单元：某数据源的某列，或一个具体取值（字面量/命名参数解析值）。 */
sealed interface Fact permits ColumnFact, ValueFact {
}

/** 事实一：某个数据源的某个列。 */
record ColumnFact(Binding binding, String column) implements Fact {
}

/** 事实二：一个具体取值（字符串字面量，或命名参数在服务端参数表中的解析值）。 */
record ValueFact(String value) implements Fact {
}

/** 有向事实边：from 上的事实可传播到 to（LEFT JOIN 的单侧约束靠方向性实现）。 */
record Edge(Fact from, Fact to) {
}
