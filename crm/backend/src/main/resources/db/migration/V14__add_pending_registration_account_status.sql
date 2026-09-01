ALTER TABLE sys_user_account
    MODIFY COLUMN role_code VARCHAR(32) NULL,
    MODIFY COLUMN region_code VARCHAR(32) NULL,
    ADD COLUMN account_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' AFTER enabled,
    ADD KEY idx_sys_user_account_status (account_status);
