package com.boc.nl2sql.controller.access;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 浏览器 Web Crypto 加密后的密码信封。 */
public record EncryptedPasswordRequest(
        @NotBlank @Size(max = 64) String keyId,
        @NotBlank @Size(max = 8192) String encryptedPassword
) {
}
