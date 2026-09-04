package com.boc.nl2sql.service.conversation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.dao.conversation.QueryTaskMapper;
import com.boc.nl2sql.dao.execution.QueryExecutionGateway;
import com.boc.nl2sql.domain.authorization.CurrentUser;
import com.boc.nl2sql.domain.conversation.QueryTaskEntity;
import com.boc.nl2sql.domain.execution.FallbackInfo;
import com.boc.nl2sql.domain.execution.PlannedQuery;
import com.boc.nl2sql.domain.execution.QueryPage;
import com.boc.nl2sql.domain.execution.QueryResult;
import com.boc.nl2sql.domain.execution.QueryRisk;
import com.boc.nl2sql.domain.execution.ResultColumnHint;
import com.boc.nl2sql.service.authorization.AuthorizationCenter;
import com.boc.nl2sql.service.execution.GeneratedSqlScopeValidator;
import com.boc.nl2sql.service.execution.ResultAssembler;
import com.boc.nl2sql.service.execution.SqlSafetyValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 按页重新读取已完成查询的结果。
 *
 * <p>首次执行只持久化当前页和已经通过校验的查询计划，不缓存整批业务数据。翻页时本服务复用该计划，
 * 重新检查当前登录身份、资源归属、SQL 安全性和数据范围，再到数据库读取目标页。这样既能访问第 101 条
 * 之后的数据，也不会把全部结果一次性送到浏览器或绕过账号权限变化。</p>
 */
@Service
public class QueryResultPageService {
    private final QueryTaskMapper taskMapper;
    private final AuthorizationCenter authorization;
    private final ConversationStore conversations;
    private final SqlSafetyValidator safety;
    private final GeneratedSqlScopeValidator scope;
    private final QueryExecutionGateway execution;
    private final ResultAssembler assembler;
    private final ObjectMapper json;
    private final int maxPageSize;
    private final int maxResultPages;
    private final long maxOffset;

    public QueryResultPageService(QueryTaskMapper taskMapper,
                                  AuthorizationCenter authorization,
                                  ConversationStore conversations,
                                  SqlSafetyValidator safety,
                                  GeneratedSqlScopeValidator scope,
                                  QueryExecutionGateway execution,
                                  ResultAssembler assembler,
                                  ObjectMapper json,
                                  @Value("${app.query.max-page-size:500}") int maxPageSize,
                                  @Value("${app.query.max-result-pages:50}") int maxResultPages,
                                  @Value("${app.query.max-offset:100000}") long maxOffset) {
        if (maxPageSize < 1 || maxResultPages < 1 || maxOffset < 0) {
            throw new IllegalArgumentException("查询分页上限配置无效");
        }
        this.taskMapper = taskMapper;
        this.authorization = authorization;
        this.conversations = conversations;
        this.safety = safety;
        this.scope = scope;
        this.execution = execution;
        this.assembler = assembler;
        this.json = json;
        this.maxPageSize = maxPageSize;
        this.maxResultPages = maxResultPages;
        this.maxOffset = maxOffset;
    }

    /**
     * 返回指定结果页。页码从 1 开始；超出当前总页数时返回空行和最新总数，由前端据此回到有效页。
     */
    public QueryResult page(String taskId, int pageNo, int pageSize, CurrentUser user) {
        authorization.requireBusinessDataAccess(user);
        QueryPage requestedPage = requestedPage(pageNo, pageSize);
        QueryTaskEntity task = ownedCompletedTask(taskId, user);
        QueryResult savedResult = read(task.getResultJson(), QueryResult.class);
        if (savedResult == null || savedResult.fallback() != null && !savedResult.fallback().dataAvailable()) {
            throw new BusinessException(409008, "该查询没有可继续翻页的数据，请调整条件后重新查询");
        }
        PlannedQuery plan = storedPlan(task, savedResult);

        // 翻页发生在任务结束之后，必须按“现在的身份”重新证明 SQL 仍然安全且没有扩大数据范围。
        safety.validate(plan.sql());
        if (modelGenerated(task.getInterpretationSource())) {
            scope.validate(plan.sql(), user);
        }
        if (task.getResolvedCustomerId() != null) {
            scope.validateCustomer(plan.sql(), plan.parameters(), task.getResolvedCustomerId());
        } else if (task.getCustomerIdsJson() != null) {
            scope.validateCustomers(plan.sql(), plan.parameters(),
                    Arrays.asList(read(task.getCustomerIdsJson(), String[].class)));
        }

        // 使用独立执行编号，避免两个历史结果组件同时翻页时被误判为同一条并发 SQL。
        String executionId = "page-" + taskId + "-" + UUID.randomUUID();
        var rows = execution.execute(executionId, plan, requestedPage, () -> true);
        QueryResult result = assembler.assemble(plan, rows, task.getInterpretationSource(),
                task.getInterpretationConfidence() == null ? 1.0 : task.getInterpretationConfidence());
        FallbackInfo fallback = savedResult.fallback();
        return fallback == null ? result : result.withFallback(fallback);
    }

    private QueryTaskEntity ownedCompletedTask(String taskId, CurrentUser user) {
        QueryTaskEntity task = taskMapper.selectOne(Wrappers.<QueryTaskEntity>lambdaQuery()
                .eq(QueryTaskEntity::getTaskId, taskId)
                .eq(QueryTaskEntity::getUserId, user.userId())
                .last("LIMIT 1"));
        if (task == null) {
            throw new BusinessException(404001, "查询任务不存在");
        }
        authorization.requireOwner(user, task.getUserId(), "查询任务不存在");
        conversations.own(task.getSessionId(), user);
        if (!List.of("SUCCESS", "DEGRADED").contains(task.getStatusCode())
                || task.getResultJson() == null || task.getSqlText() == null) {
            throw new BusinessException(409008, "该查询尚无可分页的完整结果，请等待查询完成或重新查询");
        }
        return task;
    }

    private QueryPage requestedPage(int pageNo, int pageSize) {
        if (pageNo < 1) {
            throw new BusinessException(400001, "page_no必须大于等于1");
        }
        if (pageNo > maxResultPages) {
            throw new BusinessException(400001, "查询结果最多只能查看前" + maxResultPages + "页");
        }
        if (pageSize < 1 || pageSize > maxPageSize) {
            throw new BusinessException(400001, "每页条数必须在1至" + maxPageSize + "之间");
        }
        long offset;
        try {
            offset = Math.multiplyExact((long) pageNo - 1, pageSize);
        } catch (ArithmeticException overflow) {
            throw new BusinessException(400001, "分页偏移量过大");
        }
        if (offset > maxOffset) {
            throw new BusinessException(400001, "分页位置超过系统上限，请缩小查询条件范围");
        }
        return new QueryPage(pageNo, pageSize, offset);
    }

    @SuppressWarnings("unchecked")
    private PlannedQuery storedPlan(QueryTaskEntity task, QueryResult savedResult) {
        Map<String, Object> parameters = task.getSqlParametersJson() == null
                ? Map.of() : read(task.getSqlParametersJson(), Map.class);
        List<ResultColumnHint> hints = task.getColumnHintsJson() == null
                ? List.of() : List.of(read(task.getColumnHintsJson(), ResultColumnHint[].class));
        return new PlannedQuery(task.getSqlText(), parameters, savedResult.resultType(),
                savedResult.title(), QueryRisk.low(), hints);
    }

    /** 规则模板已由固定参数收窄；自由生成的 SQL 需要按当前身份再次做范围证明。 */
    private boolean modelGenerated(String source) {
        return source != null && !"RULE".equals(source) && !"TEMPLATE_FALLBACK".equals(source);
    }

    private <T> T read(String value, Class<T> type) {
        return json.readValue(value, type);
    }
}
