package com.boc.nl2sql.service.quality;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 当前反馈投影的只读入口。
 * 会话查询通过该组件批量回填消息的当前反馈，不直接读取质量子系统的表。
 */
@Component
public class FeedbackQuery {
    private final NamedParameterJdbcTemplate jdbc;

    public FeedbackQuery(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * 查询指定用户对一批消息的当前反馈。
     *
     * @return messageId 到 LIKE、DISLIKE 或 NONE 的映射；输入为空时返回空 Map
     */
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
