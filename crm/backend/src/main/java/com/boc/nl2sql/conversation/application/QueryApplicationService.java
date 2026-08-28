package com.boc.nl2sql.conversation.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.boc.nl2sql.audit.AuditService;
import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.conversation.api.ClarificationRequest;
import com.boc.nl2sql.conversation.api.ConfirmationRequest;
import com.boc.nl2sql.conversation.api.SubmitQueryRequest;
import com.boc.nl2sql.conversation.api.SubmitQueryResponse;
import com.boc.nl2sql.conversation.api.TaskStatusResponse;
import com.boc.nl2sql.conversation.domain.QueryStatus;
import com.boc.nl2sql.conversation.infrastructure.QueryTaskEntity;
import com.boc.nl2sql.conversation.infrastructure.QueryTaskMapper;
import com.boc.nl2sql.execution.domain.QueryResult;
import com.boc.nl2sql.nl2sql.domain.ClarificationQuestion;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class QueryApplicationService {
    private final QueryTaskMapper taskMapper;
    private final QueryTaskProcessor processor;
    private final SessionContextStore contextStore;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final TaskStateStore states;
    private final com.boc.nl2sql.execution.QueryExecutionGateway execution;
    private final com.boc.nl2sql.history.application.HistoryService history;
    private final int timeoutSeconds;
    @org.springframework.beans.factory.annotation.Autowired private ConversationStore conversations;
    @org.springframework.beans.factory.annotation.Autowired private TaskSnapshots snapshots;
    @org.springframework.beans.factory.annotation.Autowired private CustomerResolver customers;
    @org.springframework.beans.factory.annotation.Autowired private FollowupResolver followups;
    @org.springframework.beans.factory.annotation.Autowired private IdempotencyCache idempotency;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.transaction.PlatformTransactionManager transactions;

    public QueryApplicationService(QueryTaskMapper taskMapper, QueryTaskProcessor processor,
                                   SessionContextStore contextStore, AuditService auditService,
                                   ObjectMapper objectMapper, TaskStateStore states,
                                   com.boc.nl2sql.execution.QueryExecutionGateway execution,
                                   com.boc.nl2sql.history.application.HistoryService history,
                                   @org.springframework.beans.factory.annotation.Value("${app.query.execution-timeout-seconds:60}") int timeoutSeconds) {
        this.taskMapper = taskMapper;
        this.processor = processor;
        this.contextStore = contextStore;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.states = states; this.execution = execution; this.history = history; this.timeoutSeconds = timeoutSeconds;
    }

    public SubmitQueryResponse submit(SubmitQueryRequest request, CurrentUser user, String requestId) {
        return submit(request,user,requestId,UUID.randomUUID().toString());
    }

    public SubmitQueryResponse submit(SubmitQueryRequest request, CurrentUser user, String requestId,String key) {
        if(key==null||!key.matches("[A-Za-z0-9._:-]{8,128}"))throw new BusinessException(400004,"请提供8至128位有效 Idempotency-Key");
        String fingerprint=IdempotencyCache.hash(objectMapper.writeValueAsString(java.util.List.of(request.sessionId(),request.queryText().trim(),request.preferredDisplay()==null?"AUTO":request.preferredDisplay(),request.thinkingEnabled()==null||request.thinkingEnabled())));
        String cached=idempotency.get(user.userId(),key);
        if(cached!=null){QueryTaskEntity old=taskMapper.selectById(cached);if(old!=null&&old.getUserId().equals(user.userId())&&key.equals(old.getIdempotencyKey()))return replay(old,fingerprint);}
        SubmitQueryResponse response;
        try {
            var template=new org.springframework.transaction.support.TransactionTemplate(transactions);
            template.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
            response=template.execute(tx->{
                var old=findSubmission(user.userId(),key);if(old!=null)return replay(old,fingerprint);
                var session=conversations.lock(request.sessionId(),user,customers.redact(request.queryText().trim()));
                old=findSubmission(user.userId(),key);if(old!=null)return replay(old,fingerprint);
                if(session.get("active_task_id")!=null){
                    var active=taskMapper.selectById(session.get("active_task_id").toString());
                    if(active!=null&&!QueryStatus.terminal(active.getStatusCode()))throw new BusinessException(409006,"本会话仍有未结束的查询，请先完成补充、确认或取消");
                }
                return create(request,user,requestId,key,fingerprint,session);
            });
        }catch(org.springframework.dao.DuplicateKeyException collision){
            var old=findSubmission(user.userId(),key);if(old==null)throw collision;response=replay(old,fingerprint);
        }
        idempotency.put(user.userId(),key,response.taskId());return response;
    }
    private QueryTaskEntity findSubmission(long user,String key){return taskMapper.selectOne(Wrappers.<QueryTaskEntity>lambdaQuery().eq(QueryTaskEntity::getUserId,user).eq(QueryTaskEntity::getIdempotencyKey,key));}
    private SubmitQueryResponse replay(QueryTaskEntity task,String fingerprint){
        conversations.visible(task.getSessionId(),task.getUserId());
        if(!fingerprint.equals(task.getRequestHash()))throw new BusinessException(409005,"同一幂等键不能用于不同请求，请为新问题创建新的提交");
        return new SubmitQueryResponse(task.getTaskId(),task.getSessionId(),task.getStatusCode(),task.getProgress(),"/api/v1/queries/"+task.getTaskId()+"/status");
    }
    private SubmitQueryResponse create(SubmitQueryRequest request,CurrentUser user,String requestId,String key,String fingerprint,Map<String,Object> session){
        String taskId = UUID.randomUUID().toString();
        QueryTaskEntity task = new QueryTaskEntity();
        task.setTaskId(taskId);
        task.setSessionId(request.sessionId());
        task.setUserId(user.userId());
        task.setQueryText(customers.redact(request.queryText().trim()));
        String display=request.preferredDisplay()==null?"AUTO":request.preferredDisplay().toUpperCase(java.util.Locale.ROOT);
        if(!java.util.Set.of("AUTO","TABLE","BAR","LINE","AREA","PIE","SCATTER","HEATMAP","METRIC").contains(display))throw new BusinessException(400001,"preferred_display不是支持的展示类型");
        task.setPreferredDisplay(display);
        var previous=conversations.context(session);
        boolean inherit=followups.followup(request.queryText()) && !customers.explicitIdentity(request.queryText());
        task.setMergedQueryText(inherit?followups.merge(request.queryText().trim(),previous):request.queryText().trim());
        if(task.getMergedQueryText().length()>8000)throw new BusinessException(400005,"当前上下文过长，请新建会话并明确需要保留的条件");
        task.setResolvedCustomerId(inherit?previous.customerId():null);
        task.setContextJson(objectMapper.writeValueAsString(inherit?previous:com.boc.nl2sql.conversation.domain.ConversationContext.empty()));
        task.setDisplayQuery(customers.redact(task.getMergedQueryText()));
        task.setThinkingEnabled(request.thinkingEnabled()==null||request.thinkingEnabled());
        task.setIdempotencyKey(key);task.setRequestHash(fingerprint);
        task.setStatusCode(QueryStatus.RECEIVED.name());
        task.setProgress(0);
        task.setStageMessage("查询请求已接收");
        task.setClarificationRound(0);
        task.setStateVersion(0L);
        task.setRepairAttempts(0);
        task.setConfirmed(false);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.insert(task);
        conversations.activate(task.getSessionId(),taskId);
        conversations.userMessage(task,"query",task.getQueryText());conversations.record(task);
        auditService.record(requestId, taskId, user.userId(), "QUERY_RECEIVED", "provider request accepted");
        enqueue(taskId,user,requestId);
        return new SubmitQueryResponse(taskId, request.sessionId(), QueryStatus.RECEIVED.name(), 0,
                "/api/v1/queries/" + taskId + "/status");
    }

    public TaskStatusResponse status(String taskId, CurrentUser user) {
        QueryTaskEntity task = ownedTask(taskId, user);
        return snapshots.of(task);
    }

    private Map<String, Object> confirmation(QueryTaskEntity task) {
        @SuppressWarnings("unchecked")
        Map<String, Object> risk = read(task.getRiskJson(), Map.class);
        Object level = risk == null ? "MEDIUM" : risk.getOrDefault("level", "MEDIUM");
        Object reasons = risk == null ? java.util.List.of("查询范围较大")
                : risk.getOrDefault("reasons", java.util.List.of("查询范围较大"));
        return Map.of("confirm_token", task.getConfirmationToken(), "risk_level", level,
                "message", "该SQL可能涉及大量数据或较长查询时延，请确认后执行。", "reasons", reasons);
    }

    @org.springframework.transaction.annotation.Transactional
    public SubmitQueryResponse clarify(String sessionId, ClarificationRequest request,
                                       CurrentUser user, String requestId) {
        QueryTaskEntity task = ownedTask(request.taskId(), user);
        conversations.lockTask(task);task=ownedTaskForUpdate(request.taskId(),user);
        if (!task.getSessionId().equals(sessionId) || !QueryStatus.ASKING.name().equals(task.getStatusCode())) {
            throw new BusinessException(409001, "当前任务不在等待补充状态");
        }
        String answer = request.mergedAnswer();
        if (answer.isBlank()) throw new BusinessException(400002, "请填写补充条件或选择一个选项");
        ClarificationQuestion question = read(task.getQuestionJson(), ClarificationQuestion.class);
        if (question == null || !question.questionId().equals(request.questionId())) {
            throw new BusinessException(409002, "反问已失效，请刷新任务状态");
        }
        String connector = "CONFLICT".equals(question.type()) ? "，最终条件为："
                : "TIME_BASIS".equals(question.type()) ? "，时间口径：" : "，补充条件：";
        if(question.type().startsWith("CUSTOMER_"))customers.answer(task,user,question,answer,request.identityType());
        else if("FOLLOWUP_CONTEXT".equals(question.type()))task.setMergedQueryText(answer);
        else task.setMergedQueryText(task.getMergedQueryText() + connector + answer);
        task.setDisplayQuery(customers.redact(task.getMergedQueryText()));
        String visibleAnswer="CUSTOMER_NAME".equals(request.identityType())?CustomerResolver.mask(answer):customers.redact(answer);
        conversations.userMessage(task,"answer-"+question.questionId(),request.identityType()==null?visibleAnswer:request.identityType()+"："+visibleAnswer);
        task.setClarificationRound(task.getClarificationRound() + 1);
        task.setQuestionJson(null);
        task.setStatusCode(QueryStatus.RECEIVED.name());
        task.setProgress(10);
        task.setStageMessage("已收到补充条件，正在重新解析");
        saveOrConflict(task);
        auditService.record(requestId, task.getTaskId(), user.userId(), "QUERY_CLARIFIED", question.type());
        enqueue(task.getTaskId(), user, requestId);
        return new SubmitQueryResponse(task.getTaskId(), sessionId, task.getStatusCode(), task.getProgress(),
                "/api/v1/queries/" + task.getTaskId() + "/status");
    }

    @org.springframework.transaction.annotation.Transactional
    public TaskStatusResponse confirm(String taskId, ConfirmationRequest request,
                                      CurrentUser user, String requestId) {
        QueryTaskEntity task = ownedTask(taskId, user);
        conversations.lockTask(task);task=ownedTaskForUpdate(taskId,user);
        if (!QueryStatus.CONFIRMING.name().equals(task.getStatusCode())
                || !request.confirmToken().equals(task.getConfirmationToken())) {
            throw new BusinessException(409003, "确认令牌无效或已过期");
        }
        if ("REJECT".equalsIgnoreCase(request.decision())) {
            return cancel(taskId, user, requestId);
        }
        if (!"CONFIRM".equalsIgnoreCase(request.decision())) {
            throw new BusinessException(400003, "decision 仅支持 CONFIRM 或 REJECT");
        }
        task.setConfirmed(true);
        task.setConfirmationToken(null);
        task.setStatusCode(QueryStatus.RECEIVED.name());
        task.setStageMessage("已确认，准备执行查询");
        conversations.userMessage(task,"confirm-"+request.confirmToken(),"确认并执行");
        saveOrConflict(task);
        auditService.record(requestId, taskId, user.userId(), "QUERY_CONFIRMED", "user confirmed high scope query");
        enqueue(taskId, user, requestId);
        return status(taskId, user);
    }

    @org.springframework.transaction.annotation.Transactional
    public TaskStatusResponse cancel(String taskId, CurrentUser user, String requestId) {
        QueryTaskEntity task = ownedTask(taskId, user);
        conversations.lockTask(task);task=ownedTaskForUpdate(taskId,user);
        int changed = taskMapper.update(null, Wrappers.<QueryTaskEntity>lambdaUpdate()
                .eq(QueryTaskEntity::getTaskId, taskId).eq(QueryTaskEntity::getUserId, user.userId())
                .notIn(QueryTaskEntity::getStatusCode, "SUCCESS", "FAILED", "CANCELLED", "TIMED_OUT", "DEGRADED")
                .set(QueryTaskEntity::getStatusCode, "CANCELLED").set(QueryTaskEntity::getProgress, 100)
                .set(QueryTaskEntity::getStageMessage, "查询已取消，不会继续修复或降级执行")
                .set(QueryTaskEntity::getConfirmationToken, null).set(QueryTaskEntity::getQuestionJson, null)
                .set(QueryTaskEntity::getUpdatedAt, LocalDateTime.now()).setSql("state_version = state_version + 1"));
        if (changed == 1) {
            conversations.record(taskMapper.selectById(taskId));
            afterCommit(()->execution.cancel(taskId));
            try {
                history.save(taskId, user.userId(), task.getQueryText(), task.getIntentCode(), "CANCELLED", task.getSqlText(), "查询已取消");
                auditService.record(requestId, taskId, user.userId(), "QUERY_CANCELLED", "USER_REQUEST");
            } catch (RuntimeException recordFailure) {
                org.slf4j.LoggerFactory.getLogger(QueryApplicationService.class)
                        .error("取消任务的附属记录写入失败：taskId={}", taskId);
            }
        }
        return status(taskId, user);
    }

    private void enqueue(String id,CurrentUser user,String requestId){afterCommit(()->{
        try{processor.processAsync(id,user,requestId);}catch(org.springframework.core.task.TaskRejectedException rejected){
            // afterCommit时原事务资源尚未解绑，队列拒绝后的状态必须在独立事务提交。
            var failureTx=new org.springframework.transaction.support.TransactionTemplate(transactions);
            failureTx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            failureTx.executeWithoutResult(tx->{QueryTaskEntity task=taskMapper.selectById(id);task.setStatusCode("FAILED");task.setProgress(100);task.setStageMessage("查询队列繁忙，请重新提交");task.setErrorMessage("查询队列繁忙，请重新提交");states.trySave(task);});
        }
    });}
    private void afterCommit(Runnable work){
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){@Override public void afterCommit(){work.run();}});
        else work.run();
    }

    private void saveOrConflict(QueryTaskEntity task) {
        if (!states.trySave(task)) throw new BusinessException(409004, "任务状态已变更，请刷新后重试");
    }

    private QueryTaskEntity ownedTask(String taskId, CurrentUser user) {
        return ownedTask(taskId,user,false);
    }

    private QueryTaskEntity ownedTaskForUpdate(String taskId,CurrentUser user){
        return ownedTask(taskId,user,true);
    }

    private QueryTaskEntity ownedTask(String taskId,CurrentUser user,boolean lock){
        QueryTaskEntity task = taskMapper.selectOne(Wrappers.<QueryTaskEntity>lambdaQuery()
                .eq(QueryTaskEntity::getTaskId, taskId)
                .eq(QueryTaskEntity::getUserId, user.userId())
                // FOR UPDATE是当前读，避免REPEATABLE READ复用等待会话锁之前的快照。
                .last(lock?"LIMIT 1 FOR UPDATE":"LIMIT 1"));
        if (task == null) throw new BusinessException(404001, "查询任务不存在");
        conversations.own(task.getSessionId(),user);
        return task;
    }

    private <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored task JSON is invalid", exception);
        }
    }
}
