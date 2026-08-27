package com.boc.nl2sql.model;

import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.nl2sql.application.RuleBasedSemanticParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ModelGateway {
    private final String configuredProvider;
    private final List<ModelAdapter> adapters;
    private final RuleBasedSemanticParser ruleParser;

    public ModelGateway(@Value("${app.model.provider:mock}") String configuredProvider,
                        List<ModelAdapter> adapters,
                        RuleBasedSemanticParser ruleParser) {
        this.configuredProvider = configuredProvider;
        this.adapters = adapters;
        this.ruleParser = ruleParser;
    }

    /**
     * 高频明确场景优先走规则，只有固定模板无法完整回答时才调用模型。
     * 这既降低模型调用量，也避免简单场景受模型输出波动影响。
     */
    public QueryInterpretation interpret(String queryText, CurrentUser user) {
        var ruleSemantic = ruleParser.parse(queryText);
        if (ruleParser.supportsDeterministicPlan(queryText, ruleSemantic)) {
            return QueryInterpretation.rule(ruleSemantic);
        }

        ModelAdapter adapter = adapters.stream()
                .filter(candidate -> candidate.provider().equalsIgnoreCase(configuredProvider))
                .findFirst()
                .orElseThrow(() -> new BusinessException(503101, "未找到已配置的大模型适配器"));
        if (!adapter.available() || "mock".equalsIgnoreCase(adapter.provider())) {
            throw new BusinessException(503102,
                    "该问题超出规则快速查询范围，请配置 DeepSeek V4 Flash 后重试");
        }
        return adapter.interpret(queryText, user);
    }

    public String activeProvider() {
        return configuredProvider;
    }
}
