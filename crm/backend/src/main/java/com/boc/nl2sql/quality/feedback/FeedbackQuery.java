package com.boc.nl2sql.quality.feedback;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** C 只通过此 F Interface 回显当前用户自己的反馈状态。 */
@Component
public class FeedbackQuery {
    private final NamedParameterJdbcTemplate jdbc;

    public FeedbackQuery(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<Long, String> currentForMessages(Long userId, Collection<Long> messageIds) {
        if (userId == null || messageIds == null || messageIds.isEmpty()) return Map.of();
        var parameters = new MapSqlParameterSource().addValue("userId", userId).addValue("messageIds", messageIds);
        var result = new HashMap<Long, String>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT message_id,feedback_code FROM quality_feedback_current
                 WHERE user_id=:userId AND message_id IN (:messageIds)
                """, parameters)) {
            result.put(((Number) row.get("message_id")).longValue(), (String) row.get("feedback_code"));
        }
        return result;
    }
}
