package com.boc.nl2sql.service.execution;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 查询块作用域：自身绑定 + 外层作用域链；aliases 为本块输出列别名（GROUP BY/HAVING/ORDER BY 可引用）；
 *  windowNames 为本块 WINDOW 子句定义的命名窗口（仅本块可见，与 MySQL 的块级作用域一致）。 */
final class Scope {
    final Scope parent;
    final Map<String, Binding> bindings = new LinkedHashMap<>();
    final Set<String> aliases = new HashSet<>();
    final Set<String> windowNames = new HashSet<>();

    Scope(Scope parent) {
        this.parent = parent;
    }
}
