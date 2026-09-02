package com.boc.nl2sql.dao.evaluation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.boc.nl2sql.domain.evaluation.EvalDatasetEntity;

/** 评测集的 MyBatis-Plus 数据访问入口。 */
@Mapper
public interface EvalDatasetMapper extends BaseMapper<EvalDatasetEntity> {
}
