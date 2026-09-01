package com.boc.nl2sql.history.application;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.boc.nl2sql.common.api.PageResult;
import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.authorization.application.AuthorizationCenter;
import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.history.infrastructure.QueryHistoryEntity;
import com.boc.nl2sql.history.infrastructure.QueryHistoryMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class HistoryService {
    private static final int SQL_SUMMARY_MAX_LENGTH = 500;
    private static final int RESULT_SUMMARY_MAX_LENGTH = 500;
    private final QueryHistoryMapper mapper;
    private final AuthorizationCenter authorization;

    public HistoryService(QueryHistoryMapper mapper, AuthorizationCenter authorization) {
        this.mapper = mapper;
        this.authorization = authorization;
    }

    public void save(String taskId, Long userId, String queryText, String intent, String status,
                     String sqlSummary, String resultSummary) {
        QueryHistoryEntity entity = new QueryHistoryEntity();
        entity.setHistoryId(UUID.randomUUID().toString());
        entity.setTaskId(taskId);
        entity.setUserId(userId);
        entity.setQueryText(queryText);
        entity.setIntentCode(intent);
        entity.setStatusCode(status);
        // 历史页只保存便于回顾的摘要；完整 SQL 已在任务表中保存，不能突破字段边界拖垮主查询状态。
        entity.setSqlSummary(truncate(sqlSummary, SQL_SUMMARY_MAX_LENGTH));
        entity.setResultSummary(truncate(resultSummary, RESULT_SUMMARY_MAX_LENGTH));
        entity.setCreatedAt(LocalDateTime.now());
        entity.setDeleted(false);
        mapper.insert(entity);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    public PageResult<QueryHistoryEntity> page(CurrentUser user, int pageNo, int pageSize, String keyword) {
        authorization.requireAuthenticated(user);
        IPage<QueryHistoryEntity> page = mapper.selectPage(Page.of(pageNo, pageSize),
                Wrappers.<QueryHistoryEntity>lambdaQuery()
                        .eq(QueryHistoryEntity::getUserId, user.userId())
                        .eq(QueryHistoryEntity::getDeleted, false)
                        .notInSql(QueryHistoryEntity::getTaskId,"SELECT q.task_id FROM query_task q JOIN conversation_session s ON s.session_id=q.session_id WHERE s.deleted_at IS NOT NULL")
                        .like(keyword != null && !keyword.isBlank(), QueryHistoryEntity::getQueryText, keyword)
                        .orderByDesc(QueryHistoryEntity::getCreatedAt));
        return new PageResult<>(page.getRecords(), page.getTotal(), pageNo, pageSize);
    }

    public void delete(CurrentUser user, String historyId) {
        QueryHistoryEntity entity = mapper.selectById(historyId);
        if (entity == null) {
            throw new BusinessException(404001, "历史记录不存在");
        }
        authorization.requireOwner(user, entity.getUserId(), "历史记录不存在");
        entity.setDeleted(true);
        mapper.updateById(entity);
    }
}
