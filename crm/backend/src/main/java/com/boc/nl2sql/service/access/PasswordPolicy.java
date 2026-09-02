package com.boc.nl2sql.service.access;

import com.boc.nl2sql.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** 服务端强密码策略；前端提示不能替代这里的最终校验。 */
@Component
public class PasswordPolicy {
    public void validate(String password) {
        if (password == null || password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72
                || !containsDigit(password) || !containsLowercase(password)
                || !containsUppercase(password) || !containsSpecial(password)) {
            throw new BusinessException(400012,
                    "密码需至少8位，且包含数字、小写字母、大写字母和特殊符号；长度不得超过72字节");
        }
    }

    private boolean containsDigit(String value) {
        return value.chars().anyMatch(character -> character >= '0' && character <= '9');
    }

    private boolean containsLowercase(String value) {
        return value.chars().anyMatch(character -> character >= 'a' && character <= 'z');
    }

    private boolean containsUppercase(String value) {
        return value.chars().anyMatch(character -> character >= 'A' && character <= 'Z');
    }

    private boolean containsSpecial(String value) {
        return value.chars().anyMatch(character -> !((character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z') || (character >= '0' && character <= '9')
                || Character.isWhitespace(character)));
    }
}
