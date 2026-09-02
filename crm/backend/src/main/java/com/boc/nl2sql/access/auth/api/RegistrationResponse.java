package com.boc.nl2sql.access.auth.api;

/** 不返回账号主键、密码或权限信息，避免误导用户认为账号已可使用。 */
public record RegistrationResponse(String username, String employeeNo, String accountStatus, String message) {
}
