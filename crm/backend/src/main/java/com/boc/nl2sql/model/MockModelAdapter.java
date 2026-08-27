package com.boc.nl2sql.model;

import com.boc.nl2sql.nl2sql.application.RuleBasedSemanticParser;
import com.boc.nl2sql.nl2sql.domain.SemanticQuery;
import org.springframework.stereotype.Component;

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
    public SemanticQuery interpret(String queryText) {
        return parser.parse(queryText);
    }
}
