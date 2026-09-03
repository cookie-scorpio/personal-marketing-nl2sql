package com.boc.nl2sql.service.execution;

import java.util.HashSet;
import java.util.Set;

/** 一次 validate 调用的共享状态：来源编号、表达式节点预算、递归深度、IN 名单证明。 */
final class Run {
    int nextSourceId;
    int nodes;
    int expressionDepth;
    final Set<Fact> customerListProven = new HashSet<>();
}
