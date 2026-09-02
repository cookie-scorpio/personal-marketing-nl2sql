package com.boc.nl2sql.dao.evaluation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.boc.nl2sql.domain.evaluation.EvalRunItemEntity;

/** 评测运行明细的数据访问入口。 */
@Mapper
public interface EvalRunItemMapper extends BaseMapper<EvalRunItemEntity> {
}
