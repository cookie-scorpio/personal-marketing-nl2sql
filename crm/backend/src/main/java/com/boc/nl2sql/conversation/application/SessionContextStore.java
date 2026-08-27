package com.boc.nl2sql.conversation.application;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis 保存短期会话索引；Redis 不可用时退回进程内缓存，避免本地开发被非核心依赖阻断。
 */
@Service
public class SessionContextStore {
    private final StringRedisTemplate redisTemplate;
    private final Map<String, String> localFallback = new ConcurrentHashMap<>();

    public SessionContextStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void rememberTask(Long userId, String sessionId, String taskId) {
        String key = key(userId, sessionId);
        localFallback.put(key, taskId);
        try {
            redisTemplate.opsForValue().set(key, taskId, Duration.ofMinutes(30));
        } catch (RuntimeException ignored) {
            // 降级仅用于开发环境；生产环境应通过监控暴露 Redis 故障。
        }
    }

    private String key(Long userId, String sessionId) {
        return "nl2sql:session:" + userId + ":" + sessionId + ":last-task";
    }
}
