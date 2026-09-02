package com.boc.nl2sql.model;

import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.authorization.application.DataScopePolicy;
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
    private final DataInsightProvider insights;
    private final int maxRows;
    private final String systemPrompt;
    private final String schemaContext;

    public Nl2SqlPrompts(BusinessTermCatalog terms, DataInsightProvider insights, @Value("${app.query.max-sql-limit:500}") int maxRows) {
        this.insights = insights;
        this.terms = terms;
        this.maxRows = maxRows;
        this.systemPrompt = read("prompts/nl2sql-system.txt");
        this.schemaContext = read("prompts/nl2sql-schema.txt");
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public String userPrompt(String question, CurrentUser user) {
        var sb = new StringBuilder(schemaContext)
                .append("\n数据库业务术语（优先采用）：\n").append(terms.promptContext())
                .append("\n当前日期：").append(LocalDate.now())
                .append("\n分页由服务端统一处理；不要添加LIMIT/OFFSET。用户要求Top N时改用ROW_NUMBER排名条件，N不得超过：").append(maxRows);
        // 注入数据概览，让模型选择合理的时间粒度与空结果说明，减少对快照日期的猜测。
        if (insights != null) {
        var snapshot = insights.latestSnapshot();
        if (snapshot != null) sb.append("\n最新持有快照日期：").append(snapshot).append("（查询当前持有直接用该日期）");
        var coverage = insights.transactionCoverage();
        if (coverage != null) sb.append("\n交易数据覆盖范围：").append(coverage).append("（范围外日期为无数据，不是0）");
        }
        sb.append("\n必须使用的数据范围条件：").append(scopeCondition(user))
          .append("\n用户问题：").append(question);
        return sb.toString();
    }

    private String scopeCondition(CurrentUser user) {
        return DataScopePolicy.literalCondition("c", user);
    }

    private String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("无法加载NL2SQL提示词：" + path, exception);
        }
    }
}
