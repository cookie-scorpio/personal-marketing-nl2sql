package com.boc.nl2sql.access.auth.api;

import com.boc.nl2sql.authorization.domain.CurrentUser;

public record LoginResponse(String accessToken, String tokenType, long expiresIn, CurrentUser user) {
}
