package com.boc.nl2sql.model;

import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.execution.application.SqlAstValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/** 无数据库执行能力的规划工具。身份由服务端闭包提供，模型不能传入或修改。 */
@Component
public class SqlPlanningTools {
    private final Nl2SqlPrompts prompts;
    private final int maxRows;
    public SqlPlanningTools(Nl2SqlPrompts prompts,@Value("${app.query.max-sql-limit:500}") int maxRows){this.prompts=prompts;this.maxRows=maxRows;}
    public List<Map<String,Object>> definitions(){
        return List.of(function("get_query_schema","读取允许查询的表结构、业务口径、当前账号范围；不返回客户数据。",Map.of(),List.of()),
                function("validate_sql","在提交最终JSON之前检查完整SQL的只读、字段和账号范围。失败时修正SQL再检查。此工具不执行SQL、不替代最终校验或风险确认。",Map.of("sql",Map.of("type","string","description","完整单条MySQL SELECT；分页由服务端统一添加")),List.of("sql")));
    }
    private Map<String,Object> function(String name,String description,Map<String,Object> properties,List<String> required){
        return Map.of("type","function","function",Map.of("name",name,"description",description,"parameters",Map.of("type","object","properties",properties,"required",required,"additionalProperties",false)));
    }
    public Map<String,Object> call(String name,Map<String,Object> arguments,CurrentUser user){
        if("get_query_schema".equals(name) && arguments.isEmpty())return Map.of("ok",true,"schema",prompts.userPrompt("仅查看结构",user));
        if(!"validate_sql".equals(name))return Map.of("ok",false,"code","UNKNOWN_TOOL","message","仅支持get_query_schema与validate_sql");
        if(arguments.size()!=1 || !(arguments.get("sql") instanceof String sql) || sql.length()>30000)
            return Map.of("ok",false,"code",400001,"message","validate_sql只接受sql字符串，最大30000字符");
        try{
            new SqlAstValidator(user,Map.of(),ModelCallContext.customer()==null?null:java.util.List.of(ModelCallContext.customer()),maxRows).validate(sql);
            return Map.of("ok",true,"executed",false,"message","静态预检通过；不代表业务口径或MySQL执行正确。最终SQL仍需后端校验、已确认客户校验与风险确认。");
        }catch(BusinessException invalid){return Map.of("ok",false,"executed",false,"code",invalid.code(),"message",invalid.getMessage());}
    }
}
