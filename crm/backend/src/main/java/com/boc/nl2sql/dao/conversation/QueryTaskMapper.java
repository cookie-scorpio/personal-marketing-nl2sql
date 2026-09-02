package com.boc.nl2sql.dao.conversation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.boc.nl2sql.domain.conversation.QueryTaskEntity;

/** 查询任务的 MyBatis-Plus 数据访问入口。 */
@Mapper
public interface QueryTaskMapper extends BaseMapper<QueryTaskEntity> {
}
