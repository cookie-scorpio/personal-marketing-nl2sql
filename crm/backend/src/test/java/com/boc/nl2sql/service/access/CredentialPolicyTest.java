package com.boc.nl2sql.service.access;

import com.boc.nl2sql.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CredentialPolicyTest {
    private final UsernamePolicy usernames = new UsernamePolicy();
    private final PasswordPolicy passwords = new PasswordPolicy();

    @Test
    void normalizesExistingStyleUsername() {
        assertEquals("manager01", usernames.normalizeAndValidate(" manager01 "));
    }

    @Test
    void rejectsUsernameOutsideExistingPattern() {
        assertThrows(BusinessException.class, () -> usernames.normalizeAndValidate("1manager01"));
        assertThrows(BusinessException.class, () -> usernames.normalizeAndValidate("manager"));
        assertThrows(BusinessException.class, () -> usernames.normalizeAndValidate("manager-01"));
    }

    @Test
    void acceptsAndRejectsStrongPasswordAsSpecified() {
        assertDoesNotThrow(() -> passwords.validate("Valid@123"));
        assertThrows(BusinessException.class, () -> passwords.validate("weakpass"));
        assertThrows(BusinessException.class, () -> passwords.validate("Valid123"));
        assertThrows(BusinessException.class, () -> passwords.validate("Valid@1"));
    }
}
