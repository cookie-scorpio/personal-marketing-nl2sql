package com.boc.nl2sql.domain.authorization;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 一条由权限管理员授予的身份记录；一个账号可以拥有多条记录。 */
@TableName("sys_user_role_grant")
public class UserRoleGrantEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String roleCode;
    private String businessScopeLevel;
    private Long grantedByUserId;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getRoleCode() { return roleCode; }
    public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
    public String getBusinessScopeLevel() { return businessScopeLevel; }
    public void setBusinessScopeLevel(String businessScopeLevel) { this.businessScopeLevel = businessScopeLevel; }
    public Long getGrantedByUserId() { return grantedByUserId; }
    public void setGrantedByUserId(Long grantedByUserId) { this.grantedByUserId = grantedByUserId; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
