package com.boc.nl2sql.execution.domain;

import java.util.List;
import java.util.Map;

/** 数据库分页执行结果；total始终是应用分页前的结果总行数。 */
public record PagedQueryRows(List<Map<String, Object>> rows, long total, QueryPage page, String sqlPreview) {
    public PagedQueryRows {
        rows = List.copyOf(rows);
    }

    public PagedQueryRows(List<Map<String, Object>> rows, long total, QueryPage page) {
        this(rows,total,page,null);
    }

    public boolean hasMore() {
        return page.offset() + rows.size() < total;
    }
}
