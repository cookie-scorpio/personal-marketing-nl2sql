package com.boc.nl2sql.quality;

import com.boc.nl2sql.quality.collection.ModelCallRecorder;
import com.boc.nl2sql.quality.collection.QualityFacts;
import com.boc.nl2sql.quality.collection.SqlFactRecorder;
import com.boc.nl2sql.quality.event.QualityEvent;
import com.boc.nl2sql.quality.event.QualityEventType;
import com.boc.nl2sql.quality.event.QualityFact;
import com.boc.nl2sql.quality.feedback.FeedbackApplication;
import com.boc.nl2sql.quality.persistence.QualityEventRepository;
import com.boc.nl2sql.quality.query.QualityTimelineQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "app.model.provider=mock")
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "v11.mysql", matches = "true")
class QualityFactsMysqlTest {
    @Autowired QualityFacts facts;
    @Autowired QualityTimelineQuery timeline;
    @Autowired FeedbackApplication feedback;
    @Autowired ModelCallRecorder modelCalls;
    @Autowired SqlFactRecorder sqlFacts;
    @Autowired QualityEventRepository repository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void completeTaskModelSqlAndFeedbackFactsArePersistedAndQueryable() throws Exception {
        String taskId=UUID.randomUUID().toString(),sessionId=UUID.randomUUID().toString(),requestId="quality-mysql-test";
        long messageId=Math.abs(UUID.randomUUID().getMostSignificantBits());
        facts.publish(QualityFact.builder(QualityEventType.QUERY_RECEIVED,"CONVERSATION")
                .requestId(requestId).sessionId(sessionId).taskId(taskId).userId(3L).summary("完整任务")
                .detail("query_text","查询手机号13812345678的完整结果")
                .detail("result",Map.of("rows",List.of(Map.of("customer_id","C00000001","amount",12345)))).build());
        org.slf4j.MDC.put("taskId",taskId);org.slf4j.MDC.put("requestId",requestId);
        try {
            modelCalls.completed(UUID.randomUUID().toString(),"INTERPRET","mock","mock-model",3L,1,1,
                    Map.of("messages",List.of(Map.of("role","user","content","完整模型请求"))),
                    Map.of("choices",List.of(Map.of("message",Map.of("content","完整模型响应"))),
                            "usage",Map.of("prompt_tokens",10,"completion_tokens",20)),TimeUnit.MILLISECONDS.toNanos(12));
        } finally {org.slf4j.MDC.remove("taskId");org.slf4j.MDC.remove("requestId");}
        sqlFacts.record(taskId,requestId,3L,"MODEL","EXECUTED","SELECT 12345 AS amount",Map.of("p",12345),"rows=1");
        feedback.record(new FeedbackApplication.FeedbackCommand(requestId,sessionId,taskId,messageId,3L,
                "DISLIKE","SQL_INCORRECT","结果不正确"));

        List<Map<String,Object>> events=awaitEvents(taskId,4);
        assertThat(events).extracting(row->row.get("event_type")).contains(
                "QUERY_RECEIVED","MODEL_CALL_COMPLETED","SQL_ATTEMPT_RECORDED","FEEDBACK_CHANGED");
        assertThat(events.toString()).contains("13812345678","完整模型请求","完整模型响应","SELECT 12345 AS amount","结果不正确");
        assertThat(jdbc.queryForObject("SELECT feedback_code FROM quality_feedback_current WHERE message_id=?",
                String.class,messageId)).isEqualTo("DISLIKE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE task_id=? AND evaluation_candidate=TRUE",
                Integer.class,taskId)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void replayingTheSameEventIdIsIdempotent() {
        String eventId=UUID.randomUUID().toString(),taskId=UUID.randomUUID().toString();
        var event=new QualityEvent(eventId,1,QualityEventType.QUERY_RECEIVED.name(),"CONVERSATION",
                "ONLINE","replay-test",null,taskId,null,3L,null,null,null,false,
                "replayed event",LocalDateTime.now(),Map.of("query_text","完整问题"));

        repository.save(event);
        repository.save(event);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE event_id=?",Integer.class,eventId))
                .isEqualTo(1);
    }

    private List<Map<String,Object>> awaitEvents(String taskId,int minimum)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        List<Map<String,Object>> events=List.of();
        while(System.nanoTime()<deadline){
            events=timeline.taskTimeline(taskId,0,100);
            if(events.size()>=minimum)return events;
            Thread.sleep(25);
        }
        return events;
    }
}
