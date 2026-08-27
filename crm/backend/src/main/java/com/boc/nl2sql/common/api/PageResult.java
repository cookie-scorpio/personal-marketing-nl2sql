package com.boc.nl2sql.common.api;

import java.util.List;

public record PageResult<T>(List<T> items, long total, int pageNo, int pageSize) {
}
