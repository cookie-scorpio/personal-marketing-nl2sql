package com.boc.nl2sql.model;

import com.boc.nl2sql.domain.authorization.CurrentUser;
import com.boc.nl2sql.service.nl2sql.RuleBasedSemanticParser;
import org.springframework.stereotype.Component;

/** 不调用外部模型的确定性适配器，用于本地默认运行和可重复的规则验收。 */
@Component
public class MockModelAdapter implements ModelAdapter {
    private final RuleBasedSemanticParser parser;

    public MockModelAdapter(RuleBasedSemanticParser parser) {
        this.parser = parser;
    }

    @Override
    public String provider() {
        return "mock";
    }

    @Override
    public QueryInterpretation interpret(String queryText, CurrentUser user) {
        return QueryInterpretation.rule(parser.parse(queryText));
    }
}
