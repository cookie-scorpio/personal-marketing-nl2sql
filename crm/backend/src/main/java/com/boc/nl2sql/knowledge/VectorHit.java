package com.boc.nl2sql.knowledge;

/** 一条向量检索命中：refId指向业务表主键，contentText为被向量化的原文，score为余弦相似度。 */
public record VectorHit(String refId, String contentText, double score) {}
