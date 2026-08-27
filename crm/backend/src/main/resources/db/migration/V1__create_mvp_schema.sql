CREATE TABLE IF NOT EXISTS sys_user_account (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    role_code VARCHAR(32) NOT NULL,
    region_code VARCHAR(32) NOT NULL,
    branch_id VARCHAR(32),
    manager_id VARCHAR(32),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sys_user_account_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='MVP本地登录账号';

CREATE TABLE IF NOT EXISTS dim_customer_manager (
    manager_id VARCHAR(32) PRIMARY KEY,
    manager_name VARCHAR(64) NOT NULL,
    branch_id VARCHAR(32) NOT NULL,
    region_code VARCHAR(32) NOT NULL,
    status_code VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户经理维表';

CREATE TABLE IF NOT EXISTS dim_customer (
    customer_id VARCHAR(32) PRIMARY KEY,
    customer_name_masked VARCHAR(64) NOT NULL,
    gender_code CHAR(1) NOT NULL,
    age SMALLINT NOT NULL,
    age_band_code VARCHAR(16) NOT NULL,
    mobile_masked VARCHAR(32) NOT NULL,
    customer_level_code VARCHAR(20) NOT NULL,
    vip_flag BOOLEAN NOT NULL,
    risk_level_code VARCHAR(8) NOT NULL,
    occupation_code VARCHAR(32) NOT NULL,
    region_code VARCHAR(32) NOT NULL,
    branch_id VARCHAR(32) NOT NULL,
    manager_id VARCHAR(32) NOT NULL,
    total_asset_amount DECIMAL(20,2) NOT NULL,
    asset_change_3m_rate DECIMAL(10,4) NOT NULL,
    open_date DATE NOT NULL,
    status_code VARCHAR(16) NOT NULL,
    snapshot_date DATE NOT NULL,
    KEY idx_customer_manager (manager_id),
    KEY idx_customer_branch (branch_id),
    KEY idx_customer_region_level (region_code, customer_level_code),
    KEY idx_customer_asset (total_asset_amount)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户画像快照';

CREATE TABLE IF NOT EXISTS fct_transaction (
    transaction_id VARCHAR(40) PRIMARY KEY,
    customer_id VARCHAR(32) NOT NULL,
    product_id VARCHAR(32),
    transaction_time DATETIME NOT NULL,
    transaction_date DATE NOT NULL,
    transaction_type_code VARCHAR(24) NOT NULL,
    debit_credit_flag CHAR(1) NOT NULL,
    currency_code CHAR(3) NOT NULL DEFAULT 'CNY',
    amount_cny DECIMAL(20,2) NOT NULL,
    branch_id VARCHAR(32) NOT NULL,
    status_code VARCHAR(16) NOT NULL,
    KEY idx_transaction_customer_date (customer_id, transaction_date),
    KEY idx_transaction_date_type (transaction_date, transaction_type_code),
    KEY idx_transaction_branch_date (branch_id, transaction_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户交易事实表';

CREATE TABLE IF NOT EXISTS dim_marketing_campaign (
    campaign_id VARCHAR(32) PRIMARY KEY,
    campaign_name VARCHAR(128) NOT NULL,
    campaign_type_code VARCHAR(24) NOT NULL,
    campaign_status_code VARCHAR(20) NOT NULL,
    product_id VARCHAR(32),
    target_customer_segment_code VARCHAR(32),
    channel_code VARCHAR(16) NOT NULL,
    owner_org_id VARCHAR(32) NOT NULL,
    owner_manager_id VARCHAR(32),
    start_time DATETIME NOT NULL,
    end_time DATETIME NOT NULL,
    budget_amount DECIMAL(20,2),
    target_count BIGINT,
    KEY idx_campaign_time (start_time, end_time),
    KEY idx_campaign_owner (owner_org_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='营销活动维表';

CREATE TABLE IF NOT EXISTS fct_product_holding (
    holding_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    customer_id VARCHAR(32) NOT NULL,
    product_id VARCHAR(32) NOT NULL,
    product_name VARCHAR(128) NOT NULL,
    product_category_code VARCHAR(24) NOT NULL,
    holding_amount DECIMAL(20,2) NOT NULL,
    market_value_amount DECIMAL(20,2) NOT NULL,
    profit_amount DECIMAL(20,2) NOT NULL,
    maturity_date DATE,
    risk_level_code VARCHAR(8) NOT NULL,
    snapshot_date DATE NOT NULL,
    UNIQUE KEY uk_holding_customer_product_snapshot (customer_id, product_id, snapshot_date),
    KEY idx_holding_category (product_category_code),
    KEY idx_holding_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户产品持有快照';

CREATE TABLE IF NOT EXISTS fct_customer_marketing (
    relation_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    campaign_id VARCHAR(32) NOT NULL,
    customer_id VARCHAR(32) NOT NULL,
    contact_time DATETIME,
    contact_channel_code VARCHAR(16),
    response_flag BOOLEAN NOT NULL,
    conversion_flag BOOLEAN NOT NULL,
    conversion_amount DECIMAL(20,2) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_customer_campaign (campaign_id, customer_id),
    KEY idx_marketing_customer (customer_id),
    KEY idx_marketing_campaign_conversion (campaign_id, conversion_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户营销触达与转化事实表';

CREATE TABLE IF NOT EXISTS business_term (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    term_code VARCHAR(64) NOT NULL,
    standard_name VARCHAR(128) NOT NULL,
    synonyms VARCHAR(512) NOT NULL,
    definition_text VARCHAR(1000) NOT NULL,
    mapped_object VARCHAR(128) NOT NULL,
    version_no VARCHAR(20) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE KEY uk_business_term_code_version (term_code, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='业务术语与技术语义映射';

CREATE TABLE IF NOT EXISTS query_task (
    task_id VARCHAR(36) PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    query_text VARCHAR(1000) NOT NULL,
    merged_query_text VARCHAR(2000) NOT NULL,
    status_code VARCHAR(32) NOT NULL,
    progress INT NOT NULL,
    stage_message VARCHAR(255) NOT NULL,
    intent_code VARCHAR(32),
    clarification_round INT NOT NULL DEFAULT 0,
    question_json JSON,
    confirmation_token VARCHAR(80),
    confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    sql_text TEXT,
    sql_parameters_json JSON,
    result_json JSON,
    error_message VARCHAR(500),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_query_task_user_created (user_id, created_at),
    KEY idx_query_task_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='NL2SQL异步查询任务';

CREATE TABLE IF NOT EXISTS query_history (
    history_id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    query_text VARCHAR(1000) NOT NULL,
    intent_code VARCHAR(32),
    status_code VARCHAR(32) NOT NULL,
    sql_summary VARCHAR(500),
    result_summary VARCHAR(500),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    KEY idx_query_history_user_created (user_id, created_at),
    KEY idx_query_history_keyword (query_text(128))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户可见查询历史';

CREATE TABLE IF NOT EXISTS audit_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id VARCHAR(64),
    task_id VARCHAR(36),
    user_id BIGINT,
    event_type VARCHAR(40) NOT NULL,
    event_summary VARCHAR(500) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_audit_task_created (task_id, created_at),
    KEY idx_audit_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可由普通历史删除操作清理的审计事件';
