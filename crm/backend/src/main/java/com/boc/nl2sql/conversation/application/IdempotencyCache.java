package com.boc.nl2sql.conversation.application;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/** Redis仅加速定位；数据库唯一约束和请求摘要是跨实例幂等的最终依据。 */
@Component
public class IdempotencyCache {
    private final StringRedisTemplate redis;
    public IdempotencyCache(StringRedisTemplate redis){this.redis=redis;}
    public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public String get(long user,String key){try{return redis.opsForValue().get("nl2sql:submit:{"+user+"}:"+hash(key));}catch(RuntimeException unavailable){return null;}}
    public void put(long user,String key,String task){try{redis.opsForValue().set("nl2sql:submit:{"+user+"}:"+hash(key),task,Duration.ofHours(24));}catch(RuntimeException ignored){/* MySQL仍保证幂等，禁止退回进程锁。 */}}
}
