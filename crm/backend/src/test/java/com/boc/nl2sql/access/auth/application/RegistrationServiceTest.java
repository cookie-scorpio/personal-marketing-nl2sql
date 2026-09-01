package com.boc.nl2sql.access.auth.application;

import com.boc.nl2sql.access.auth.api.EncryptedPasswordRequest;
import com.boc.nl2sql.access.auth.api.RegistrationRequest;
import com.boc.nl2sql.authorization.infrastructure.UserAccountEntity;
import com.boc.nl2sql.authorization.infrastructure.UserAccountMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        RegistrationService service = new RegistrationService(accounts, encoder, cipher, new PasswordPolicy(), new UsernamePolicy());

        var result = service.register(new RegistrationRequest("张三", "newuser01", new EncryptedPasswordRequest("key", "ciphertext")));

        ArgumentCaptor<UserAccountEntity> account = ArgumentCaptor.forClass(UserAccountEntity.class);
        verify(accounts).insert(account.capture());
        assertEquals("PENDING", result.accountStatus());
        assertEquals("newuser01", account.getValue().getUsername());
        assertEquals("PENDING", account.getValue().getAccountStatus());
        assertEquals(false, account.getValue().getEnabled());
        assertEquals(null, account.getValue().getRoleCode());
        assertEquals(null, account.getValue().getRegionCode());
    }
}
