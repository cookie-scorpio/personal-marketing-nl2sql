package com.boc.nl2sql.controller.access;

import com.boc.nl2sql.service.authorization.UserRoleGrantService;
import com.boc.nl2sql.domain.authorization.CurrentUser;
import com.boc.nl2sql.common.api.ApiResponse;
import com.boc.nl2sql.common.web.WebRequestSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    /**
     * 删除非当前登录账号。返回被删除的账号编号，便于客户端只移除对应行而不必猜测操作结果。
     * 自删保护由服务层再次执行，不能依赖页面按钮的禁用状态。
     */
    @DeleteMapping("/accounts/{accountId}")
    public ApiResponse<Long> deleteAccount(
            @PathVariable Long accountId, @AuthenticationPrincipal CurrentUser user, HttpServletRequest request) {
        grants.deleteAccount(accountId, user);
        return ApiResponse.success(accountId, WebRequestSupport.requestId(request));
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
