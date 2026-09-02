package com.boc.nl2sql.dao.evaluation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.boc.nl2sql.domain.evaluation.EvalRunEntity;

/** 评测运行聚合记录的数据访问入口。 */
@Mapper
public interface EvalRunMapper extends BaseMapper<EvalRunEntity> {
}
