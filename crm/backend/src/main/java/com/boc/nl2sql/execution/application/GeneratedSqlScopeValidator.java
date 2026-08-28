package com.boc.nl2sql.execution.application;
import com.boc.nl2sql.authorization.domain.CurrentUser;
import org.springframework.stereotype.Component;
import java.util.Map;
@Component
public class GeneratedSqlScopeValidator {
    @org.springframework.beans.factory.annotation.Value("${app.query.max-result-rows:100}") private int maxRows=100;
    public void validate(String sql,CurrentUser user){new SqlAstValidator(user,Map.of(),null,maxRows).validate(sql);}
    public void validateCustomer(String sql,Map<String,Object> parameters,String customer){new SqlAstValidator(null,parameters,customer,maxRows).validate(sql);}
}
