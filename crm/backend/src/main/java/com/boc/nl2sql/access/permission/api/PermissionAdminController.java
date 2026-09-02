package com.boc.nl2sql.access.permission.api;

import com.boc.nl2sql.authorization.application.UserRoleGrantService;
import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.common.api.ApiResponse;
import com.boc.nl2sql.common.web.WebRequestSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 权限管理员专用接口：公开注册账号只能由此入口获得身份和业务数据范围。 */
@RestController
@RequestMapping("/api/v1/permission-admin")
public class PermissionAdminController {
    private final UserRoleGrantService grants;

    public PermissionAdminController(UserRoleGrantService grants) {
        this.grants = grants;
    }

    @GetMapping("/accounts")
    public ApiResponse<List<UserRoleGrantService.AccountOverview>> accounts(
            @AuthenticationPrincipal CurrentUser user, HttpServletRequest request) {
        return ApiResponse.success(grants.listAccounts(user), WebRequestSupport.requestId(request));
    }

    @PutMapping("/accounts/{accountId}/permissions")
    public ApiResponse<UserRoleGrantService.AccountOverview> assign(
            @PathVariable Long accountId, @Valid @RequestBody PermissionAssignmentRequest body,
            @AuthenticationPrincipal CurrentUser user, HttpServletRequest request) {
        var command = new UserRoleGrantService.PermissionAssignment(body.roles(), body.businessScopeLevel(),
                body.regionCode(), body.branchId(), body.managerId());
        return ApiResponse.success(grants.assign(accountId, command, user), WebRequestSupport.requestId(request));
    }
}
