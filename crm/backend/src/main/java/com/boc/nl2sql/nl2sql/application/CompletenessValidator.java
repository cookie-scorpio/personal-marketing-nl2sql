package com.boc.nl2sql.nl2sql.application;

import com.boc.nl2sql.nl2sql.domain.ClarificationQuestion;
import com.boc.nl2sql.nl2sql.domain.IntentType;
import com.boc.nl2sql.nl2sql.domain.SemanticQuery;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 将“必须补什么”固定为可审计规则；模型只能负责表达，不能绕过规则直接生成 SQL。 */
@Component
public class CompletenessValidator {

    public Optional<ClarificationQuestion> validate(SemanticQuery query) {
        return validate(query, false);
    }

    /** 自由查询的必填项由模型结合元数据判断，但条件矛盾仍进入统一校验。 */
    public Optional<ClarificationQuestion> validate(SemanticQuery query, boolean modelGenerated) {
        if (!query.conflicts().isEmpty()) {
            return Optional.of(new ClarificationQuestion(UUID.randomUUID().toString(), "CONFLICT",
                    "检测到条件矛盾：" + String.join("；", query.conflicts()) + "。请明确最终条件。",
                    List.of(), query.recognizedSlots()));
        }
        if (query.intent() == IntentType.UNKNOWN) {
            return Optional.of(new ClarificationQuestion(UUID.randomUUID().toString(), "MISSING_TOPIC",
                    "你希望分析哪一类营销数据？", List.of("客户筛选", "交易分析", "产品持有", "营销活动"),
                    query.recognizedSlots()));
        }
        if (!modelGenerated && (query.intent() == IntentType.TRANSACTION_ANALYSIS || query.intent() == IntentType.MARKETING_ANALYSIS)
                && query.startDate() == null) {
            return Optional.of(new ClarificationQuestion(UUID.randomUUID().toString(), "MISSING_TIME_RANGE",
                    "还需要确认时间范围，补充后才能按正确口径查询。",
                    List.of("近7天", "近30天", "本季度", "今年以来"), query.recognizedSlots()));
        }
        return Optional.empty();
    }
}
