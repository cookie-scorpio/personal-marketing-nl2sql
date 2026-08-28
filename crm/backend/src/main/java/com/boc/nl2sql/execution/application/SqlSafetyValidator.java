package com.boc.nl2sql.execution.application;
import org.springframework.stereotype.Component;
import java.util.Map;
@Component
public class SqlSafetyValidator {
    @org.springframework.beans.factory.annotation.Value("${app.query.max-result-rows:100}") private int maxRows=100;
    public void validate(String sql){new SqlAstValidator(null,Map.of(),null,maxRows).validate(sql);}
}
