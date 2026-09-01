package com.boc.nl2sql.quality.collection;

import com.boc.nl2sql.quality.event.QualityFact;

/** 其他 Module 感知 F 的唯一写入 Seam。 */
public interface QualityFacts {
    void publish(QualityFact fact);
}
