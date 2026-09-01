package com.boc.nl2sql.authorization.domain;

/** 账号生命周期状态；待审批账号不具备登录资格，也不会形成 {@link CurrentUser}。 */
public enum AccountStatus {
    PENDING,
    ACTIVE
}
