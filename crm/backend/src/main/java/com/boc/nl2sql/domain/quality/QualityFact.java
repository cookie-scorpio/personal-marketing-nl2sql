package com.boc.nl2sql.domain.quality;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务模块提交给质量子系统的类型化事实草稿。
 *
 * <p>调用方负责给出事实类型、来源、关联编号和详细内容；事件编号、发生时间、版本、
 * 持久化方式及失败补偿均由 F 补齐。构建后顶层载荷映射不可增删，避免调用方在异步入库前
 * 改变事实字段。</p>
 */
public final class QualityFact {
    private final QualityEventType type;
    private final String sourceModule;
    private final String summary;
    private final String requestId;
    private final String sessionId;
    private final String taskId;
    private final Long messageId;
    private final Long userId;
    private final String modelCallId;
    private final String sqlAttemptId;
    private final String evaluationRunId;
    private final String eventSource;
    private final boolean evaluationCandidate;
    private final Map<String, Object> payload;

    private QualityFact(Builder builder) {
        this.type = builder.type;
        this.sourceModule = builder.sourceModule;
        this.summary = builder.summary;
        this.requestId = builder.requestId;
        this.sessionId = builder.sessionId;
        this.taskId = builder.taskId;
        this.messageId = builder.messageId;
        this.userId = builder.userId;
        this.modelCallId = builder.modelCallId;
        this.sqlAttemptId = builder.sqlAttemptId;
        this.evaluationRunId = builder.evaluationRunId;
        this.eventSource = builder.eventSource;
        this.evaluationCandidate = builder.evaluationCandidate;
        this.payload = Map.copyOf(builder.payload);
    }

    /**
     * 创建事实构建器。
     *
     * @param type 已经发生的事实类型
     * @param sourceModule 产生事实的模块，例如 ACCESS、CONVERSATION、MODEL、EXECUTION 或 QUALITY
     */
    public static Builder builder(QualityEventType type, String sourceModule) {
        return new Builder(type, sourceModule);
    }

    public QualityEventType type() { return type; }
    public String sourceModule() { return sourceModule; }
    public String summary() { return summary; }
    public String requestId() { return requestId; }
    public String sessionId() { return sessionId; }
    public String taskId() { return taskId; }
    public Long messageId() { return messageId; }
    public Long userId() { return userId; }
    public String modelCallId() { return modelCallId; }
    public String sqlAttemptId() { return sqlAttemptId; }
    public String evaluationRunId() { return evaluationRunId; }
    public String eventSource() { return eventSource; }
    public boolean evaluationCandidate() { return evaluationCandidate; }
    public Map<String, Object> payload() { return payload; }

    /**
     * 链式构建事实草稿。关联字段均可选，但类型和来源模块必填。
     * {@link #detail(String, Object)} 与 {@link #details(Map)} 会忽略空键和空值。
     */
    public static final class Builder {
        private final QualityEventType type;
        private final String sourceModule;
        private String summary;
        private String requestId;
        private String sessionId;
        private String taskId;
        private Long messageId;
        private Long userId;
        private String modelCallId;
        private String sqlAttemptId;
        private String evaluationRunId;
        private String eventSource = "ONLINE";
        private boolean evaluationCandidate;
        private final Map<String, Object> payload = new LinkedHashMap<>();

        /** 校验并保存事实最小必填信息。 */
        private Builder(QualityEventType type, String sourceModule) {
            if (type == null) throw new IllegalArgumentException("事实类型不能为空");
            if (sourceModule == null || sourceModule.isBlank()) throw new IllegalArgumentException("事实来源模块不能为空");
            this.type = type;
            this.sourceModule = sourceModule;
        }

        public Builder summary(String value) { this.summary = value; return this; }
        public Builder requestId(String value) { this.requestId = value; return this; }
        public Builder sessionId(String value) { this.sessionId = value; return this; }
        public Builder taskId(String value) { this.taskId = value; return this; }
        public Builder messageId(Long value) { this.messageId = value; return this; }
        public Builder userId(Long value) { this.userId = value; return this; }
        public Builder modelCallId(String value) { this.modelCallId = value; return this; }
        public Builder sqlAttemptId(String value) { this.sqlAttemptId = value; return this; }
        public Builder evaluationRunId(String value) { this.evaluationRunId = value; return this; }
        public Builder eventSource(String value) { this.eventSource = value; return this; }
        public Builder evaluationCandidate(boolean value) { this.evaluationCandidate = value; return this; }
        public Builder detail(String key, Object value) { if (key != null && value != null) payload.put(key, value); return this; }
        public Builder details(Map<String, ?> values) {
            if (values != null) values.forEach((key, value) -> { if (key != null && value != null) payload.put(key, value); });
            return this;
        }
        public QualityFact build() { return new QualityFact(this); }
    }
}
