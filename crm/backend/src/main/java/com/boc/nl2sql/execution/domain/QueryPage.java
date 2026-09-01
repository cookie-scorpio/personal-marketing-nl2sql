package com.boc.nl2sql.execution.domain;

/** 已校验并持久化的查询分页参数。 */
public record QueryPage(int pageNo, int pageSize, long offset) {
    public QueryPage {
        if (pageNo < 1 || pageSize < 1 || offset < 0) throw new IllegalArgumentException("分页参数必须为正数");
    }
}
