package com.boc.nl2sql.domain.evaluation;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 评测运行明细：每条样本的生成 SQL、逐维度布尔结论与耗时。 */
@TableName("eval_run_item")
public class EvalRunItemEntity {
    @TableId
    private Long id;
    private Long runId;
    private Long itemId;
    private String questionText;
    private String expectedSql;
    private String generatedSql;
    private Boolean executionSuccess;
    private Boolean sqlMatch;
    private Boolean resultConsistent;
    private Integer expectedRows;
    private Integer actualRows;
    private Long elapsedMs;
    private String outcome;
    private String failureStage;
    private String errorMessage;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getRunId() { return runId; }
    public void setRunId(Long value) { runId = value; }
    public Long getItemId() { return itemId; }
    public void setItemId(Long value) { itemId = value; }
    public String getQuestionText() { return questionText; }
    public void setQuestionText(String value) { questionText = value; }
    public String getExpectedSql() { return expectedSql; }
    public void setExpectedSql(String value) { expectedSql = value; }
    public String getGeneratedSql() { return generatedSql; }
    public void setGeneratedSql(String value) { generatedSql = value; }
    public Boolean getExecutionSuccess() { return executionSuccess; }
    public void setExecutionSuccess(Boolean value) { executionSuccess = value; }
    public Boolean getSqlMatch() { return sqlMatch; }
    public void setSqlMatch(Boolean value) { sqlMatch = value; }
    public Boolean getResultConsistent() { return resultConsistent; }
    public void setResultConsistent(Boolean value) { resultConsistent = value; }
    public Integer getExpectedRows() { return expectedRows; }
    public void setExpectedRows(Integer value) { expectedRows = value; }
    public Integer getActualRows() { return actualRows; }
    public void setActualRows(Integer value) { actualRows = value; }
    public Long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(Long value) { elapsedMs = value; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String value) { outcome = value; }
    public String getFailureStage() { return failureStage; }
    public void setFailureStage(String value) { failureStage = value; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String value) { errorMessage = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
}
