package com.boc.nl2sql.domain.quality;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * F 内部使用的完整持久化事件信封。
 *
 * <p>该对象由 {@code AsyncQualityFacts} 根据 {@link QualityFact} 补齐后生成，随后写入
 * {@code audit_event}，或在数据库故障时按 JSON 行写入补偿文件。业务模块不应直接构造它。</p>
 *
 * @param eventId 全局唯一事件编号，也是补偿重放的幂等键
 * @param schemaVersion 事件载荷结构版本；当前新事实为 1
 * @param eventType 已发生事实的类型
 * @param sourceModule 产生事实的职责模块
 * @param eventSource 事实场景，当前在线请求默认为 ONLINE
 * @param requestId HTTP 请求追踪编号
 * @param sessionId 会话编号
 * @param taskId 查询任务编号
 * @param messageId 消息编号，当前主要用于反馈关联
 * @param userId 触发事实的用户编号
 * @param modelCallId 单次模型调用编号
 * @param sqlAttemptId SQL 候选或修复尝试的关联编号
 * @param evaluationRunId 评测运行编号，第一阶段通常为空
 * @param evaluationCandidate 是否进入后续评测候选池
 * @param summary 用于列表和快速定位的短摘要
 * @param occurredAt 事实发生时间，不等同于数据库插入时间
 * @param payload 各类事实自己的完整详细内容
 */
public record QualityEvent(
        String eventId,
        int schemaVersion,
        String eventType,
        String sourceModule,
        String eventSource,
        String requestId,
        String sessionId,
        String taskId,
        Long messageId,
        Long userId,
        String modelCallId,
        String sqlAttemptId,
        String evaluationRunId,
        boolean evaluationCandidate,
        String summary,
        LocalDateTime occurredAt,
        Map<String, Object> payload
) { }
