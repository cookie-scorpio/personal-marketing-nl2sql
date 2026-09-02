package com.boc.nl2sql.service.access;

import com.boc.nl2sql.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/** 与既有 manager01、leader01 等账号保持一致的用户名规则。 */
@Component
public class UsernamePolicy {
    private static final Pattern USERNAME = Pattern.compile("^[a-z]+[0-9]+$");

    public String normalizeAndValidate(String value) {
        String username = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (username.length() < 4 || username.length() > 64 || !USERNAME.matcher(username).matches()) {
            throw new BusinessException(400011, "用户名需为4至64位小写英文字母开头、数字结尾的组合，例如 manager01");
        }
        return username;
    }
}
