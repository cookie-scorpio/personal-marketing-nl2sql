package com.boc.nl2sql.service.authorization;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.boc.nl2sql.domain.authorization.AccountStatus;
import com.boc.nl2sql.domain.authorization.BusinessDataScopeLevel;
import com.boc.nl2sql.domain.authorization.CurrentUser;
import com.boc.nl2sql.domain.authorization.RoleCode;
import com.boc.nl2sql.domain.authorization.UserAccountEntity;
import com.boc.nl2sql.dao.authorization.UserAccountMapper;
import com.boc.nl2sql.domain.authorization.UserRoleGrantEntity;
import com.boc.nl2sql.dao.authorization.UserRoleGrantMapper;
import com.boc.nl2sql.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 集中处理账号的多身份授权。
 *
 * <p>账号本身保留机构、网点和客户经理字段，便于一次授权后由三个业务数据等级复用；角色表只记录
 * “可以扮演什么身份”及客户经理身份的范围等级。激活身份永远由本服务选择，浏览器不能自行伪造。</p>
 */
@Service
public class UserRoleGrantService {
    private static final List<RoleCode> ROLE_ORDER = List.of(
            RoleCode.CUSTOMER_MANAGER, RoleCode.QUALITY_AUDITOR, RoleCode.PERMISSION_ADMIN);

    private final UserAccountMapper accounts;
    private final UserRoleGrantMapper grants;

    public UserRoleGrantService(UserAccountMapper accounts, UserRoleGrantMapper grants) {
        this.accounts = accounts;
        this.grants = grants;
    }

    /** 登录或切换时，按服务端授权记录构造当前身份；requestedRole 为空时选稳定默认身份。 */
    public CurrentUser currentUser(UserAccountEntity account, RoleCode requestedRole) {
        List<UserRoleGrantEntity> active = activeGrants(account.getId());
        if (active.isEmpty()) {
            return legacyCurrentUser(account, requestedRole);
        }
        List<RoleCode> roles = active.stream().map(this::roleOf).distinct()
                .sorted(Comparator.comparingInt(ROLE_ORDER::indexOf)).toList();
        RoleCode target = requestedRole == null ? defaultRole(roles) : requestedRole.normalized();
        UserRoleGrantEntity activeGrant = active.stream()
                .filter(grant -> roleOf(grant) == target)
                .findFirst()
                .orElseThrow(() -> new BusinessException(403105, "当前账号未获授该身份"));
        return new CurrentUser(account.getId(), account.getUsername(), account.getDisplayName(), target,
                target == RoleCode.CUSTOMER_MANAGER ? scopeOf(activeGrant) : null,
                account.getRegionCode(), account.getBranchId(), account.getManagerId(), roles, account.getEmployeeNo());
    }

    /** 权限管理员读取账号清单时只得到授权所需的非敏感字段。 */
    public List<AccountOverview> listAccounts(CurrentUser administrator) {
        requirePermissionAdministrator(administrator);
        List<UserAccountEntity> rows = accounts.selectList(Wrappers.<UserAccountEntity>lambdaQuery()
                .orderByDesc(UserAccountEntity::getCreatedAt));
        List<UserRoleGrantEntity> grantRows = grants.selectList(Wrappers.<UserRoleGrantEntity>lambdaQuery()
                .orderByAsc(UserRoleGrantEntity::getCreatedAt));
        Map<Long, List<UserRoleGrantEntity>> grouped = new LinkedHashMap<>();
        for (UserRoleGrantEntity grant : grantRows) {
            if (Boolean.TRUE.equals(grant.getEnabled())) {
                grouped.computeIfAbsent(grant.getUserId(), ignored -> new ArrayList<>()).add(grant);
            }
        }
        return rows.stream().map(account -> new AccountOverview(
                account.getId(), account.getEmployeeNo(), account.getUsername(), account.getDisplayName(),
                account.getAccountStatus(), Boolean.TRUE.equals(account.getEnabled()),
                grouped.getOrDefault(account.getId(), List.of()).stream()
                        .map(grant -> new RoleOverview(roleOf(grant), scopeOfOrNull(grant))).toList(),
                account.getRegionCode(), account.getBranchId(), account.getManagerId())).toList();
    }

    /**
     * 覆盖一个账号的全部身份授权，同时激活已经具备完整业务范围的待审批账号。
     *
     * <p>覆盖写入可避免同一账号留下已经撤销但仍可切换的旧身份。权限管理员只能通过这个受控入口
     * 分配角色，公开注册接口不接受任何权限字段。</p>
     */
    @Transactional
    public AccountOverview assign(Long accountId, PermissionAssignment assignment, CurrentUser administrator) {
        requirePermissionAdministrator(administrator);
        UserAccountEntity account = accounts.selectById(accountId);
        if (account == null) throw new BusinessException(404002, "账号不存在");
        Set<RoleCode> roles = validateRoles(assignment.roles());
        BusinessDataScopeLevel level = roles.contains(RoleCode.CUSTOMER_MANAGER)
                ? requireScopeLevel(assignment.businessScopeLevel()) : null;
        String region = normalizeScope(assignment.regionCode(), "区域编码", roles.contains(RoleCode.CUSTOMER_MANAGER));
        String branch = normalizeScope(assignment.branchId(), "网点编码", level == BusinessDataScopeLevel.CUSTOMER_MANAGER || level == BusinessDataScopeLevel.TEAM_LEAD);
        String manager = normalizeScope(assignment.managerId(), "客户经理编号", level == BusinessDataScopeLevel.CUSTOMER_MANAGER);
        if (level == BusinessDataScopeLevel.ORG_MANAGER) {
            branch = null;
            manager = null;
        } else if (level == BusinessDataScopeLevel.TEAM_LEAD) {
            manager = null;
        } else if (!roles.contains(RoleCode.CUSTOMER_MANAGER)) {
            // 非业务身份没有客户数据范围，避免管理员误留下未来可被复用的旧范围。
            region = null;
            branch = null;
            manager = null;
        }

        grants.delete(Wrappers.<UserRoleGrantEntity>lambdaQuery()
                .eq(UserRoleGrantEntity::getUserId, accountId));
        LocalDateTime now = LocalDateTime.now();
        for (RoleCode role : ROLE_ORDER) {
            if (!roles.contains(role)) continue;
            UserRoleGrantEntity grant = new UserRoleGrantEntity();
            grant.setUserId(accountId);
            grant.setRoleCode(role.name());
            grant.setBusinessScopeLevel(role == RoleCode.CUSTOMER_MANAGER ? level.name() : null);
            grant.setGrantedByUserId(administrator.userId());
            grant.setEnabled(true);
            grant.setCreatedAt(now);
            grant.setUpdatedAt(now);
            grants.insert(grant);
        }
        account.setRegionCode(region);
        account.setBranchId(branch);
        account.setManagerId(manager);
        // role_code 保留为历史兼容字段，认证和切换不再依赖它决定可用身份。
        account.setRoleCode(legacyPrimaryRole(roles, level).name());
        account.setEnabled(true);
        account.setAccountStatus(AccountStatus.ACTIVE.name());
        accounts.updateById(account);
        return listAccounts(administrator).stream().filter(row -> row.userId().equals(accountId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("授权后的账号未找到"));
    }

    /** 为演示账号和升级后尚未写入角色表的历史账号补齐默认授权。 */
    @Transactional
    public void seedLegacyGrantIfMissing(UserAccountEntity account) {
        if (account == null || account.getId() == null || account.getRoleCode() == null
                || !Boolean.TRUE.equals(account.getEnabled())) return;
        if (!activeGrants(account.getId()).isEmpty()) return;
        RoleCode legacy;
        try {
            legacy = RoleCode.valueOf(account.getRoleCode());
        } catch (IllegalArgumentException ignored) {
            return;
        }
        RoleCode role = legacy.normalized();
        if (!role.assignable()) return;
        UserRoleGrantEntity grant = new UserRoleGrantEntity();
        grant.setUserId(account.getId());
        grant.setRoleCode(role.name());
        BusinessDataScopeLevel level = BusinessDataScopeLevel.fromLegacyRole(legacy);
        // 只有客户经理身份需要业务范围等级；审计与权限身份不拥有业务查询范围。
        grant.setBusinessScopeLevel(role == RoleCode.CUSTOMER_MANAGER
                ? (level == null ? BusinessDataScopeLevel.CUSTOMER_MANAGER.name() : level.name()) : null);
        grant.setEnabled(true);
        grant.setCreatedAt(LocalDateTime.now());
        grant.setUpdatedAt(LocalDateTime.now());
        grants.insert(grant);
    }

    /** 仅为本地演示账号补充一个可切换身份；业务授权统一仍走 {@link #assign}。 */
    @Transactional
    public void assignAdditionalDemoRole(Long userId, RoleCode role) {
        if (role == null || !role.normalized().assignable()) return;
        RoleCode normalized = role.normalized();
        if (grants.selectCount(Wrappers.<UserRoleGrantEntity>lambdaQuery()
                .eq(UserRoleGrantEntity::getUserId, userId)
                .eq(UserRoleGrantEntity::getRoleCode, normalized.name())) > 0) return;
        UserRoleGrantEntity grant = new UserRoleGrantEntity();
        grant.setUserId(userId);
        grant.setRoleCode(normalized.name());
        grant.setEnabled(true);
        grant.setCreatedAt(LocalDateTime.now());
        grant.setUpdatedAt(LocalDateTime.now());
        grants.insert(grant);
    }

    private List<UserRoleGrantEntity> activeGrants(Long userId) {
        return grants.selectList(Wrappers.<UserRoleGrantEntity>lambdaQuery()
                .eq(UserRoleGrantEntity::getUserId, userId)
                .eq(UserRoleGrantEntity::getEnabled, true));
    }

    private CurrentUser legacyCurrentUser(UserAccountEntity account, RoleCode requestedRole) {
        RoleCode old;
        try {
            old = RoleCode.valueOf(account.getRoleCode());
        } catch (Exception exception) {
            throw new BusinessException(403103, "当前账号尚未分配有效身份");
        }
        RoleCode role = old.normalized();
        if (!role.assignable() || requestedRole != null && requestedRole.normalized() != role) {
            throw new BusinessException(403105, "当前账号未获授该身份");
        }
        return new CurrentUser(account.getId(), account.getUsername(), account.getDisplayName(), role,
                BusinessDataScopeLevel.fromLegacyRole(old), account.getRegionCode(), account.getBranchId(),
                account.getManagerId(), List.of(role), account.getEmployeeNo());
    }

    private RoleCode roleOf(UserRoleGrantEntity grant) {
        try {
            RoleCode role = RoleCode.valueOf(grant.getRoleCode()).normalized();
            if (!role.assignable()) throw new IllegalArgumentException();
            return role;
        } catch (Exception exception) {
            throw new BusinessException(403103, "账号存在无效的角色授权");
        }
    }

    private BusinessDataScopeLevel scopeOf(UserRoleGrantEntity grant) {
        BusinessDataScopeLevel scope = scopeOfOrNull(grant);
        if (scope == null) throw new BusinessException(403103, "客户经理身份缺少业务数据范围等级");
        return scope;
    }

    private BusinessDataScopeLevel scopeOfOrNull(UserRoleGrantEntity grant) {
        if (grant.getBusinessScopeLevel() == null || grant.getBusinessScopeLevel().isBlank()) return null;
        try {
            return BusinessDataScopeLevel.valueOf(grant.getBusinessScopeLevel());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(403103, "账号存在无效的业务数据范围等级");
        }
    }

    private RoleCode defaultRole(List<RoleCode> roles) {
        return ROLE_ORDER.stream().filter(roles::contains).findFirst()
                .orElseThrow(() -> new BusinessException(403103, "当前账号尚未分配有效身份"));
    }

    private Set<RoleCode> validateRoles(List<RoleCode> values) {
        if (values == null || values.isEmpty()) throw new BusinessException(400016, "请至少授予一种身份");
        Set<RoleCode> roles = new LinkedHashSet<>();
        for (RoleCode value : values) {
            if (value == null || !value.normalized().assignable()) {
                throw new BusinessException(400016, "包含不支持的身份类型");
            }
            roles.add(value.normalized());
        }
        return roles;
    }

    private BusinessDataScopeLevel requireScopeLevel(BusinessDataScopeLevel level) {
        if (level == null) throw new BusinessException(400017, "客户经理身份必须选择业务数据范围等级");
        return level;
    }

    private String normalizeScope(String value, String label, boolean required) {
        String normalized = value == null ? null : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized == null || normalized.isBlank()) {
            if (required) throw new BusinessException(400018, label + "不能为空");
            return null;
        }
        if (!normalized.matches("[A-Z0-9_-]{1,32}")) {
            throw new BusinessException(400018, label + "格式不正确");
        }
        return normalized;
    }

    private RoleCode legacyPrimaryRole(Set<RoleCode> roles, BusinessDataScopeLevel level) {
        if (roles.contains(RoleCode.CUSTOMER_MANAGER)) return switch (Objects.requireNonNull(level)) {
            case CUSTOMER_MANAGER -> RoleCode.CUSTOMER_MANAGER;
            case TEAM_LEAD -> RoleCode.TEAM_LEAD;
            case ORG_MANAGER -> RoleCode.ORG_MANAGER;
        };
        return roles.contains(RoleCode.QUALITY_AUDITOR) ? RoleCode.QUALITY_AUDITOR : RoleCode.PERMISSION_ADMIN;
    }

    private void requirePermissionAdministrator(CurrentUser user) {
        if (user == null || user.role() == null || user.role().normalized() != RoleCode.PERMISSION_ADMIN) {
            throw new BusinessException(403107, "请切换至权限管理员身份后再操作");
        }
    }

    public record PermissionAssignment(List<RoleCode> roles, BusinessDataScopeLevel businessScopeLevel,
                                       String regionCode, String branchId, String managerId) {
    }

    public record RoleOverview(RoleCode role, BusinessDataScopeLevel businessScopeLevel) {
    }

    public record AccountOverview(Long userId, String employeeNo, String username, String displayName,
                                  String accountStatus, boolean enabled, List<RoleOverview> roles,
                                  String regionCode, String branchId, String managerId) {
    }
}
