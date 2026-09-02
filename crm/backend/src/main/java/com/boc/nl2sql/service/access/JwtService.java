package com.boc.nl2sql.service.access;

import com.boc.nl2sql.domain.authorization.CurrentUser;
import com.boc.nl2sql.domain.authorization.BusinessDataScopeLevel;
import com.boc.nl2sql.domain.authorization.RoleCode;
import com.boc.nl2sql.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 使用 HMAC-SHA256 签发本地 JWT。
 *
 * <p>MVP 不依赖外部认证中心，但仍校验签名和过期时间；后期可整体替换为 OAuth2 Resource Server。</p>
 */
@Service
public class JwtService {
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private final ObjectMapper objectMapper;
    private final byte[] secret;
    private final long ttlSeconds;

    public JwtService(ObjectMapper objectMapper,
                      @Value("${app.security.jwt-secret}") String secret,
                      @Value("${app.security.jwt-ttl-seconds}") long ttlSeconds) {
        // 密钥缺失或仍为仓库历史默认值时拒绝启动，防止用公开默认密钥伪造任意身份的令牌。
        if (secret == null || secret.isBlank() || "nl2sql-local-development-secret-change-before-sharing".equals(secret)) {
            throw new IllegalStateException("缺少安全的 JWT 签名密钥：请通过环境变量 JWT_SECRET 设置（至少32个随机字符）后再启动。");
        }
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
    }

    public String issue(CurrentUser user) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", user.userId());
        claims.put("username", user.username());
        claims.put("displayName", user.displayName());
        claims.put("role", user.role().name());
        claims.put("businessScopeLevel", user.businessScopeLevel() == null ? null : user.businessScopeLevel().name());
        claims.put("regionCode", user.regionCode());
        claims.put("branchId", user.branchId());
        claims.put("managerId", user.managerId());
        claims.put("availableRoles", user.availableRoles().stream().map(RoleCode::name).toList());
        claims.put("employeeNo", user.employeeNo());
        claims.put("iat", Instant.now().getEpochSecond());
        claims.put("exp", Instant.now().plusSeconds(ttlSeconds).getEpochSecond());
        try {
            String header = encodeJson(Map.of("alg", "HS256", "typ", "JWT"));
            String payload = encodeJson(claims);
            return header + "." + payload + "." + sign(header + "." + payload);
        } catch (JacksonException exception) {
            throw new IllegalStateException("JWT serialization failed", exception);
        }
    }

    public CurrentUser verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw invalidToken();
            }
            byte[] actual = URL_DECODER.decode(parts[2]);
            byte[] expected = URL_DECODER.decode(sign(parts[0] + "." + parts[1]));
            if (!MessageDigest.isEqual(actual, expected)) {
                throw invalidToken();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> claims = objectMapper.readValue(URL_DECODER.decode(parts[1]), Map.class);
            long expiresAt = ((Number) claims.get("exp")).longValue();
            if (Instant.now().getEpochSecond() >= expiresAt) {
                throw new BusinessException(401001, "登录状态已过期，请重新登录");
            }
            Object rawAvailable = claims.get("availableRoles");
            java.util.List<RoleCode> availableRoles = rawAvailable instanceof java.util.List<?> values
                    ? values.stream().map(String::valueOf).map(RoleCode::valueOf).toList()
                    : java.util.List.of(RoleCode.valueOf((String) claims.get("role")).normalized());
            RoleCode tokenRole = RoleCode.valueOf((String) claims.get("role"));
            String rawScope = (String) claims.get("businessScopeLevel");
            // 兼容改造前仅在 role 中保存数据范围的 JWT，避免已登录的团队/机构负责人被错误降为客户经理范围。
            BusinessDataScopeLevel scope = rawScope == null
                    ? BusinessDataScopeLevel.fromLegacyRole(tokenRole)
                    : BusinessDataScopeLevel.valueOf(rawScope);
            return new CurrentUser(
                    ((Number) claims.get("sub")).longValue(),
                    (String) claims.get("username"),
                    (String) claims.get("displayName"),
                    tokenRole,
                    scope,
                    (String) claims.get("regionCode"),
                    (String) claims.get("branchId"),
                    (String) claims.get("managerId"),
                    availableRoles,
                    (String) claims.get("employeeNo"));
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidToken();
        }
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    private String encodeJson(Object value) throws JacksonException {
        return URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return URL_ENCODER.encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("JWT signing failed", exception);
        }
    }

    private BusinessException invalidToken() {
        return new BusinessException(401001, "登录凭证无效，请重新登录");
    }
}
