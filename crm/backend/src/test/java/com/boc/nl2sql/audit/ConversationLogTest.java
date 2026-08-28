package com.boc.nl2sql.audit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.boc.nl2sql.conversation.infrastructure.QueryTaskEntity;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ConversationLogTest {
    @Test void redactsCredentialsButKeepsShortIdentitySuffix(){
        assertThat(ConversationLog.redact("手机号13812345678 后四位0012 password=demo Bearer abc.def"))
                .doesNotContain("13812345678","=demo","abc.def").contains("0012","[REDACTED]");
    }
    @Test void recordsAssistantQuestionAndSummaryWithoutResultRows(){
        var logger=(Logger)LoggerFactory.getLogger("CONVERSATION");
        var appender=new ListAppender<ILoggingEvent>();appender.start();logger.addAppender(appender);
        try{
            var task=new QueryTaskEntity();task.setTaskId("task-test");
            task.setQuestionJson("{\"type\":\"CUSTOMER_IDENTITY\",\"prompt\":\"请选择身份类型\"}");
            task.setResultJson("{\"summary\":\"查询完成\",\"analysis\":{\"overview\":\"实际结果分析\"},\"rows\":[{\"private_data\":\"hidden\"}]}");
            new ConversationLog(JsonMapper.builder().build()).record(task,"ASSISTANT","阶段说明");
            String line=appender.list.get(0).getFormattedMessage();
            assertThat(line).contains("请选择身份类型","查询完成","实际结果分析").doesNotContain("private_data","hidden");
            assertThat(JsonMapper.builder().build().readTree(line).path("task_id").asText()).isEqualTo("task-test");
        }finally{logger.detachAppender(appender);appender.stop();}
    }
}
