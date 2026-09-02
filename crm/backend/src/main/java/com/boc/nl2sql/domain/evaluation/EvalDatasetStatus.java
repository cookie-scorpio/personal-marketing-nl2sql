package com.boc.nl2sql.domain.evaluation;

/** 评测集生命周期：草稿可增删改，发布后内容不可再变化。 */
public enum EvalDatasetStatus {
    DRAFT,
    PUBLISHED
}
