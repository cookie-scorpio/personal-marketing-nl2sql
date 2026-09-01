package com.boc.nl2sql.quality.feedback;

import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.quality.collection.QualityFacts;
import com.boc.nl2sql.quality.event.QualityEventType;
import com.boc.nl2sql.quality.event.QualityFact;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** F 对反馈历史和当前反馈投影的唯一写入 Interface。 */
@Service
public class FeedbackApplication {
    private final JdbcTemplate jdbc;
    private final QualityFacts facts;

    public FeedbackApplication(JdbcTemplate jdbc, QualityFacts facts) {
        this.jdbc = jdbc;
        this.facts = facts;
    }

    @Transactional
    public FeedbackState record(FeedbackCommand command) {
        if (command.feedback() == null || !List.of("LIKE", "DISLIKE", "NONE").contains(command.feedback())) {
            throw new BusinessException(400001, "feedback只能为LIKE、DISLIKE或NONE");
        }
        String previous = jdbc.query("SELECT feedback_code FROM quality_feedback_current WHERE message_id=? FOR UPDATE",
                rs -> rs.next() ? rs.getString(1) : "NONE", command.messageId());
        jdbc.update("""
                INSERT INTO quality_feedback_current(
                    message_id,session_id,task_id,user_id,feedback_code,reason_code,comment,version,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,1,NOW(3),NOW(3))
                ON DUPLICATE KEY UPDATE feedback_code=VALUES(feedback_code),reason_code=VALUES(reason_code),
                    comment=VALUES(comment),version=version+1,updated_at=NOW(3)
                """, command.messageId(), command.sessionId(), command.taskId(), command.userId(), command.feedback(),
                command.reasonCode(), command.comment());

        facts.publish(QualityFact.builder(QualityEventType.FEEDBACK_CHANGED, "QUALITY")
                .requestId(command.requestId()).sessionId(command.sessionId()).taskId(command.taskId())
                .messageId(command.messageId()).userId(command.userId())
                .summary(previous + " -> " + command.feedback())
                .detail("previous_feedback", previous).detail("feedback", command.feedback())
                .detail("reason_code", command.reasonCode()).detail("comment", command.comment())
                .evaluationCandidate("DISLIKE".equals(command.feedback())).build());
        return new FeedbackState(command.messageId(), command.feedback(), command.reasonCode(), command.comment());
    }

    public record FeedbackCommand(String requestId, String sessionId, String taskId, long messageId, Long userId,
                                  String feedback, String reasonCode, String comment) { }
    public record FeedbackState(long messageId, String feedback, String reasonCode, String comment) {
        public Map<String, Object> response() { return Map.of("message_id", messageId, "feedback", feedback); }
    }
}
