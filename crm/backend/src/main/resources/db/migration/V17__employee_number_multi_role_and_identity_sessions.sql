-- 已发布迁移不可回写：本次新增工号、多角色授权与按当前身份隔离的会话列。
ALTER TABLE sys_user_account
    ADD COLUMN employee_no CHAR(5) NULL AFTER username,
    ADD UNIQUE KEY uk_sys_user_account_employee_no (employee_no);

-- 已有演示账号补入稳定工号；其他历史待审批账号保留空工号，不影响其既有数据。
UPDATE sys_user_account
SET employee_no = CASE username
    WHEN 'manager01' THEN '10001'
    WHEN 'leader01' THEN '10002'
    WHEN 'director01' THEN '10003'
    WHEN 'quality01' THEN '10004'
    WHEN 'admin01' THEN '10005'
    ELSE employee_no
END
WHERE employee_no IS NULL;

CREATE TABLE sys_user_role_grant (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    role_code VARCHAR(32) NOT NULL,
    business_scope_level VARCHAR(32) NULL,
    granted_by_user_id BIGINT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_user_role_grant (user_id, role_code),
    KEY idx_user_role_grant_user_enabled (user_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号多角色授权，由权限管理员维护';

-- 原三级业务角色统一迁移为“客户经理身份 + 业务范围等级”。
INSERT INTO sys_user_role_grant(user_id, role_code, business_scope_level, enabled, created_at, updated_at)
SELECT id, 'CUSTOMER_MANAGER', role_code, TRUE, NOW(3), NOW(3)
FROM sys_user_account
WHERE role_code IN ('CUSTOMER_MANAGER', 'TEAM_LEAD', 'ORG_MANAGER')
  AND NOT EXISTS (
      SELECT 1 FROM sys_user_role_grant grant_row
      WHERE grant_row.user_id = sys_user_account.id AND grant_row.role_code = 'CUSTOMER_MANAGER'
  );

-- 旧质量管理员名称仅作为兼容输入，升级后统一为质量审计员身份。
INSERT INTO sys_user_role_grant(user_id, role_code, enabled, created_at, updated_at)
SELECT id, 'QUALITY_AUDITOR', TRUE, NOW(3), NOW(3)
FROM sys_user_account
WHERE role_code = 'QUALITY_ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_user_role_grant grant_row
      WHERE grant_row.user_id = sys_user_account.id AND grant_row.role_code = 'QUALITY_AUDITOR'
  );

ALTER TABLE conversation_session
    ADD COLUMN identity_role_code VARCHAR(32) NULL AFTER user_id;

-- 原会话归入其原业务身份；质量会话归入质量审计身份，避免升级后跨身份可见。
UPDATE conversation_session session_row
LEFT JOIN sys_user_account account_row ON account_row.id = session_row.user_id
SET session_row.identity_role_code = CASE
    WHEN account_row.role_code = 'QUALITY_ADMIN' THEN 'QUALITY_AUDITOR'
    ELSE 'CUSTOMER_MANAGER'
END
WHERE session_row.identity_role_code IS NULL;

ALTER TABLE conversation_session
    MODIFY COLUMN identity_role_code VARCHAR(32) NOT NULL DEFAULT 'CUSTOMER_MANAGER',
    ADD KEY idx_session_identity_visible (user_id, identity_role_code, deleted_at, updated_at);
