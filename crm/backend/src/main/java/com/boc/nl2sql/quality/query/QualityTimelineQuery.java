package com.boc.nl2sql.quality.query;

import com.boc.nl2sql.quality.persistence.QualityEventRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class QualityTimelineQuery {
    private final QualityEventRepository repository;
    private final ObjectMapper json;

    public QualityTimelineQuery(QualityEventRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    public List<Map<String, Object>> taskTimeline(String taskId, long afterId, int size) {
        if (taskId == null || taskId.isBlank()) return List.of();
        int safeSize = Math.max(1, Math.min(size, 500));
        return repository.timeline(taskId, Math.max(0, afterId), safeSize).stream().map(this::decode).toList();
    }

    private Map<String, Object> decode(Map<String, Object> row) {
        var result = new LinkedHashMap<>(row);
        Object payload = result.remove("payload_json");
        result.put("payload", payload == null ? Map.of() : json.readValue(payload.toString(), Map.class));
        return result;
    }
}
