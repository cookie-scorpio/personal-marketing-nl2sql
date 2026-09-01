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
import com.boc.nl2sql.execution.domain.QueryPage;
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
    private final int defaultPageSize;
    private final int maxPageSize;
    private final long maxOffset;
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
                                   @org.springframework.beans.factory.annotation.Value("${app.query.execution-timeout-seconds:60}") int timeoutSeconds,
                                   @org.springframework.beans.factory.annotation.Value("${app.query.default-page-size:100}") int defaultPageSize,
                                   @org.springframework.beans.factory.annotation.Value("${app.query.max-page-size:500}") int maxPageSize,
                                   @org.springframework.beans.factory.annotation.Value("${app.query.max-offset:100000}") long maxOffset) {
        if (defaultPageSize < 1 || maxPageSize < defaultPageSize || maxOffset < 0) {
            throw new IllegalArgumentException("查询分页默认值、上限或最大偏移量配置无效");
        }
        this.taskMapper = taskMapper;
        this.processor = processor;
        this.contextStore = contextStore;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.states = states; this.execution = execution; this.history = history; this.timeoutSeconds = timeoutSeconds;
        this.defaultPageSize = defaultPageSize; this.maxPageSize = maxPageSize; this.maxOffset = maxOffset;
    }

    public SubmitQueryResponse submit(SubmitQueryRequest request, CurrentUser user, String requestId) {
        return submit(request,user,requestId,UUID.randomUUID().toString());
    }

    public SubmitQueryResponse submit(SubmitQueryRequest request, CurrentUser user, String requestId,String key) {
        if(key==null||!key.matches("[A-Za-z0-9._:-]{8,128}"))throw new BusinessException(400004,"请提供8至128位有效 Idempotency-Key");
        QueryPage page=page(request);
        java.util.List<Object> fingerprintParts=new java.util.ArrayList<>(java.util.List.of(request.sessionId(),request.queryText().trim(),request.preferredDisplay()==null?"AUTO":request.preferredDisplay(),request.thinkingEnabled()==null||request.thinkingEnabled()));
        if(request.pageNo()!=null||request.pageSize()!=null||request.limit()!=null||request.offset()!=null){
            fingerprintParts.add(page.pageNo());fingerprintParts.add(page.pageSize());fingerprintParts.add(page.offset());
        }
        String fingerprint=IdempotencyCache.hash(objectMapper.writeValueAsString(fingerprintParts));
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
                return create(request,user,requestId,key,fingerprint,session,page);
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
    private QueryPage page(SubmitQueryRequest request){
        boolean numbered=request.pageNo()!=null||request.pageSize()!=null;
        boolean ranged=request.limit()!=null||request.offset()!=null;
        if(numbered&&ranged)throw new BusinessException(400001,"page_no/page_size 与 limit/offset 不能同时传入");
        int size=ranged?(request.limit()==null?defaultPageSize:request.limit()):(request.pageSize()==null?defaultPageSize:request.pageSize());
        if(size<1||size>maxPageSize)throw new BusinessException(400001,"每页条数必须在1至"+maxPageSize+"之间");
        long offset;
        int pageNo;
        if(ranged){
            offset=request.offset()==null?0:request.offset();
            pageNo=(int)Math.min(Integer.MAX_VALUE,offset/size+1);
        }else{
            pageNo=request.pageNo()==null?1:request.pageNo();
            if(pageNo<1)throw new BusinessException(400001,"page_no必须大于等于1");
            try{offset=Math.multiplyExact((long)pageNo-1,size);}catch(ArithmeticException overflow){throw new BusinessException(400001,"分页偏移量过大");}
        }
        if(offset<0||offset>maxOffset)throw new BusinessException(400001,"offset必须在0至"+maxOffset+"之间；大结果集请缩小条件范围");
        return new QueryPage(pageNo,size,offset);
    }
    private SubmitQueryResponse create(SubmitQueryRequest request,CurrentUser user,String requestId,String key,String fingerprint,Map<String,Object> session,QueryPage page){
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
        // @客户名单（方案甲）：解析粘贴的编号集合并做账号范围预检。
        java.util.LinkedHashSet<String> requestedIds=new java.util.LinkedHashSet<>();
        if(request.customerIds()!=null){
            for(String raw:request.customerIds()){
                if(raw==null)continue;String id=raw.trim().toUpperCase(java.util.Locale.ROOT);
                if(!id.matches("C[0-9]{8}"))throw new BusinessException(400001,"客户编号格式不正确："+id);
                requestedIds.add(id);
            }
            if(requestedIds.size()>200)throw new BusinessException(400001,"客户名单一次最多200人，请分批查询");
        }
        // 用户显式扩大统计对象（全行/全部客户等）时不继承单客约束，避免形成无法解除的澄清循环。
        boolean expands=followups.expandsScope(request.queryText());
        boolean inherit=followups.followup(request.queryText()) && !customers.explicitIdentity(request.queryText()) && !expands;
        task.setMergedQueryText(inherit?followups.merge(request.queryText().trim(),previous):request.queryText().trim());
        if(task.getMergedQueryText().length()>8000)throw new BusinessException(400005,"当前上下文过长，请新建会话并明确需要保留的条件");
        task.setResolvedCustomerId(inherit&&!requestedIds.isEmpty()?previous.customerId():(requestedIds.isEmpty()?task.getResolvedCustomerId():null));
        task.setContextJson(objectMapper.writeValueAsString(inherit&&requestedIds.isEmpty()?previous:com.boc.nl2sql.conversation.domain.ConversationContext.empty()));
        // 展示文本只用用户原话（含承接提示），内部合并模板只交给模型。
        String displayBase=customers.redact(followups.displayText(request.queryText().trim(),inherit?previous:null,inherit));
        // 用户气泡忠实保存本人输入；助手回显、任务文本和模型上下文仍使用脱敏版本。
        String visibleUserQuery=request.queryText().trim();
        if(!requestedIds.isEmpty()){
            // @名单展示与用户消息一致：折叠编号为名单标签，不罗列长串编号。
            displayBase=displayBase.replaceAll("(?i)C[0-9]{8}(\s*[，,、]?\s*)+"," ").trim()
                    +"（@客户名单 "+requestedIds.size()+" 人）";
            visibleUserQuery=visibleUserQuery.replaceAll("(?i)C[0-9]{8}(\s*[，,、]?\s*)+"," ").trim()
                    +" @客户名单("+requestedIds.size()+"人)";
        }
        task.setDisplayQuery(displayBase);
        task.setPageNo(page.pageNo());task.setPageSize(page.pageSize());task.setPageOffset(page.offset());
        task.setThinkingEnabled(request.thinkingEnabled()==null||request.thinkingEnabled());
        task.setIdempotencyKey(key);task.setRequestHash(fingerprint);
        task.setStatusCode(QueryStatus.RECEIVED.name());
        task.setProgress(0);
        task.setStageMessage("查询请求已接收");
        task.setClarificationRound(0);
        task.setStateVersion(0L);
        task.setRepairAttempts(0);
        task.setConfirmed(false);
        if(!requestedIds.isEmpty()){
            // 范围预检：权限内的进入名单，权限外的通过澄清剔除，绝不静默丢弃。
            var scopeCheck=conversations.checkIdsScope(requestedIds,user);
            var inScope=new java.util.LinkedHashSet<String>(scopeCheck.inScope());
            var outOfScope=new java.util.LinkedHashSet<String>(scopeCheck.outOfScope());
            task.setCustomerIdsJson(objectMapper.writeValueAsString(inScope));
            if(!outOfScope.isEmpty()){
                String removed=String.join("、",outOfScope);
                var q=new com.boc.nl2sql.nl2sql.domain.ClarificationQuestion(UUID.randomUUID().toString(),"CUSTOMER_SCOPE",
                        "客户名单中有 "+outOfScope.size()+" 人不在您的数据权限范围（"+removed+"），已自动剔除。是否按剩余 "+inScope.size()+" 人继续查询？",
                        java.util.List.of("剔除后继续（剩余 "+inScope.size()+" 人）","取消本次查询"),
                        java.util.Map.of("名单总数",String.valueOf(requestedIds.size()),"权限外",removed),
                        "剔除后继续（剩余 "+inScope.size()+" 人）");
                task.setQuestionJson(objectMapper.writeValueAsString(q));
                task.setStatusCode(QueryStatus.ASKING.name());task.setProgress(30);task.setStageMessage(q.prompt());
                auditService.record(requestId, taskId, user.userId(), "QUERY_ASKING", "CUSTOMER_SCOPE");
                taskMapper.insert(task);
                conversations.activate(task.getSessionId(),taskId);
                conversations.userMessage(task,"query",visibleUserQuery);conversations.record(task);
                auditService.record(requestId, taskId, user.userId(), "QUERY_RECEIVED", "provider request accepted");
                return new SubmitQueryResponse(taskId, request.sessionId(), QueryStatus.ASKING.name(), 30,
                        "/api/v1/queries/" + taskId + "/status");
            }
        }
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.insert(task);
        conversations.activate(task.getSessionId(),taskId);
        conversations.userMessage(task,"query",visibleUserQuery);conversations.record(task);
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
        if("CUSTOMER_SCOPE".equals(question.type())){
            // @名单权限澄清：剔除后继续（名单已在提交时收敛为权限内集合）或直接取消。
            if(answer.contains("取消")){
                cancel(request.taskId(),user,requestId);
                return new SubmitQueryResponse(request.taskId(),sessionId,"CANCELLED",100,"/api/v1/queries/"+request.taskId()+"/status");
            }
            task.setStatusCode(QueryStatus.RECEIVED.name());task.setProgress(10);
            task.setStageMessage("已确认名单范围，正在重新解析");task.setQuestionJson(null);
            task.setClarificationRound(task.getClarificationRound()+1);
            conversations.userMessage(task,"answer-"+question.questionId(),answer);
            saveOrConflict(task);
            auditService.record(requestId,task.getTaskId(),user.userId(),"QUERY_CLARIFIED","CUSTOMER_SCOPE");
            enqueue(task.getTaskId(),user,requestId);
            return new SubmitQueryResponse(task.getTaskId(),sessionId,QueryStatus.RECEIVED.name(),10,
                    "/api/v1/queries/"+task.getTaskId()+"/status");
        }
        String connector = "CONFLICT".equals(question.type()) ? "，最终条件为："
                : "TIME_BASIS".equals(question.type()) ? "，时间口径："
                : "DISPLAY_CONFLICT".equals(question.type()) ? "，展示口径：" : "，补充条件：";
        if("CUSTOMER_CONFIRM".equals(question.type()))customers.confirmMulti(task,user,answer);
        else if(question.type().startsWith("CUSTOMER_"))customers.answer(task,user,question,answer,request.identityType());
        else if("FOLLOWUP_CONTEXT".equals(question.type()))task.setMergedQueryText(answer);
        else task.setMergedQueryText(task.getMergedQueryText() + connector + answer);
        task.setDisplayQuery(customers.redact(task.getMergedQueryText()));
        conversations.userMessage(task,"answer-"+question.questionId(),request.identityType()==null?answer:request.identityType()+"："+answer);
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
