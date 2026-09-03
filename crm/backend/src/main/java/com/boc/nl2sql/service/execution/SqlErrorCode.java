package com.boc.nl2sql.service.execution;

import com.boc.nl2sql.common.exception.BusinessException;

/** 校验拒绝的错误码与统一文案前缀：错误码契约被修复回路按码消费，不可随意变更。 */
enum SqlErrorCode {
    /** 语法、结构、白名单类拒绝。 */
    SQL_STRUCTURE_REJECTED(422101),
    /** LIMIT/OFFSET 类拒绝。 */
    SQL_PAGINATION_REJECTED(422102),
    /** 字段类拒绝。 */
    SQL_COLUMN_REJECTED(422104),
    /** 表对象类拒绝。 */
    SQL_TABLE_REJECTED(403102),
    /** 账号数据范围未配置。 */
    ACCOUNT_SCOPE_INVALID(403103),
    /** 未证明账号范围约束。 */
    SCOPE_NOT_PROVEN(403104),
    /** 未证明已确认客户约束。 */
    CUSTOMER_NOT_PROVEN(403105);

    private final int code;

    SqlErrorCode(int code) {
        this.code = code;
    }

    int code() {
        return code;
    }

    /** 按统一文案前缀抛出业务异常。 */
    void fail(String message) {
        throw new BusinessException(code, "SQL校验未通过：" + message);
    }
}
