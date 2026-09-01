package com.boc.nl2sql.execution.application;
import com.boc.nl2sql.authorization.domain.CurrentUser;
import org.springframework.stereotype.Component;
import java.util.Map;
@Component
public class GeneratedSqlScopeValidator {
    @org.springframework.beans.factory.annotation.Value("${app.query.max-sql-limit:500}") private int maxRows=500;
    public void validate(String sql,CurrentUser user){new SqlAstValidator(user,Map.of(),null,maxRows).validate(sql);}
    public void validateCustomer(String sql,Map<String,Object> parameters,String customer){new SqlAstValidator(null,parameters,customer==null?null:java.util.List.of(customer),maxRows).validate(sql);}
    /** @客户名单：以集合等值证明名单约束。 */
    public void validateCustomers(String sql,Map<String,Object> parameters,java.util.Collection<String> customers){new SqlAstValidator(null,parameters,customers,maxRows).validate(sql);}
}
