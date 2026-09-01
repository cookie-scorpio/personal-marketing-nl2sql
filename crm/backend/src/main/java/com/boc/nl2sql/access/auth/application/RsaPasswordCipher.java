package com.boc.nl2sql.access.auth.application;

import com.boc.nl2sql.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 使用 PKCS#8 RSA 私钥解密浏览器密码。
 * 私钥不进入数据库、前端构建产物、接口响应或日志；公开接口只返回由私钥推导出的 SPKI 公钥。
 */
@Component
public class RsaPasswordCipher implements PasswordCipher {
    private static final OAEPParameterSpec OAEP_SHA_256 = new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    private final PrivateKey privateKey;
    private final PublicKeyInfo publicKey;

    public RsaPasswordCipher(@Value("${app.security.password-rsa-private-key:}") String configuredPrivateKey,
                             @Value("${app.security.allow-ephemeral-password-rsa-key:true}") boolean allowEphemeralKey) {
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            privateKey = resolvePrivateKey(configuredPrivateKey, allowEphemeralKey, factory);
            if (!(privateKey instanceof RSAPrivateCrtKey crtKey)) {
                throw new IllegalArgumentException("私钥不是可推导公钥的 RSA CRT 密钥");
            }
            var publicKeyValue = factory.generatePublic(new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent()));
            String publicKeyBase64 = Base64.getEncoder().encodeToString(publicKeyValue.getEncoded());
            String keyId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publicKeyValue.getEncoded()))
                    .substring(0, 16);
            publicKey = new PublicKeyInfo(keyId, "RSA-OAEP-256", publicKeyBase64);
        } catch (Exception exception) {
            throw new IllegalStateException("RSA 密钥初始化失败，请检查 AUTH_RSA_PRIVATE_KEY 配置", exception);
        }
    }

    @Override
    public PublicKeyInfo publicKey() {
        return publicKey;
    }

    @Override
    public String decrypt(String keyId, String encryptedPassword) {
        if (!publicKey.keyId().equals(keyId)) {
            throw new BusinessException(400013, "密码加密密钥已更新，请刷新页面后重试");
        }
        if (encryptedPassword == null || encryptedPassword.isBlank() || encryptedPassword.length() > 8192) {
            throw invalidEnvelope();
        }
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            cipher.init(Cipher.DECRYPT_MODE, privateKey, OAEP_SHA_256);
            byte[] plaintext = cipher.doFinal(Base64.getDecoder().decode(encryptedPassword));
            String password = new String(plaintext, StandardCharsets.UTF_8);
            if (password.indexOf('\u0000') >= 0) {
                throw invalidEnvelope();
            }
            return password;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidEnvelope();
        }
    }

    private static byte[] decodePem(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("未配置私钥");
        }
        String body = value.replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    private static PrivateKey resolvePrivateKey(String configuredPrivateKey,
                                                boolean allowEphemeralKey,
                                                KeyFactory factory) throws Exception {
        if (configuredPrivateKey != null && !configuredPrivateKey.isBlank()) {
            return factory.generatePrivate(new PKCS8EncodedKeySpec(decodePem(configuredPrivateKey)));
        }
        if (!allowEphemeralKey) {
            throw new IllegalStateException("未配置 RSA 私钥，且已禁止生成本地临时密钥");
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair().getPrivate();
    }

    private BusinessException invalidEnvelope() {
        return new BusinessException(400013, "密码加密数据无效，请刷新页面后重试");
    }
}
