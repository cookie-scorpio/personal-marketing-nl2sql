package com.boc.nl2sql.history.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface QueryHistoryMapper extends BaseMapper<QueryHistoryEntity> {
}
