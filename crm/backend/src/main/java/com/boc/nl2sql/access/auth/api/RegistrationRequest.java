package com.boc.nl2sql.access.auth.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 公开注册仅创建待审批账号，不接收角色、机构或数据范围字段。 */
public record RegistrationRequest(
        @NotBlank @Size(max = 64) String displayName,
        @NotBlank @Size(max = 64) String username,
        @Valid EncryptedPasswordRequest password
) {
}
