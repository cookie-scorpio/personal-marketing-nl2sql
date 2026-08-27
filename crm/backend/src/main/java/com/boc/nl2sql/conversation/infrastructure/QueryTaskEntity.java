package com.boc.nl2sql.conversation.infrastructure;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("query_task")
public class QueryTaskEntity {
    @TableId
    private String taskId;
    private String sessionId;
    private Long userId;
    private String queryText;
    private String mergedQueryText;
    private String statusCode;
    private Integer progress;
    private String stageMessage;
    private String intentCode;
    private Integer clarificationRound;
    private String questionJson;
    private String confirmationToken;
    private Boolean confirmed;
    private String sqlText;
    private String sqlParametersJson;
    private String resultJson;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getQueryText() { return queryText; }
    public void setQueryText(String queryText) { this.queryText = queryText; }
    public String getMergedQueryText() { return mergedQueryText; }
    public void setMergedQueryText(String mergedQueryText) { this.mergedQueryText = mergedQueryText; }
    public String getStatusCode() { return statusCode; }
    public void setStatusCode(String statusCode) { this.statusCode = statusCode; }
    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }
    public String getStageMessage() { return stageMessage; }
    public void setStageMessage(String stageMessage) { this.stageMessage = stageMessage; }
    public String getIntentCode() { return intentCode; }
    public void setIntentCode(String intentCode) { this.intentCode = intentCode; }
    public Integer getClarificationRound() { return clarificationRound; }
    public void setClarificationRound(Integer clarificationRound) { this.clarificationRound = clarificationRound; }
    public String getQuestionJson() { return questionJson; }
    public void setQuestionJson(String questionJson) { this.questionJson = questionJson; }
    public String getConfirmationToken() { return confirmationToken; }
    public void setConfirmationToken(String confirmationToken) { this.confirmationToken = confirmationToken; }
    public Boolean getConfirmed() { return confirmed; }
    public void setConfirmed(Boolean confirmed) { this.confirmed = confirmed; }
    public String getSqlText() { return sqlText; }
    public void setSqlText(String sqlText) { this.sqlText = sqlText; }
    public String getSqlParametersJson() { return sqlParametersJson; }
    public void setSqlParametersJson(String sqlParametersJson) { this.sqlParametersJson = sqlParametersJson; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
