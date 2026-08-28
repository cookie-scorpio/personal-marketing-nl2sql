package com.boc.nl2sql.conversation.api;

import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.conversation.application.ConversationStore;
import com.boc.nl2sql.conversation.application.QueryApplicationService;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.Set;

/** 事件落库后发送，Last-Event-ID可在任意实例继续读取。断开订阅不会取消SQL。 */
@RestController
public class QueryEventsController {
    private final QueryApplicationService service;private final ConversationStore store;private final ObjectMapper json;
    private final ScheduledExecutorService scheduler=Executors.newScheduledThreadPool(2,r->{Thread t=new Thread(r,"query-sse");t.setDaemon(true);return t;});
    public QueryEventsController(QueryApplicationService service,ConversationStore store,ObjectMapper json){this.service=service;this.store=store;this.json=json;}
    @GetMapping(value="/api/v1/queries/{id}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id,@AuthenticationPrincipal CurrentUser user,
            @RequestHeader(value="Last-Event-ID",defaultValue="0")long after,HttpServletResponse response){
        var initial=service.status(id,user);
        if(after<0)throw new com.boc.nl2sql.common.exception.BusinessException(400001,"事件编号不能为负数");
        response.setHeader("Cache-Control","no-cache, no-transform");response.setHeader("X-Accel-Buffering","no");
        SseEmitter emitter=new SseEmitter(55000L);AtomicLong cursor=new AtomicLong(after),ticks=new AtomicLong();
        AtomicBoolean done=new AtomicBoolean();AtomicReference<ScheduledFuture<?>> scheduled=new AtomicReference<>();
        Runnable stop=()->{done.set(true);var future=scheduled.get();if(future!=null)future.cancel(false);};
        emitter.onCompletion(stop);emitter.onTimeout(()->{stop.run();emitter.complete();});emitter.onError(error->stop.run());
        scheduled.set(scheduler.scheduleWithFixedDelay(()->{
            if(done.get())return;
            try{
                service.status(id,user); // 每次回放前重新验证会话未被删除
                var batch=store.events(id,cursor.get());
                for(var row:batch){
                    long event=((Number)row.get("event_id")).longValue();String payload=row.get("payload_json").toString();
                    emitter.send(SseEmitter.event().id(Long.toString(event)).name("status").data(payload,MediaType.APPLICATION_JSON));cursor.set(event);
                }
                if(batch.size()<100){
                    var current=service.status(id,user);
                    if(Set.of("ASKING","CONFIRMING","SUCCESS","FAILED","CANCELLED","TIMED_OUT","DEGRADED").contains(current.status())){
                        if(batch.isEmpty())emitter.send(SseEmitter.event().name("snapshot").data(json.writeValueAsString(current),MediaType.APPLICATION_JSON));
                        stop.run();emitter.complete();return;
                    }
                }
                if(ticks.incrementAndGet()%30==0)emitter.send(SseEmitter.event().comment("heartbeat"));
            }catch(Exception disconnected){stop.run();emitter.complete();}
        },0,350,TimeUnit.MILLISECONDS));
        if(done.get())scheduled.get().cancel(false);
        return emitter;
    }
    @PreDestroy public void close(){scheduler.shutdownNow();}
}
