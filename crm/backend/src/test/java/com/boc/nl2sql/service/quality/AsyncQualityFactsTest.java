package com.boc.nl2sql.service.quality;

import com.boc.nl2sql.domain.quality.QualityEvent;
import com.boc.nl2sql.domain.quality.QualityEventType;
import com.boc.nl2sql.domain.quality.QualityFact;
import com.boc.nl2sql.dao.quality.QualityEventRepository;
import com.boc.nl2sql.dao.quality.QualityEventSpool;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThatCode;

class AsyncQualityFactsTest {
    @Test
    void persistenceFailureFallsBackToDurableSpoolWithoutEscapingToCaller() {
        var repository=mock(QualityEventRepository.class);
        var spool=mock(QualityEventSpool.class);
        doThrow(new IllegalStateException("database unavailable")).when(repository).save(any(QualityEvent.class));
        var collector=new AsyncQualityFacts(Runnable::run,repository,spool,new SimpleMeterRegistry());

        collector.publish(QualityFact.builder(QualityEventType.QUERY_RECEIVED,"CONVERSATION")
                .taskId("task").summary("received").build());

        verify(spool).append(any(QualityEvent.class));
    }

    @Test
    void databaseAndSpoolFailureStillDoesNotEscapeToBusinessCaller() {
        var repository=mock(QualityEventRepository.class);
        var spool=mock(QualityEventSpool.class);
        doThrow(new IllegalStateException("database unavailable")).when(repository).save(any(QualityEvent.class));
        doThrow(new IllegalStateException("disk unavailable")).when(spool).append(any(QualityEvent.class));
        var collector=new AsyncQualityFacts(Runnable::run,repository,spool,new SimpleMeterRegistry());

        assertThatCode(() -> collector.publish(QualityFact.builder(QualityEventType.QUERY_RECEIVED,"CONVERSATION")
                .taskId("task").summary("received").build())).doesNotThrowAnyException();
    }
}
