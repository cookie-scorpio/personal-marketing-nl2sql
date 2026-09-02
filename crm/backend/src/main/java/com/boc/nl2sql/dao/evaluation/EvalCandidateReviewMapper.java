package com.boc.nl2sql.dao.evaluation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.boc.nl2sql.domain.evaluation.EvalCandidateReviewEntity;

/** 候选审计事件审核结论的数据访问入口。 */
@Mapper
public interface EvalCandidateReviewMapper extends BaseMapper<EvalCandidateReviewEntity> {
}
