package com.boc.nl2sql.access.auth.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 登录请求。密码字段必须是当前服务端公钥对应的加密信封，避免明文密码进入 JSON。 */
public record LoginRequest(
        @NotBlank @Size(max = 64) String username,
        @Valid EncryptedPasswordRequest password
) {
}
