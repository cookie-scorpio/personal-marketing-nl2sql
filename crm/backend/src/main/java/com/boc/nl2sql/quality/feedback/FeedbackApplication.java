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

/**
 * F 对反馈历史和当前反馈投影的唯一写入服务。
 *
 * <p>会话服务负责校验所有者、助手消息和可评价状态，验证通过后把反馈命令提交给本服务。
 * 本服务负责反馈枚举、当前状态版本和不可变变化事实。</p>
 */
@Service
public class FeedbackApplication {
    private final JdbcTemplate jdbc;
    private final QualityFacts facts;

    public FeedbackApplication(JdbcTemplate jdbc, QualityFacts facts) {
        this.jdbc = jdbc;
        this.facts = facts;
    }

    /**
     * 原子更新一条消息的当前反馈，并追加 FEEDBACK_CHANGED 事实。
     *
     * <p>先锁定旧状态，再使用 upsert 更新反馈和值版本。事实由 {@link QualityFacts} 在本事务
     * 成功提交后异步派发，因此投影回滚时不会留下错误的变化历史。</p>
     *
     * @throws BusinessException feedback 不是 LIKE、DISLIKE 或 NONE 时抛出
     */
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

    /** 会话服务提交的反馈命令；reasonCode 和 comment 为可选补充信息。 */
    public record FeedbackCommand(String requestId, String sessionId, String taskId, long messageId, Long userId,
                                  String feedback, String reasonCode, String comment) { }
    /** F 更新后的反馈状态。 */
    public record FeedbackState(long messageId, String feedback, String reasonCode, String comment) {
        /** 转换成当前普通用户接口兼容的最小响应，原因和备注暂不回显。 */
        public Map<String, Object> response() { return Map.of("message_id", messageId, "feedback", feedback); }
    }
}
