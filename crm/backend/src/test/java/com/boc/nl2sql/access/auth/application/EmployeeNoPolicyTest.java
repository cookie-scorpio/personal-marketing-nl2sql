package com.boc.nl2sql.access.auth.application;

import com.boc.nl2sql.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmployeeNoPolicyTest {
    private final EmployeeNoPolicy policy = new EmployeeNoPolicy();

    @Test
    void acceptsOnlyFiveArabicDigits() {
        assertEquals("01234", policy.normalizeAndValidate(" 01234 "));
        assertThrows(BusinessException.class, () -> policy.normalizeAndValidate("1234"));
        assertThrows(BusinessException.class, () -> policy.normalizeAndValidate("12A45"));
    }
}
