package com.boc.nl2sql.access.auth.application;

import com.boc.nl2sql.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RsaPasswordCipherTest {
    private static final OAEPParameterSpec OAEP_SHA_256 = new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    @Test
    void decryptsBrowserCompatibleRsaOaepCiphertext() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(pair.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----";
        RsaPasswordCipher cipher = new RsaPasswordCipher(pem, false);

        var publicKey = KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(cipher.publicKey().publicKey())));
        Cipher encryptor = Cipher.getInstance("RSA/ECB/OAEPPadding");
        encryptor.init(Cipher.ENCRYPT_MODE, publicKey, OAEP_SHA_256);
        String encrypted = Base64.getEncoder().encodeToString(encryptor.doFinal("Valid@123".getBytes(StandardCharsets.UTF_8)));

        assertEquals("Valid@123", cipher.decrypt(cipher.publicKey().keyId(), encrypted));
        assertThrows(BusinessException.class, () -> cipher.decrypt("obsolete-key", encrypted));
    }

    @Test
    void createsEphemeralKeyForLocalDevelopmentWhenNoKeyIsConfigured() {
        RsaPasswordCipher cipher = new RsaPasswordCipher("", true);

        assertNotNull(cipher.publicKey().keyId());
        assertEquals("RSA-OAEP-256", cipher.publicKey().algorithm());
        assertNotNull(cipher.publicKey().publicKey());
    }
}
