package com.boc.nl2sql.service.quality;

import com.boc.nl2sql.domain.quality.QualityEvent;
import com.boc.nl2sql.domain.quality.QualityFact;
import com.boc.nl2sql.dao.quality.QualityEventRepository;
import com.boc.nl2sql.dao.quality.QualityEventSpool;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * 质量子系统的统一事实入口，负责补齐事件信息、事务后派发、异步落库和失败补偿。
 *
 * <p>该组件只观察业务结果。即使数据库、线程池和本地补偿文件同时不可用，也不得
 * 抛出异常改变查询任务状态或正常结果。</p>
 */
@Component
public class AsyncQualityFacts implements QualityFacts {
    private static final Logger log = LoggerFactory.getLogger(AsyncQualityFacts.class);
    private final Executor executor;
    private final QualityEventRepository repository;
    private final QualityEventSpool spool;
    private final MeterRegistry meters;

    public AsyncQualityFacts(@Qualifier("qualityEventExecutor") Executor executor,
                             QualityEventRepository repository, QualityEventSpool spool, MeterRegistry meters) {
        this.executor = executor;
        this.repository = repository;
        this.spool = spool;
        this.meters = meters;
    }

    /**
     * 接收事实并安排持久化。如果调用方位于事务中，则注册提交后回调，避免保存已回滚业务的事实。
     */
    @Override
    public void publish(QualityFact fact) {
        QualityEvent event = envelope(fact);
        Runnable dispatch = () -> dispatch(event);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { dispatch.run(); }
            });
        } else {
            dispatch.run();
        }
    }

    /** 将事实提交到 F 专用队列；队列拒绝或派发异常时立即转入本地补偿。 */
    private void dispatch(QualityEvent event) {
        try {
            executor.execute(() -> persist(event));
            meters.counter("nl2sql.quality.events.accepted", "type", event.eventType()).increment();
        } catch (TaskRejectedException rejected) {
            meters.counter("nl2sql.quality.events.queue_rejected").increment();
            appendToSpool(event);
        } catch (RuntimeException unexpected) {
            log.warn("F事实派发失败，转入补偿：eventId={}, type={}", event.eventId(), event.eventType());
            appendToSpool(event);
        }
    }

    /** 异步写入正式事实表并记录结果指标；数据库失败时保留到 JSONL。 */
    private void persist(QualityEvent event) {
        try {
            repository.save(event);
            meters.counter("nl2sql.quality.events.persisted", "type", event.eventType()).increment();
        } catch (RuntimeException error) {
            meters.counter("nl2sql.quality.events.persistence_failures", "type", event.eventType()).increment();
            appendToSpool(event);
        }
    }

    /**
     * 执行最终降级写入。再次捕获异常，保证存储与补偿同时故障时也不会污染业务调用栈。
     */
    private void appendToSpool(QualityEvent event) {
        try {
            spool.append(event);
        } catch (RuntimeException error) {
            // 质量存储和补偿都不可用时只记录诊断日志，不能反向打断业务请求。
            log.error("F事实补偿失败：eventId={}, type={}", event.eventId(), event.eventType(), error);
        }
    }

    /**
     * 将事实草稿转换成完整事件：生成幂等编号、固定结构版本、限制摘要长度并记录发生时间。
     */
    private QualityEvent envelope(QualityFact fact) {
        String summary = fact.summary() == null || fact.summary().isBlank() ? fact.type().name() : fact.summary().strip();
        if (summary.length() > 500) summary = summary.substring(0, 500);
        String eventSource = fact.eventSource() == null || fact.eventSource().isBlank() ? "ONLINE" : fact.eventSource();
        Map<String, Object> payload = fact.payload() == null ? Map.of() : fact.payload();
        return new QualityEvent(UUID.randomUUID().toString(), 1, fact.type().name(), fact.sourceModule(), eventSource,
                fact.requestId(), fact.sessionId(), fact.taskId(), fact.messageId(), fact.userId(), fact.modelCallId(),
                fact.sqlAttemptId(), fact.evaluationRunId(), fact.evaluationCandidate(), summary,
                LocalDateTime.now(), payload);
    }
}
