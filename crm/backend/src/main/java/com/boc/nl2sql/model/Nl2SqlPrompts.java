package com.boc.nl2sql.model;

import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.knowledge.BusinessTermCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/** 固定提示词与动态业务上下文分开维护；模型密钥从不进入提示词。 */
@Component
public class Nl2SqlPrompts {
    private final BusinessTermCatalog terms;
    private final int maxRows;
    private final String systemPrompt;
    private final String schemaContext;

    public Nl2SqlPrompts(BusinessTermCatalog terms, @Value("${app.query.max-result-rows:100}") int maxRows) {
        this.terms = terms;
        this.maxRows = maxRows;
        this.systemPrompt = read("prompts/nl2sql-system.txt");
        this.schemaContext = read("prompts/nl2sql-schema.txt");
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public String userPrompt(String question, CurrentUser user) {
        return schemaContext + "\n数据库业务术语（优先采用）：\n" + terms.promptContext()
                + "\n当前日期：" + LocalDate.now()
                + "\n最大返回行数：" + maxRows
                + "\n必须使用的数据范围条件：" + scopeCondition(user)
                + "\n用户问题：" + question;
    }

    private String scopeCondition(CurrentUser user) {
        return switch (user.role()) {
            case CUSTOMER_MANAGER -> "c.manager_id = '" + safeCode(user.managerId()) + "'";
            case TEAM_LEAD -> "c.branch_id = '" + safeCode(user.branchId()) + "'";
            case ORG_MANAGER -> "c.region_code = '" + safeCode(user.regionCode()) + "'";
        };
    }

    private String safeCode(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]+")) {
            throw new BusinessException(403103, "当前账号的数据范围配置无效");
        }
        return value;
    }

    private String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("无法加载NL2SQL提示词：" + path, exception);
        }
    }
}
