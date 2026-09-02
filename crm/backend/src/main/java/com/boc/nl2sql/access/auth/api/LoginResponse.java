package com.boc.nl2sql.access.auth.api;

import com.boc.nl2sql.authorization.domain.CurrentUser;

/** 登录成功响应；令牌与可信用户摘要一起返回，过期时间以秒为单位。 */
public record LoginResponse(String accessToken, String tokenType, long expiresIn, CurrentUser user) {
}
