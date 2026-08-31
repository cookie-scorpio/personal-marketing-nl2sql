package com.boc.nl2sql.conversation.infrastructure;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;

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
    private String interpretationSource;
    private Double interpretationConfidence;
    private String preferredDisplay;
    private Boolean thinkingEnabled;
    private String idempotencyKey;
    private String requestHash;
    private String contextJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String resolvedCustomerId;
    private String displayQuery;
    private String customerIdsJson;
    private String multiCustomersJson;
    private Long stateVersion;
    private Integer repairAttempts;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String columnHintsJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String fallbackJson;
    private Integer clarificationRound;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String questionJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String confirmationToken;
    private String riskJson;
    private Boolean confirmed;
    private String sqlText;
    private String sqlParametersJson;
    private String resultJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getTaskId() { return taskId; }
    public Boolean getThinkingEnabled() { return thinkingEnabled; }
    public void setThinkingEnabled(Boolean value) { thinkingEnabled=value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { idempotencyKey=value; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String value) { requestHash=value; }
    public String getContextJson() { return contextJson; }
    public void setContextJson(String value) { contextJson=value; }
    public String getResolvedCustomerId() { return resolvedCustomerId; }
    public void setResolvedCustomerId(String value) { resolvedCustomerId=value; }
    public String getDisplayQuery() { return displayQuery; }
    public void setDisplayQuery(String value) { displayQuery=value; }
    public String getCustomerIdsJson() { return customerIdsJson; }
    public void setCustomerIdsJson(String value) { customerIdsJson=value; }
    public String getMultiCustomersJson() { return multiCustomersJson; }
    public void setMultiCustomersJson(String value) { multiCustomersJson=value; }
    public Long getStateVersion() { return stateVersion; }
    public void setStateVersion(Long stateVersion) { this.stateVersion = stateVersion; }
    public Integer getRepairAttempts() { return repairAttempts; }
    public void setRepairAttempts(Integer repairAttempts) { this.repairAttempts = repairAttempts; }
    public String getColumnHintsJson() { return columnHintsJson; }
    public void setColumnHintsJson(String columnHintsJson) { this.columnHintsJson = columnHintsJson; }
    public String getFallbackJson() { return fallbackJson; }
    public void setFallbackJson(String fallbackJson) { this.fallbackJson = fallbackJson; }
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
    public String getInterpretationSource() { return interpretationSource; }
    public void setInterpretationSource(String interpretationSource) { this.interpretationSource = interpretationSource; }
    public Double getInterpretationConfidence() { return interpretationConfidence; }
    public void setInterpretationConfidence(Double interpretationConfidence) { this.interpretationConfidence = interpretationConfidence; }
    public String getPreferredDisplay() { return preferredDisplay; }
    public void setPreferredDisplay(String preferredDisplay) { this.preferredDisplay = preferredDisplay; }
    public Integer getClarificationRound() { return clarificationRound; }
    public void setClarificationRound(Integer clarificationRound) { this.clarificationRound = clarificationRound; }
    public String getQuestionJson() { return questionJson; }
    public void setQuestionJson(String questionJson) { this.questionJson = questionJson; }
    public String getConfirmationToken() { return confirmationToken; }
    public void setConfirmationToken(String confirmationToken) { this.confirmationToken = confirmationToken; }
    public String getRiskJson() { return riskJson; }
    public void setRiskJson(String riskJson) { this.riskJson = riskJson; }
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
