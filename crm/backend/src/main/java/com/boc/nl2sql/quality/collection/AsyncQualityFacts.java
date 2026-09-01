package com.boc.nl2sql.quality.collection;

import com.boc.nl2sql.quality.event.QualityEvent;
import com.boc.nl2sql.quality.event.QualityFact;
import com.boc.nl2sql.quality.persistence.QualityEventRepository;
import com.boc.nl2sql.quality.persistence.QualityEventSpool;
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

/** F 的统一事实入口，负责补齐事件信息、事务后派发、异步落库和失败补偿。 */
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

    private void persist(QualityEvent event) {
        try {
            repository.save(event);
            meters.counter("nl2sql.quality.events.persisted", "type", event.eventType()).increment();
        } catch (RuntimeException error) {
            meters.counter("nl2sql.quality.events.persistence_failures", "type", event.eventType()).increment();
            appendToSpool(event);
        }
    }

    private void appendToSpool(QualityEvent event) {
        try {
            spool.append(event);
        } catch (RuntimeException error) {
            // F 的存储和补偿都不可用时只记录诊断日志，不能反向打断业务请求。
            log.error("F事实补偿失败：eventId={}, type={}", event.eventId(), event.eventType(), error);
        }
    }

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
