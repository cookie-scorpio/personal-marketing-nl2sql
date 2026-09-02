package com.boc.nl2sql.controller.access;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 公开注册仅创建待审批账号，不接收角色、机构或数据范围字段。 */
public record RegistrationRequest(
        @NotBlank @Pattern(regexp = "^[0-9]{5}$") String employeeNo,
        @NotBlank @Size(max = 64) String username,
        @Valid EncryptedPasswordRequest password
) {
}
