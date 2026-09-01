package com.boc.nl2sql.model;

import com.boc.nl2sql.authorization.domain.*;
import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.knowledge.BusinessTermCatalog;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqlPlanningToolsTest {
    final CurrentUser user=new CurrentUser(1L,"manager01","经理",RoleCode.CUSTOMER_MANAGER,"EAST","B001","M0001");
    final String valid="SELECT c.customer_id FROM dim_customer c WHERE c.manager_id='M0001' LIMIT 100";
    Nl2SqlPrompts prompts(){var terms=mock(BusinessTermCatalog.class);when(terms.promptContext()).thenReturn("");return new Nl2SqlPrompts(terms, null, 100);}
    @Test void toolsEnforceScopeRejectWritesAndNeverReturnRows(){
        var tools=new SqlPlanningTools(prompts(),100);
        assertThat(tools.call("validate_sql",Map.of("sql",valid),user)).containsEntry("ok",true).containsEntry("executed",false).doesNotContainKey("rows");
        assertThat(tools.call("validate_sql",Map.of("sql","SELECT c.customer_id FROM dim_customer c LIMIT 10"),user)).containsEntry("code",403104);
        assertThat(tools.call("validate_sql",Map.of("sql","DELETE FROM dim_customer"),user)).containsEntry("ok",false);
        assertThat(tools.call("execute_sql",Map.of("sql",valid),user)).containsEntry("code","UNKNOWN_TOOL");
        assertThat(tools.call("validate_sql",Map.of("sql",valid,"user_id",3),user)).containsEntry("ok",false);
        try(var context=ModelCallContext.open(()->true,()->"C00000001")){
            assertThat(tools.call("validate_sql",Map.of("sql",valid),user)).containsEntry("code",403105);
        }
    }
    @Test void budgetIsPersistentAndShared(@TempDir Path dir){
        var path=dir.resolve("budget.txt").toString();
        new ModelRequestBudget(path,2).acquire();new ModelRequestBudget(path,2).acquire();
        assertThatThrownBy(()->new ModelRequestBudget(path,2).acquire()).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo(429101));
    }
    @Test void toolLoopPassesFeedbackAndThinkingContextThenProducesFinalPlan() throws Exception {
        var json=JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
        var requests=new CopyOnWriteArrayList<Map>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{
            requests.add(json.readValue(exchange.getRequestBody().readAllBytes(),Map.class));int n=requests.size();
            Map<String,Object> message;
            if(n<=2)message=Map.of("role","assistant","content","","reasoning_content","private reasoning",
                    "tool_calls",List.of(Map.of("id","check-"+n,"type","function","function",Map.of("name","validate_sql","arguments",json.writeValueAsString(Map.of("sql",n==1?"SELECT c.customer_id FROM dim_customer c LIMIT 100":valid))))));
            else message=Map.of("role","assistant","content",json.writeValueAsString(Map.of("intent","GENERIC_ANALYSIS","confidence",0.99,"needs_clarification",false,"sql",valid,"preferred_display","TABLE")));
            byte[] body=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason",n<=2?"tool_calls":"stop","message",message))));
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try{
            var adapter=new DeepSeekModelAdapter(json,prompts(),"http://127.0.0.1:"+server.getAddress().getPort(),"local-test","test",true,4096,8192,10,true,3,true);
            ReflectionTestUtils.setField(adapter,"sqlTools",new SqlPlanningTools(prompts(),100));ReflectionTestUtils.setField(adapter,"toolsEnabled",true);
            assertThat(adapter.interpret("查询客户",user).generatedSql()).isEqualTo(valid);
            assertThat(requests).hasSize(3);
            assertThat(json.writeValueAsString(requests.get(1).get("messages"))).contains("403104","private reasoning","check-1");
            assertThat(json.writeValueAsString(requests.get(2).get("messages"))).contains("check-2","预检通过");
        }finally{server.stop(0);}
    }
}
