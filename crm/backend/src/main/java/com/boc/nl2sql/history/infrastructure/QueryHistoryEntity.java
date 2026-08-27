package com.boc.nl2sql.history.infrastructure;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("query_history")
public class QueryHistoryEntity {
    @TableId
    private String historyId;
    private String taskId;
    private Long userId;
    private String queryText;
    private String intentCode;
    private String statusCode;
    private String sqlSummary;
    private String resultSummary;
    private LocalDateTime createdAt;
    private Boolean deleted;

    public String getHistoryId() { return historyId; }
    public void setHistoryId(String historyId) { this.historyId = historyId; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getQueryText() { return queryText; }
    public void setQueryText(String queryText) { this.queryText = queryText; }
    public String getIntentCode() { return intentCode; }
    public void setIntentCode(String intentCode) { this.intentCode = intentCode; }
    public String getStatusCode() { return statusCode; }
    public void setStatusCode(String statusCode) { this.statusCode = statusCode; }
    public String getSqlSummary() { return sqlSummary; }
    public void setSqlSummary(String sqlSummary) { this.sqlSummary = sqlSummary; }
    public String getResultSummary() { return resultSummary; }
    public void setResultSummary(String resultSummary) { this.resultSummary = resultSummary; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Boolean getDeleted() { return deleted; }
    public void setDeleted(Boolean deleted) { this.deleted = deleted; }
}
