package com.boc.nl2sql.execution.application;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import com.boc.nl2sql.execution.domain.PlannedQuery;
import java.util.LinkedHashMap;

/** 独立JSON行日志：保留完整SQL结构，换行经JSON转义，不记录密钥、Prompt或思考正文。 */
@Component
public class SqlReviewLog {
    private final ObjectMapper json;
    public SqlReviewLog(ObjectMapper json){this.json=json;}
    public void record(String task,String request,String source,String phase,PlannedQuery plan,String outcome){
        var entry=new LinkedHashMap<String,Object>();entry.put("task_id",task);entry.put("request_id",request);entry.put("source",source);entry.put("phase",phase);entry.put("sql",plan.sql());
        var params=new LinkedHashMap<String,Object>();plan.parameters().forEach((key,value)->params.put(key,key.toLowerCase().matches(".*(password|secret|token|mobile|phone|customername).*")?"[REDACTED]":value));
        entry.put("parameters",params);entry.put("outcome",outcome);
        LoggerFactory.getLogger("SQL_REVIEW").info(json.writeValueAsString(entry));
    }
}
