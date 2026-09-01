package com.boc.nl2sql.access.auth.application;

/** 浏览器密码加密与服务端解密的边界。 */
public interface PasswordCipher {
    PublicKeyInfo publicKey();

    String decrypt(String keyId, String encryptedPassword);

    record PublicKeyInfo(String keyId, String algorithm, String publicKey) {
    }
}
