package com.boc.nl2sql.execution.application;
import org.springframework.stereotype.Component;
import java.util.Map;

/** 为旧调用点保留的 SQL 安全校验入口，实际规则统一委托给 AST 校验器。 */
@Component
public class SqlSafetyValidator {
    @org.springframework.beans.factory.annotation.Value("${app.query.max-sql-limit:500}") private int maxRows=500;
    public void validate(String sql){new SqlAstValidator(null,Map.of(),null,maxRows).validate(sql);}
}
