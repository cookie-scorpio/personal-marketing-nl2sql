package com.boc.nl2sql.service.access;

import com.boc.nl2sql.controller.access.EncryptedPasswordRequest;
import com.boc.nl2sql.controller.access.RegistrationRequest;
import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.domain.authorization.UserAccountEntity;
import com.boc.nl2sql.dao.authorization.UserAccountMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegistrationServiceTest {
    @Test
    void createsDisabledPendingAccountWithoutRoleOrScope() {
        UserAccountMapper accounts = mock(UserAccountMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(accounts.selectCount(any())).thenReturn(0L);
        when(encoder.encode(anyString())).thenReturn("bcrypt-hash");
        PasswordCipher cipher = new PasswordCipher() {
            @Override public PublicKeyInfo publicKey() { return new PublicKeyInfo("key", "RSA-OAEP-256", "public"); }
            @Override public String decrypt(String keyId, String encryptedPassword) { return "Valid@123"; }
        };
        RegistrationService service = new RegistrationService(accounts, encoder, cipher, new PasswordPolicy(), new UsernamePolicy(), new EmployeeNoPolicy());

        var result = service.register(new RegistrationRequest("12345", " 林书言 ", "newuser01", new EncryptedPasswordRequest("key", "ciphertext")));

        ArgumentCaptor<UserAccountEntity> account = ArgumentCaptor.forClass(UserAccountEntity.class);
        verify(accounts).insert(account.capture());
        assertEquals("PENDING", result.accountStatus());
        assertEquals("newuser01", account.getValue().getUsername());
        assertEquals("12345", account.getValue().getEmployeeNo());
        assertEquals("林书言", account.getValue().getDisplayName());
        assertEquals("PENDING", account.getValue().getAccountStatus());
        assertEquals(false, account.getValue().getEnabled());
        assertEquals(null, account.getValue().getRoleCode());
        assertEquals(null, account.getValue().getRegionCode());
    }

    @Test
    void rejectsAnExistingUsernameWithAnExplicitCorrectionMessage() {
        UserAccountMapper accounts = mock(UserAccountMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        PasswordCipher cipher = mock(PasswordCipher.class);
        // 第一次唯一性查询检查用户名；命中后必须直接失败，不能继续插入账号。
        when(accounts.selectCount(any())).thenReturn(1L);
        RegistrationService service = new RegistrationService(accounts, encoder, cipher,
                new PasswordPolicy(), new UsernamePolicy(), new EmployeeNoPolicy());

        BusinessException rejected = assertThrows(BusinessException.class, () -> service.register(
                new RegistrationRequest("12345", "林书言", "manager01",
                        new EncryptedPasswordRequest("key", "ciphertext"))));

        assertEquals(409010, rejected.code());
        assertEquals("用户名已存在，请修改用户名后重新提交", rejected.getMessage());
    }

    @Test
    void rejectsBlankDisplayNameAsRequiredAccountInformation() {
        UserAccountMapper accounts = mock(UserAccountMapper.class);
        RegistrationService service = new RegistrationService(accounts, mock(PasswordEncoder.class),
                mock(PasswordCipher.class), new PasswordPolicy(), new UsernamePolicy(), new EmployeeNoPolicy());

        BusinessException rejected = assertThrows(BusinessException.class, () -> service.register(
                new RegistrationRequest("12345", "   ", "newuser01",
                        new EncryptedPasswordRequest("key", "ciphertext"))));

        assertEquals(400019, rejected.code());
        assertEquals("姓名不能为空、不能包含控制字符，且不能超过64个字符", rejected.getMessage());
    }

    @Test
    void reportsTheUsernameWhenConcurrentInsertHitsTheDatabaseUniqueIndex() {
        UserAccountMapper accounts = mock(UserAccountMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(accounts.selectCount(any())).thenReturn(0L, 0L, 1L);
        when(accounts.insert(any(UserAccountEntity.class))).thenThrow(new DuplicateKeyException("duplicate username"));
        when(encoder.encode(anyString())).thenReturn("bcrypt-hash");
        PasswordCipher cipher = new PasswordCipher() {
            @Override public PublicKeyInfo publicKey() { return new PublicKeyInfo("key", "RSA-OAEP-256", "public"); }
            @Override public String decrypt(String keyId, String encryptedPassword) { return "Valid@123"; }
        };
        RegistrationService service = new RegistrationService(accounts, encoder, cipher,
                new PasswordPolicy(), new UsernamePolicy(), new EmployeeNoPolicy());

        BusinessException rejected = assertThrows(BusinessException.class, () -> service.register(
                new RegistrationRequest("12345", "林书言", "newuser01",
                        new EncryptedPasswordRequest("key", "ciphertext"))));

        assertEquals(409010, rejected.code());
        assertEquals("用户名已存在，请修改用户名后重新提交", rejected.getMessage());
    }
}
