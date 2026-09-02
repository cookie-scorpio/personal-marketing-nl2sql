package com.boc.nl2sql.authorization.application;

import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 统一的服务端授权入口。
 * 认证过滤器负责构造可信身份；业务模块通过本类完成资源归属和数据范围判断，避免散落的角色判断相互漂移。
 */
@Component
public class AuthorizationCenter {
    private final DataScopePolicy dataScopePolicy;

    public AuthorizationCenter(DataScopePolicy dataScopePolicy) {
        this.dataScopePolicy = dataScopePolicy;
    }

    public CurrentUser requireAuthenticated(CurrentUser user) {
        if (user == null || user.userId() == null || user.role() == null) {
            throw new BusinessException(401001, "请先登录后再访问");
        }
        return user;
    }

    /** 业务问数、客户检索等入口只接受客户经理或质量审计身份，不能只依赖前端隐藏按钮。 */
    public CurrentUser requireBusinessDataAccess(CurrentUser user) {
        requireAuthenticated(user);
        DataScopePolicy.scopeOf(user);
        return user;
    }

    /** 对外统一返回“资源不存在”，不向其他登录用户泄露资源归属。 */
    public void requireOwner(CurrentUser user, Long ownerId, String missingMessage) {
        requireAuthenticated(user);
        if (ownerId == null || !ownerId.equals(user.userId())) {
            throw new BusinessException(404001, missingMessage);
        }
    }

    public String customerCondition(String customerAlias, CurrentUser user, Map<String, Object> parameters) {
        requireAuthenticated(user);
        return dataScopePolicy.condition(customerAlias, user, parameters);
    }

    public DataScopePolicy.Scope customerScope(CurrentUser user) {
        requireAuthenticated(user);
        return DataScopePolicy.scopeOf(user);
    }
}
