package com.boc.nl2sql.service.quality;

import com.boc.nl2sql.dao.quality.QualityEventRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将数据库事实行转换为管理员可读取的任务时间线。 */
@Component
public class QualityTimelineQuery {
    private final QualityEventRepository repository;
    private final ObjectMapper json;

    public QualityTimelineQuery(QualityEventRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    /**
     * 分页读取一个任务的事实。
     * 空任务编号返回空列表，afterId 不得小于 0，单页数量被收敛到 1 至 500。
     */
    public List<Map<String, Object>> taskTimeline(String taskId, long afterId, int size) {
        if (taskId == null || taskId.isBlank()) return List.of();
        int safeSize = Math.max(1, Math.min(size, 500));
        return repository.timeline(taskId, Math.max(0, afterId), safeSize).stream().map(this::decode).toList();
    }

    /** 把数据库 payload_json 字符串解析为接口中的结构化 payload 字段。 */
    private Map<String, Object> decode(Map<String, Object> row) {
        var result = new LinkedHashMap<>(row);
        Object payload = result.remove("payload_json");
        result.put("payload", payload == null ? Map.of() : json.readValue(payload.toString(), Map.class));
        return result;
    }
}
