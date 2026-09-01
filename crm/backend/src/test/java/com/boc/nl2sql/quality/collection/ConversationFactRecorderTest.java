package com.boc.nl2sql.quality.collection;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.boc.nl2sql.quality.event.QualityFact;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ConversationFactRecorderTest {
    @Test
    void keepsCompleteConversationContentInDiagnosticLogAndDatabaseFact() {
        var logger=(Logger)LoggerFactory.getLogger("CONVERSATION");
        var appender=new ListAppender<ILoggingEvent>();appender.start();logger.addAppender(appender);
        QualityFacts facts=mock(QualityFacts.class);
        try{
            new ConversationFactRecorder(JsonMapper.builder().build(),facts).record("request","session","task-test",1L,
                    "ASSISTANT","SUCCESS",4,"手机号13812345678",
                    Map.of("result",Map.of("rows",java.util.List.of(Map.of("private_data","完整保存")))));
            String line=appender.list.get(0).getFormattedMessage();
            assertThat(line).contains("13812345678","private_data","完整保存");
            var captor=forClass(QualityFact.class);verify(facts).publish(captor.capture());
            assertThat(captor.getValue().payload().toString()).contains("13812345678","完整保存");
        }finally{logger.detachAppender(appender);appender.stop();}
    }
}
