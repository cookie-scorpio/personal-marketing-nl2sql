package com.boc.nl2sql.conversation.application;

import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.conversation.domain.QueryStatus;
import com.boc.nl2sql.conversation.infrastructure.QueryTaskEntity;
import com.boc.nl2sql.conversation.infrastructure.QueryTaskMapper;
import com.boc.nl2sql.execution.QueryExecutionGateway;
import com.boc.nl2sql.execution.QueryTerminatedException;
import com.boc.nl2sql.execution.application.*;
import com.boc.nl2sql.execution.domain.*;
import com.boc.nl2sql.model.ModelGateway;
import com.boc.nl2sql.model.QueryInterpretation;
import com.boc.nl2sql.nl2sql.application.CompletenessValidator;
import com.boc.nl2sql.quality.collection.QualityFacts;
import com.boc.nl2sql.quality.collection.SqlFactRecorder;
import com.boc.nl2sql.quality.event.QualityEventType;
import com.boc.nl2sql.quality.event.QualityFact;
import com.boc.nl2sql.quality.persistence.RepairFactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** 任务状态使用版本号提交；修复额度属于任务，确认/澄清都不会重置。 */
@Service
public class QueryTaskProcessor {
    private static final Logger log = LoggerFactory.getLogger(QueryTaskProcessor.class);
    private static final int MAX_REPAIRS = 2;
    private final QueryTaskMapper taskMapper;
    private final TaskStateStore states;
    private final ModelGateway modelGateway;
    private final CompletenessValidator completeness;
    private final SqlPlanner planner;
    private final SqlRiskEvaluator risks;
    private final SqlSafetyValidator safety;
    private final GeneratedSqlScopeValidator scope;
    private final QueryExecutionGateway execution;
    private final ResultAssembler assembler;
    private final FallbackPlanner fallbackPlanner;
    private final QualityFacts qualityFacts;
    private final ObjectMapper json;
    private final int maxClarifications;
    @org.springframework.beans.factory.annotation.Autowired private CustomerResolver customers;
    @org.springframework.beans.factory.annotation.Autowired private FollowupResolver followups;
    @org.springframework.beans.factory.annotation.Autowired private CustomerQueryPlanner customerPlanner;
    @org.springframework.beans.factory.annotation.Autowired private SqlFactRecorder sqlFacts;
    @org.springframework.beans.factory.annotation.Autowired private RepairFactStore repairFacts;

    public QueryTaskProcessor(QueryTaskMapper taskMapper, TaskStateStore states, ModelGateway modelGateway,
            CompletenessValidator completeness, SqlPlanner planner, SqlRiskEvaluator risks,
            SqlSafetyValidator safety, GeneratedSqlScopeValidator scope, QueryExecutionGateway execution,
            ResultAssembler assembler, FallbackPlanner fallbackPlanner, QualityFacts qualityFacts, ObjectMapper json,
            com.boc.nl2sql.nl2sql.application.DisplayConflictGuard conflictGuard,
            @Value("${app.query.max-clarification-rounds:5}") int maxClarifications) {
        this.taskMapper = taskMapper; this.states = states; this.modelGateway = modelGateway;
        this.completeness = completeness; this.planner = planner; this.risks = risks; this.safety = safety;
        this.scope = scope; this.execution = execution; this.assembler = assembler;
        this.fallbackPlanner = fallbackPlanner; this.qualityFacts = qualityFacts;
        this.json = json; this.maxClarifications = maxClarifications; this.conflictGuard = conflictGuard;
    }
    private final com.boc.nl2sql.nl2sql.application.DisplayConflictGuard conflictGuard;

    @Async("queryExecutor")
    public void processAsync(String taskId, CurrentUser user, String requestId) {
        QueryTaskEntity task = taskMapper.selectById(taskId);
        if (task == null || !QueryStatus.RECEIVED.name().equals(task.getStatusCode())) return;
        org.slf4j.MDC.put("taskId",taskId);org.slf4j.MDC.put("requestId",requestId);
        try (var context=com.boc.nl2sql.model.ModelCallContext.open(()->states.active(taskId),task::getResolvedCustomerId)) {
            boolean confirmed = Boolean.TRUE.equals(task.getConfirmed()) && task.getSqlText() != null;
            // 第一次版本提交同时认领任务，同一个已确认请求只能执行一次。
            stage(task, QueryStatus.INTENT_ANALYZING, 20, confirmed ? "正在恢复已确认的查询计划" : "正在识别业务意图和查询条件");
            if (confirmed) {
                PlannedQuery saved = storedPlan(task);
                validate(saved, user, task.getInterpretationSource());
                if(task.getResolvedCustomerId()!=null)scope.validateCustomer(saved.sql(),saved.parameters(),task.getResolvedCustomerId());
                executePlan(task, saved, user, requestId);
                return;
            }
            java.util.List<String> customerList=task.getCustomerIdsJson()==null?null:
                    java.util.Arrays.asList(json.readValue(task.getCustomerIdsJson(),String[].class));
            if(customerList!=null){
                // 名单已由服务端核验，模型必须使用 IN 条件表达，不再执行单客定位。
                task.setDisplayQuery(task.getQueryText().replaceAll("(?i)C[0-9]{8}(\s*[，,、]?\s*)+"," ").trim()
                        +"（@客户名单 "+customerList.size()+" 人）");
            }else if(customers!=null){
                var identity=customers.inspect(task,user);
                if(identity.isPresent()){ask(task,identity.get(),requestId);return;}
                var ctx=task.getContextJson()==null?null:json.readValue(task.getContextJson(),com.boc.nl2sql.conversation.domain.ConversationContext.class);
                if(followups.followup(task.getMergedQueryText()) && !followups.selfContained(task.getMergedQueryText()) && task.getResolvedCustomerId()==null && (ctx==null||ctx.query().isBlank())){
                    ask(task,new com.boc.nl2sql.nl2sql.domain.ClarificationQuestion(UUID.randomUUID().toString(),"FOLLOWUP_CONTEXT","本会话还没有可以承接的查询，请完整描述本次查询对象和指标。",List.of(),Map.of()),requestId);return;
                }
                String compareTag="";
                if(task.getCustomerIdsJson()!=null){
                    int n=json.readValue(task.getCustomerIdsJson(),String[].class).length;
                    compareTag="（已确认对比客户 "+n+" 人）";
                }
                task.setDisplayQuery(task.getQueryText()+(task.getResolvedCustomerId()==null?"":task.getCustomerIdsJson()!=null?compareTag:"（已确认客户 "+task.getResolvedCustomerId()+"）"));
                var direct=customerPlanner.plan(task.getMergedQueryText(),task.getResolvedCustomerId(),user);
                if(direct.isPresent()){
                    task.setIntentCode("CUSTOMER_FILTER");task.setInterpretationSource("RULE");task.setInterpretationConfidence(1.0);
                    stage(task,QueryStatus.SQL_GENERATING,45,"客户已确认，正在生成查询计划");
                    var chosen=requestedDisplay(task,direct.get());
                    runPlan(task,chosen,user,requestId,0);return;
                }
            }
            String modelText=modelText(task);
            QueryInterpretation interpretation = task.getThinkingEnabled()==null
                    ?modelGateway.interpret(modelText, user, () -> states.active(taskId))
                    :modelGateway.interpret(modelText,user,()->states.active(taskId),task.getThinkingEnabled());
            states.ensureActive(taskId);
            task.setIntentCode(interpretation.semantic().intent().name());
            task.setInterpretationSource(interpretation.source());
            task.setInterpretationConfidence(interpretation.confidence());
            var question = interpretation.clarification() == null
                    ? completeness.validate(interpretation.semantic(), interpretation.hasGeneratedSql())
                    : java.util.Optional.of(interpretation.clarification());
            if (question.isPresent()) {
                ask(task,question.get(),requestId);
                return;
            }
            // 显式要求饼图但问题表达时间趋势时，用确定性规则澄清口径，不依赖模型裁量。
            if (interpretation.hasGeneratedSql() && modelGenerated(task.getInterpretationSource())) {
                var conflict=conflictGuard.check(task.getQueryText(),task.getPreferredDisplay());
                if(conflict.isPresent()){ask(task,conflict.get(),requestId);return;}
            }
            stage(task, QueryStatus.SQL_GENERATING, 45, "正在生成受控查询计划");
            PlannedQuery plan = interpretation.hasGeneratedSql() ? fromInterpretation(interpretation)
                    : planner.plan(interpretation.semantic(), user);
            plan=requestedDisplay(task,plan);
            runPlan(task,plan,user,requestId,0);
        } catch (TaskStateStore.TaskChangedException ignored) {
            // 取消或其他请求已经赢得状态提交，旧工作不得继续更新/执行。
        } catch (QueryTerminatedException stopped) {
            finishStopped(task, stopped, requestId);
        } catch (Exception exception) {
            log.warn("任务失败：taskId={}, exceptionType={}, reason={}",taskId,exception.getClass().getSimpleName(),safeMessage(exception));
            fail(task, safeMessage(exception), requestId);
        } finally {org.slf4j.MDC.remove("taskId");org.slf4j.MDC.remove("requestId");}
    }

    private PlannedQuery requestedDisplay(QueryTaskEntity task,PlannedQuery plan){
        String requested=task.getPreferredDisplay();
        return requested==null || "AUTO".equals(requested)?plan:new PlannedQuery(plan.sql(),plan.parameters(),requested,plan.title(),plan.risk(),plan.columnHints());
    }

    private PlannedQuery fromInterpretation(QueryInterpretation interpretation) {
        return new PlannedQuery(interpretation.generatedSql(), Map.of(), interpretation.preferredDisplay(),
                interpretation.title(), QueryRisk.low(), interpretation.columnHints());
    }

    @SuppressWarnings("unchecked")
    private PlannedQuery storedPlan(QueryTaskEntity task) {
        Map<String, Object> parameters = task.getSqlParametersJson() == null ? Map.of()
                : json.readValue(task.getSqlParametersJson(), Map.class);
        var hints = task.getColumnHintsJson() == null ? List.<ResultColumnHint>of()
                : List.of(json.readValue(task.getColumnHintsJson(), ResultColumnHint[].class));
        return new PlannedQuery(task.getSqlText(), parameters, task.getPreferredDisplay(),
                "已确认查询结果", QueryRisk.low(), hints);
    }

    /** 每条新SQL（包括修复/降级）都重新校验和确认，绝不沿用旧SQL的确认授权。 */
    private boolean acceptPlan(QueryTaskEntity task, PlannedQuery plan, CurrentUser user, String requestId) {
        states.ensureActive(task.getTaskId());
        review(task,requestId,"GENERATED",plan,null);
        try {
            validate(plan, user, task.getInterpretationSource());
            if(task.getResolvedCustomerId()!=null)scope.validateCustomer(plan.sql(),plan.parameters(),task.getResolvedCustomerId());
            else if(task.getCustomerIdsJson()!=null)scope.validateCustomers(plan.sql(),plan.parameters(),
                    java.util.Arrays.asList(json.readValue(task.getCustomerIdsJson(),String[].class)));
        } catch(RuntimeException rejected){review(task,requestId,"REJECTED",plan,rejected instanceof BusinessException b?b.code()+" "+b.getMessage():"VALIDATION_ERROR");throw rejected;}
        QueryRisk risk = risks.assess(plan);
        task.setSqlText(plan.sql());
        task.setSqlParametersJson(json.writeValueAsString(plan.parameters()));
        task.setColumnHintsJson(json.writeValueAsString(plan.columnHints()));
        task.setPreferredDisplay(plan.resultType());
        task.setRiskJson(json.writeValueAsString(risk));
        task.setQuestionJson(null);
        task.setConfirmed(false);
        task.setConfirmationToken(null);
        stage(task, QueryStatus.VALIDATING, 60, "正在执行只读、权限和对象白名单校验");
        if (risk.requiresConfirmation()) {
            task.setConfirmationToken(UUID.randomUUID().toString().replace("-", ""));
            stage(task, QueryStatus.CONFIRMING, 65, "当前SQL需要确认后执行");
            fact(QualityEventType.QUERY_CONFIRMING,task,user,requestId,"NEW_PLAN",details("sql",plan.sql(),"risk",risk));
            return false;
        }
        return true;
    }

    private void validate(PlannedQuery plan, CurrentUser user, String source) {
        safety.validate(plan.sql());
        if (modelGenerated(source)) scope.validate(plan.sql(), user);
    }

    /** 只要计划来自模型自由生成（无论哪个适配器）都必须做账号范围证明；规则模板天然受限，无需证明。 */
    private boolean modelGenerated(String source) {
        return source != null && !"RULE".equals(source) && !"TEMPLATE_FALLBACK".equals(source);
    }

    /** 校验失败也可以消耗同一个两次修复额度；每个候选仍完整重跑安全与风险检查。 */
    private void runPlan(QueryTaskEntity task, PlannedQuery plan, CurrentUser user, String requestId, int repairAttempt) {
        try {
            boolean accepted=acceptPlan(task,plan,user,requestId);
            if(repairAttempt>0)repairApplied(task,repairAttempt);
            if(accepted)executePlan(task,plan,user,requestId);
        } catch (BusinessException invalid) {
            if(repairAttempt>0)repairRejected(task,repairAttempt,invalid.code()+" "+invalid.getMessage());
            if(!repairableValidation(task,invalid))throw invalid;
            RepairCandidate candidate=requestRepair(task,plan,user,requestId,"VALIDATION",
                    invalid.code()+" "+invalid.getMessage());
            if(candidate==null){fallback(task,user,requestId,"SQL未通过安全校验，且有限修复未形成可用计划。");return;}
            runPlan(task,candidate.plan(),user,requestId,candidate.attempt());
        }
    }

    private boolean repairableValidation(QueryTaskEntity task,BusinessException error){
        return modelGenerated(task.getInterpretationSource())
                && Set.of(403102,403104,403105,422101,422102,422103,422104).contains(error.code());
    }

    private void executePlan(QueryTaskEntity task, PlannedQuery plan, CurrentUser user, String requestId) {
        stage(task, QueryStatus.EXECUTING, 75, "正在查询已授权的营销数据");
        PagedQueryRows page;
        List<Map<String, Object>> rows;
        try {
            review(task,requestId,"EXECUTING",plan,null);
            QueryPage requested = new QueryPage(task.getPageNo()==null?1:task.getPageNo(),
                    task.getPageSize()==null?100:task.getPageSize(),task.getPageOffset()==null?0:task.getPageOffset());
            page = execution.execute(task.getTaskId(), plan, requested, () -> states.active(task.getTaskId()));
            rows = page.rows();
            review(task,requestId,"EXECUTED",plan,"rows="+rows.size()+",total="+page.total());
        } catch(QueryTerminatedException stopped){
            review(task,requestId,stopped.timedOut()?"TIMED_OUT":"CANCELLED",plan,null);throw stopped;
        } catch (DataAccessException error) {
            var reason=SqlFailureClassifier.repairReason(error);
            review(task,requestId,"SQL_ERROR",plan,reason.orElse("DATABASE_ERROR"));
            states.ensureActive(task.getTaskId());
            if (!modelGenerated(task.getInterpretationSource()) || reason.isEmpty()) {
                if (task.getFallbackJson() != null) {finishNoData(task,"固定模板未能完成查询，请调整条件或稍后重试。",requestId);return;}
                throw error;
            }
            fact(QualityEventType.QUERY_SQL_ERROR,task,user,requestId,reason.get(),details("sql",plan.sql(),"reason",reason.get()),true);
            RepairCandidate candidate=requestRepair(task,plan,user,requestId,"EXECUTION",reason.get());
            if(candidate==null){fallback(task,user,requestId,"SQL已达到两次修复上限或模型未形成可执行计划。");return;}
            runPlan(task,candidate.plan(),user,requestId,candidate.attempt());
            return;
        }

        if(modelGenerated(task.getInterpretationSource())&&!rows.isEmpty()){
            stage(task,QueryStatus.RESULT_REVIEWING,85,"正在复核结果结构与原问题是否一致");
            try{
                var verdict=modelGateway.reviewResult(modelText(task),user,plan.sql(),resultSummary(plan,rows),Boolean.TRUE.equals(task.getThinkingEnabled()));
                review(task,requestId,verdict.aligned()?"RESULT_ALIGNED":"RESULT_MISMATCH",plan,verdict.reason());
                if(!verdict.aligned()){
                    fact(QualityEventType.QUERY_RESULT_MISMATCH,task,user,requestId,shorten(verdict.reason()),
                            details("sql",plan.sql(),"reason",verdict.reason()),true);
                    RepairCandidate candidate=requestRepair(task,plan,user,requestId,"RESULT_REVIEW",verdict.reason());
                    if(candidate==null){fallback(task,user,requestId,"结果结构复核未通过，且已达到两次修复上限或模型未形成可用计划。");return;}
                    runPlan(task,candidate.plan(),user,requestId,candidate.attempt());return;
                }
            }catch(QueryTerminatedException stopped){throw stopped;
            }catch(BusinessException unavailable){
                // 复核服务不可用不丢弃已经通过权限校验并成功执行的结果；记录后继续组装。
                review(task,requestId,"RESULT_REVIEW_UNAVAILABLE",plan,unavailable.code()+" "+unavailable.getMessage());
                fact(QualityEventType.QUERY_RESULT_REVIEW_UNAVAILABLE,task,user,requestId,String.valueOf(unavailable.code()),
                        details("code",unavailable.code(),"message",unavailable.getMessage()));
            }
        }
        stage(task, QueryStatus.PACKAGING, 90, "正在整理全部指标、图表与数据分析");
        QueryResult result = assembler.assemble(plan, page, task.getInterpretationSource(),
                task.getInterpretationConfidence() == null ? 1.0 : task.getInterpretationConfidence());
        FallbackInfo info = fallbackInfo(task);
        if (info != null) result = result.withFallback(new FallbackInfo(info.reason(), info.templateId(), true, info.suggestions()));
        finish(task, result, info == null ? QueryStatus.SUCCESS : QueryStatus.DEGRADED, requestId);
    }

    private RepairCandidate requestRepair(QueryTaskEntity task,PlannedQuery failed,CurrentUser user,String requestId,
                                          String trigger,String failure){
        if(!modelGenerated(task.getInterpretationSource())||task.getRepairAttempts()>=MAX_REPAIRS)return null;
        int attempt=task.getRepairAttempts()+1;task.setRepairAttempts(attempt);
        String why=switch(trigger){case "VALIDATION"->"候选SQL未通过AST、对象或数据范围校验";case "EXECUTION"->"已校验SQL触发可修复的MySQL表达错误";default->"执行结果结构与原始问题明显不一致";};
        repairFacts.started(task.getTaskId(),user.userId(),attempt,trigger,failed.sql(),failure,why);
        stage(task,QueryStatus.REPAIRING,70,"正在安全修复SQL（"+attempt+"/"+MAX_REPAIRS+"）");
        try{
            states.ensureActive(task.getTaskId());
            QueryInterpretation repaired=task.getThinkingEnabled()==null?modelGateway.repair(modelText(task),user,failed.sql(),failure)
                    :modelGateway.repair(modelText(task),user,failed.sql(),failure,task.getThinkingEnabled());
            states.ensureActive(task.getTaskId());
            if(!repaired.hasGeneratedSql()||repaired.clarification()!=null){
                repairFacts.modelFailed(task.getTaskId(),user.userId(),attempt,"模型未返回完整只读SQL");return null;
            }
            PlannedQuery candidate=requestedDisplay(task,fromInterpretation(repaired));
            repairFacts.generated(task.getTaskId(),user.userId(),attempt,candidate.sql());
            return new RepairCandidate(candidate,attempt);
        }catch(QueryTerminatedException stopped){throw stopped;
        }catch(TaskStateStore.TaskChangedException changed){throw changed;
        }catch(BusinessException failedCall){
            repairFacts.modelFailed(task.getTaskId(),user.userId(),attempt,failedCall.code()+" "+failedCall.getMessage());
            return null;
        }catch(RuntimeException unexpected){
            repairFacts.modelFailed(task.getTaskId(),user.userId(),attempt,"模型修复调用异常");
            log.warn("SQL修复调用异常：taskId={}, attempt={}, exceptionType={}",task.getTaskId(),attempt,
                    unexpected.getClass().getSimpleName());
            return null;
        }
    }

    private Map<String,Object> resultSummary(PlannedQuery plan,List<Map<String,Object>> rows){
        var keys=new LinkedHashSet<String>();rows.forEach(row->keys.addAll(row.keySet()));
        var columns=new ArrayList<Map<String,Object>>();
        for(String key:keys){Object sample=null;for(var row:rows)if(row.get(key)!=null){sample=row.get(key);break;}
            columns.add(Map.of("name",key,"type",valueType(sample)));}
        var hints=plan.columnHints().stream().map(h->Map.<String,Object>of("key",h.key()==null?"":h.key(),"role",h.role()==null?"":h.role(),
                "aggregation",h.aggregation()==null?"":h.aggregation(),"unit",h.unit()==null?"":h.unit())).toList();
        return Map.of("returned_row_count",rows.size(),"columns",columns,"declared_columns",hints,
                "requested_display",plan.resultType()==null?"AUTO":plan.resultType());
    }
    private String valueType(Object value){
        if(value==null)return "UNKNOWN";if(value instanceof Number)return "NUMBER";if(value instanceof Boolean)return "BOOLEAN";
        if(value instanceof java.time.temporal.TemporalAccessor||value instanceof java.util.Date)return "DATE_TIME";return "TEXT";
    }
    private void repairApplied(QueryTaskEntity task,int attempt){repairFacts.applied(task.getTaskId(),task.getUserId(),attempt);}
    private void repairRejected(QueryTaskEntity task,int attempt,String reason){repairFacts.rejected(task.getTaskId(),task.getUserId(),attempt,reason);}
    private String shorten(String text){String value=text==null?"":text.strip();return value.substring(0,Math.min(500,value.length()));}
    private record RepairCandidate(PlannedQuery plan,int attempt){}

    private void fallback(QueryTaskEntity task, CurrentUser user, String requestId, String reason) {
        stage(task, QueryStatus.FALLING_BACK, 72, "正在匹配可完整回答问题的固定模板");
        var template = task.getResolvedCustomerId()!=null?java.util.Optional.<FallbackPlanner.Template>empty():fallbackPlanner.plan(task.getMergedQueryText(), user);
        FallbackInfo info = new FallbackInfo(reason, template.map(FallbackPlanner.Template::id).orElse(null),
                false, List.of("可简化为按年龄段或性别统计客户数量与当前平均资产，并明确开户时间范围。"));
        task.setFallbackJson(json.writeValueAsString(info));
        states.save(task);
        fact(QualityEventType.QUERY_FALLBACK,task,user,requestId,
                template.map(FallbackPlanner.Template::id).orElse("NO_MATCH"),
                details("reason",reason,"template_id",template.map(FallbackPlanner.Template::id).orElse("NO_MATCH")),true);
        if (template.isEmpty()) {
            finishNoData(task, reason + " 没有能完整覆盖原问题的固定模板，未返回替代口径的数据。", requestId);
            return;
        }
        task.setInterpretationSource("TEMPLATE_FALLBACK");
        task.setInterpretationConfidence(1.0);
        runPlan(task,template.get().query(),user,requestId,0);
    }

    private void finishNoData(QueryTaskEntity task, String reason, String requestId) {
        FallbackInfo previous = fallbackInfo(task);
        FallbackInfo info = new FallbackInfo(reason, previous == null ? null : previous.templateId(), false,
                previous == null ? List.of("请明确指标和时间口径后重新查询。") : previous.suggestions());
        QueryResult result = assembler.assemble(new PlannedQuery("", Map.of(), "TABLE", "未获得可用查询数据", false),
                List.of(), "FALLBACK", 0.0).withFallback(info);
        finish(task, result, QueryStatus.DEGRADED, requestId);
    }

    private FallbackInfo fallbackInfo(QueryTaskEntity task) {
        return task.getFallbackJson() == null ? null : json.readValue(task.getFallbackJson(), FallbackInfo.class);
    }

    private void finish(QueryTaskEntity task, QueryResult result, QueryStatus status, String requestId) {
        if (result.fallback() != null) task.setFallbackJson(json.writeValueAsString(result.fallback()));
        task.setResultJson(json.writeValueAsString(result)); task.setErrorMessage(null);
        stage(task, status, 100, result.summary());
        recordTerminal(task, result.summary(), requestId);
    }

    private void stage(QueryTaskEntity task, QueryStatus status, int progress, String message) {
        task.setStatusCode(status.name()); task.setProgress(progress); task.setStageMessage(message);
        states.save(task);
    }

    private void finishStopped(QueryTaskEntity task, QueryTerminatedException stopped, String requestId) {
        task.setErrorMessage(stopped.getMessage());
        task.setStatusCode(stopped.timedOut() ? "TIMED_OUT" : "CANCELLED");
        task.setProgress(100); task.setStageMessage(stopped.getMessage());
        if (states.trySave(task)) recordTerminal(task, stopped.getMessage(), requestId);
    }

    private void fail(QueryTaskEntity task, String message, String requestId) {
        task.setErrorMessage(message); task.setStatusCode("FAILED"); task.setProgress(100);
        task.setStageMessage("查询未完成");
        if (states.trySave(task)) recordTerminal(task, message, requestId);
    }

    private void recordTerminal(QueryTaskEntity task, String summary, String requestId) {
        try {
            fact(QualityEventType.valueOf("QUERY_" + task.getStatusCode()),task,null,requestId,summary,
                    terminalDetails(task),Set.of("FAILED","TIMED_OUT","DEGRADED").contains(task.getStatusCode()));
        } catch (RuntimeException error) {
            log.error("任务终态附属记录失败：taskId={}, status={}", task.getTaskId(), task.getStatusCode());
        }
    }

    private String safeMessage(Exception error) {
        if (error instanceof BusinessException business) return business.getMessage();
        if (error instanceof DataAccessException) return "分析数据库查询失败，请调整条件或稍后重试";
        return "查询执行失败，请稍后重试";
    }
    private void ask(QueryTaskEntity task,com.boc.nl2sql.nl2sql.domain.ClarificationQuestion question,String requestId){
        if(task.getClarificationRound()>=maxClarifications){fail(task,"补充次数已达上限，仍需明确："+question.prompt(),requestId);return;}
        task.setQuestionJson(json.writeValueAsString(question));stage(task,QueryStatus.ASKING,30,question.prompt());
        fact(QualityEventType.QUERY_ASKING,task,null,requestId,question.type(),Map.of("question",question));
    }
    private String modelText(QueryTaskEntity task){
        if(task.getCustomerIdsJson()!=null){
            String[] ids=json.readValue(task.getCustomerIdsJson(),String[].class);
            String idList=String.join("、",ids);
            return task.getMergedQueryText()+"\n服务端已核验客户名单（共"+ids.length+"人）："+idList
                    +"。生成的SQL必须使用 customer_id IN (上述全部编号) 表达客户范围，不得增删或替换编号，每个客户来源都要保留该IN条件。";
        }
        return task.getMergedQueryText()+(task.getResolvedCustomerId()==null?"":"\n服务端已确认客户编号："+task.getResolvedCustomerId()+"。每个客户来源必须使用此 customer_id 限制，不可更换客户或扩大范围。若用户明确要求统计超出该客户的范围，不得编造数据，应在clarification_question中建议用户新建会话后单独提问。");
    }
    private void review(QueryTaskEntity task,String request,String phase,PlannedQuery plan,String result){
        sqlFacts.record(task.getTaskId(),request,task.getUserId(),task.getInterpretationSource(),phase,
                plan.sql(),plan.parameters(),result);
    }

    private void fact(QualityEventType type,QueryTaskEntity task,CurrentUser user,String requestId,
                      String summary,Map<String,?> details){fact(type,task,user,requestId,summary,details,false);}
    private void fact(QualityEventType type,QueryTaskEntity task,CurrentUser user,String requestId,
                      String summary,Map<String,?> details,boolean candidate){
        qualityFacts.publish(QualityFact.builder(type,"CONVERSATION").requestId(requestId)
                .sessionId(task.getSessionId()).taskId(task.getTaskId())
                .userId(user==null?task.getUserId():user.userId()).summary(summary)
                .details(details).evaluationCandidate(candidate).build());
    }
    private Map<String,Object> terminalDetails(QueryTaskEntity task){
        var details=new LinkedHashMap<String,Object>();
        details.put("status",task.getStatusCode());details.put("query_text",task.getQueryText());
        details.put("merged_query_text",task.getMergedQueryText());details.put("sql",task.getSqlText());
        details.put("sql_parameters_json",task.getSqlParametersJson());details.put("result_json",task.getResultJson());
        details.put("error_message",task.getErrorMessage());details.put("interpretation_source",task.getInterpretationSource());
        details.values().removeIf(Objects::isNull);return details;
    }
    private Map<String,Object> details(Object... pairs){
        var result=new LinkedHashMap<String,Object>();
        for(int i=0;i+1<pairs.length;i+=2)if(pairs[i]!=null&&pairs[i+1]!=null)result.put(String.valueOf(pairs[i]),pairs[i+1]);
        return result;
    }
}
