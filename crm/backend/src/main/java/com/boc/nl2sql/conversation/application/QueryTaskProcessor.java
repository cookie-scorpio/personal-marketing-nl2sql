package com.boc.nl2sql.conversation.application;

import com.boc.nl2sql.audit.AuditService;
import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.conversation.domain.QueryStatus;
import com.boc.nl2sql.conversation.infrastructure.QueryTaskEntity;
import com.boc.nl2sql.conversation.infrastructure.QueryTaskMapper;
import com.boc.nl2sql.execution.QueryExecutionGateway;
import com.boc.nl2sql.execution.QueryTerminatedException;
import com.boc.nl2sql.execution.application.*;
import com.boc.nl2sql.execution.domain.*;
import com.boc.nl2sql.history.application.HistoryService;
import com.boc.nl2sql.model.ModelGateway;
import com.boc.nl2sql.model.QueryInterpretation;
import com.boc.nl2sql.nl2sql.application.CompletenessValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    private final HistoryService history;
    private final AuditService audit;
    private final ObjectMapper json;
    private final int maxClarifications;
    @org.springframework.beans.factory.annotation.Autowired private CustomerResolver customers;
    @org.springframework.beans.factory.annotation.Autowired private FollowupResolver followups;
    @org.springframework.beans.factory.annotation.Autowired private CustomerQueryPlanner customerPlanner;
    @org.springframework.beans.factory.annotation.Autowired private SqlReviewLog sqlLog;

    public QueryTaskProcessor(QueryTaskMapper taskMapper, TaskStateStore states, ModelGateway modelGateway,
            CompletenessValidator completeness, SqlPlanner planner, SqlRiskEvaluator risks,
            SqlSafetyValidator safety, GeneratedSqlScopeValidator scope, QueryExecutionGateway execution,
            ResultAssembler assembler, FallbackPlanner fallbackPlanner, HistoryService history,
            AuditService audit, ObjectMapper json,
            @Value("${app.query.max-clarification-rounds:5}") int maxClarifications) {
        this.taskMapper = taskMapper; this.states = states; this.modelGateway = modelGateway;
        this.completeness = completeness; this.planner = planner; this.risks = risks; this.safety = safety;
        this.scope = scope; this.execution = execution; this.assembler = assembler;
        this.fallbackPlanner = fallbackPlanner; this.history = history; this.audit = audit;
        this.json = json; this.maxClarifications = maxClarifications;
    }

    @Async("queryExecutor")
    public void processAsync(String taskId, CurrentUser user, String requestId) {
        QueryTaskEntity task = taskMapper.selectById(taskId);
        if (task == null || !QueryStatus.RECEIVED.name().equals(task.getStatusCode())) return;
        try {
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
            if(customers!=null){
                var identity=customers.inspect(task,user);
                if(identity.isPresent()){ask(task,identity.get(),requestId);return;}
                var ctx=task.getContextJson()==null?null:json.readValue(task.getContextJson(),com.boc.nl2sql.conversation.domain.ConversationContext.class);
                if(followups.followup(task.getMergedQueryText()) && task.getResolvedCustomerId()==null && (ctx==null||ctx.query().isBlank())){
                    ask(task,new com.boc.nl2sql.nl2sql.domain.ClarificationQuestion(UUID.randomUUID().toString(),"FOLLOWUP_CONTEXT","本会话还没有可以承接的查询，请完整描述本次查询对象和指标。",List.of(),Map.of()),requestId);return;
                }
                task.setDisplayQuery(task.getMergedQueryText()+(task.getResolvedCustomerId()==null?"":"；客户编号："+task.getResolvedCustomerId()));
                var direct=customerPlanner.plan(task.getMergedQueryText(),task.getResolvedCustomerId(),user);
                if(direct.isPresent()){
                    task.setIntentCode("CUSTOMER_FILTER");task.setInterpretationSource("RULE");task.setInterpretationConfidence(1.0);
                    stage(task,QueryStatus.SQL_GENERATING,45,"客户已确认，正在生成查询计划");
                    if(acceptPlan(task,direct.get(),user,requestId))executePlan(task,direct.get(),user,requestId);return;
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
            stage(task, QueryStatus.SQL_GENERATING, 45, "正在生成受控查询计划");
            PlannedQuery plan = interpretation.hasGeneratedSql() ? fromInterpretation(interpretation)
                    : planner.plan(interpretation.semantic(), user);
            if (acceptPlan(task, plan, user, requestId)) executePlan(task, plan, user, requestId);
        } catch (TaskStateStore.TaskChangedException ignored) {
            // 取消或其他请求已经赢得状态提交，旧工作不得继续更新/执行。
        } catch (QueryTerminatedException stopped) {
            finishStopped(task, stopped, requestId);
        } catch (Exception exception) {
            fail(task, safeMessage(exception), requestId);
        }
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
        } catch(RuntimeException rejected){review(task,requestId,"REJECTED",plan,rejected instanceof BusinessException b?Integer.toString(b.code()):"VALIDATION_ERROR");throw rejected;}
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
            audit.record(requestId, task.getTaskId(), user.userId(), "QUERY_CONFIRMING", "NEW_PLAN");
            return false;
        }
        return true;
    }

    private void validate(PlannedQuery plan, CurrentUser user, String source) {
        safety.validate(plan.sql());
        if ("DEEPSEEK".equals(source)) scope.validate(plan.sql(), user);
    }

    private void executePlan(QueryTaskEntity task, PlannedQuery plan, CurrentUser user, String requestId) {
        while (true) {
            stage(task, QueryStatus.EXECUTING, 75, "正在查询已授权的营销数据");
            List<Map<String, Object>> rows;
            try {
                review(task,requestId,"EXECUTING",plan,null);
                rows = execution.execute(task.getTaskId(), plan, () -> states.active(task.getTaskId()));
                review(task,requestId,"EXECUTED",plan,"rows="+rows.size());
            } catch(QueryTerminatedException stopped){
                review(task,requestId,stopped.timedOut()?"TIMED_OUT":"CANCELLED",plan,null);throw stopped;
            } catch (DataAccessException error) {
                review(task,requestId,"SQL_ERROR",plan,SqlFailureClassifier.repairReason(error).orElse("DATABASE_ERROR"));
                states.ensureActive(task.getTaskId());
                var reason = SqlFailureClassifier.repairReason(error);
                if (!"DEEPSEEK".equals(task.getInterpretationSource()) || reason.isEmpty()) {
                    if (task.getFallbackJson() != null) {
                        finishNoData(task, "固定模板未能完成查询，请调整条件或稍后重试。", requestId);
                        return;
                    }
                    throw error;
                }
                audit.record(requestId, task.getTaskId(), user.userId(), "QUERY_SQL_ERROR", reason.get());
                if (task.getRepairAttempts() >= MAX_REPAIRS) {
                    fallback(task, user, requestId, "SQL已修复两次，仍无法执行；已停止模型调用。");
                    return;
                }
                task.setRepairAttempts(task.getRepairAttempts() + 1);
                stage(task, QueryStatus.REPAIRING, 70, "正在修复SQL（" + task.getRepairAttempts() + "/2）");
                audit.record(requestId, task.getTaskId(), user.userId(), "QUERY_REPAIR_STARTED",
                        "attempt=" + task.getRepairAttempts());
                try {
                    states.ensureActive(task.getTaskId());
                    QueryInterpretation repaired = task.getThinkingEnabled()==null?modelGateway.repair(modelText(task), user, plan.sql(), reason.get())
                            :modelGateway.repair(modelText(task),user,plan.sql(),reason.get(),task.getThinkingEnabled());
                    states.ensureActive(task.getTaskId());
                    if (!repaired.hasGeneratedSql() || repaired.clarification() != null) {
                        fallback(task, user, requestId, "模型修复未形成可执行计划，已停止模型调用。");
                        return;
                    }
                    plan = fromInterpretation(repaired);
                    if (!acceptPlan(task, plan, user, requestId)) return;
                } catch (BusinessException invalidRepair) {
                    fallback(task, user, requestId, "SQL修复响应不可用或未通过安全校验，已停止模型调用。");
                    return;
                }
                continue;
            }
            stage(task, QueryStatus.PACKAGING, 90, "正在整理全部指标、图表与数据分析");
            QueryResult result = assembler.assemble(plan, rows, task.getInterpretationSource(),
                    task.getInterpretationConfidence() == null ? 1.0 : task.getInterpretationConfidence());
            FallbackInfo info = fallbackInfo(task);
            if (info != null) result = result.withFallback(new FallbackInfo(info.reason(), info.templateId(), true, info.suggestions()));
            finish(task, result, info == null ? QueryStatus.SUCCESS : QueryStatus.DEGRADED, requestId);
            return;
        }
    }

    private void fallback(QueryTaskEntity task, CurrentUser user, String requestId, String reason) {
        stage(task, QueryStatus.FALLING_BACK, 72, "正在匹配可完整回答问题的固定模板");
        var template = task.getResolvedCustomerId()!=null?java.util.Optional.<FallbackPlanner.Template>empty():fallbackPlanner.plan(task.getMergedQueryText(), user);
        FallbackInfo info = new FallbackInfo(reason, template.map(FallbackPlanner.Template::id).orElse(null),
                false, List.of("可简化为按年龄段或性别统计客户数量与当前平均资产，并明确开户时间范围。"));
        task.setFallbackJson(json.writeValueAsString(info));
        states.save(task);
        audit.record(requestId, task.getTaskId(), user.userId(), "QUERY_FALLBACK",
                template.map(FallbackPlanner.Template::id).orElse("NO_MATCH"));
        if (template.isEmpty()) {
            finishNoData(task, reason + " 没有能完整覆盖原问题的固定模板，未返回替代口径的数据。", requestId);
            return;
        }
        task.setInterpretationSource("TEMPLATE_FALLBACK");
        task.setInterpretationConfidence(1.0);
        if (acceptPlan(task, template.get().query(), user, requestId)) executePlan(task, template.get().query(), user, requestId);
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
            history.save(task.getTaskId(), task.getUserId(), task.getQueryText(), task.getIntentCode(),
                    task.getStatusCode(), task.getSqlText(), summary);
            audit.record(requestId, task.getTaskId(), task.getUserId(), "QUERY_" + task.getStatusCode(), summary);
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
        task.setQuestionJson(json.writeValueAsString(question));stage(task,QueryStatus.ASKING,30,question.prompt());audit.record(requestId,task.getTaskId(),task.getUserId(),"QUERY_ASKING",question.type());
    }
    private String modelText(QueryTaskEntity task){return task.getMergedQueryText()+(task.getResolvedCustomerId()==null?"":"\n服务端已确认客户编号："+task.getResolvedCustomerId()+"。每个客户来源必须使用此 customer_id 限制，不可更换客户或扩大范围。");}
    private void review(QueryTaskEntity task,String request,String phase,PlannedQuery plan,String result){if(sqlLog!=null)sqlLog.record(task.getTaskId(),request,task.getInterpretationSource(),phase,plan,result);}
}
