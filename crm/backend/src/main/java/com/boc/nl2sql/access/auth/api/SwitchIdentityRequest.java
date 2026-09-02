package com.boc.nl2sql.access.auth.api;

import com.boc.nl2sql.authorization.domain.RoleCode;
import jakarta.validation.constraints.NotNull;

/** 切换当前身份只接收目标身份代码，数据范围始终由后端授权记录推导。 */
public record SwitchIdentityRequest(@NotNull RoleCode role) {
}
