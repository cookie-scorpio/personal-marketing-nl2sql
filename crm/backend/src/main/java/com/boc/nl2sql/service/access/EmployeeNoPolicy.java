package com.boc.nl2sql.service.access;

import com.boc.nl2sql.common.exception.BusinessException;
import org.springframework.stereotype.Component;

/** 工号是账号的受控身份标识，注册时只接受五位阿拉伯数字。 */
@Component
public class EmployeeNoPolicy {
    public String normalizeAndValidate(String value) {
        String employeeNo = value == null ? "" : value.trim();
        if (!employeeNo.matches("[0-9]{5}")) {
            throw new BusinessException(400015, "工号必须为5位阿拉伯数字");
        }
        return employeeNo;
    }
}
