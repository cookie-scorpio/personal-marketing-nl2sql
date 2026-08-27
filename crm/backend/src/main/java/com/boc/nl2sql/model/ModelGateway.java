package com.boc.nl2sql.model;

import com.boc.nl2sql.nl2sql.domain.SemanticQuery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ModelGateway {
    private final String configuredProvider;
    private final List<ModelAdapter> adapters;

    public ModelGateway(@Value("${app.model.provider:mock}") String configuredProvider,
                        List<ModelAdapter> adapters) {
        this.configuredProvider = configuredProvider;
        this.adapters = adapters;
    }

    public SemanticQuery interpret(String queryText) {
        ModelAdapter adapter = adapters.stream()
                .filter(candidate -> candidate.provider().equalsIgnoreCase(configuredProvider))
                .findFirst()
                .orElseGet(() -> adapters.stream()
                        .filter(candidate -> candidate.provider().equals("mock"))
                        .findFirst()
                        .orElseThrow());
        return adapter.interpret(queryText);
    }

    public String activeProvider() {
        return configuredProvider;
    }
}
