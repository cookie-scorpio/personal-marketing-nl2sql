package com.boc.nl2sql.dao.evaluation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.boc.nl2sql.domain.evaluation.EvalDatasetItemEntity;

/** 评测集条目的数据访问入口。 */
@Mapper
public interface EvalDatasetItemMapper extends BaseMapper<EvalDatasetItemEntity> {
}
