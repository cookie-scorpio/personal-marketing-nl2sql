package com.boc.nl2sql.domain.evaluation;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 评测集条目：问题原文加人工审核确认的金标 SQL。 */
@TableName("eval_dataset_item")
public class EvalDatasetItemEntity {
    @TableId
    private Long id;
    private Long datasetId;
    private String sourceEventId;
    private String sourceTaskId;
    private String questionText;
    private String expectedSql;
    private String note;
    private String intentCode;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getDatasetId() { return datasetId; }
    public void setDatasetId(Long value) { datasetId = value; }
    public String getSourceEventId() { return sourceEventId; }
    public void setSourceEventId(String value) { sourceEventId = value; }
    public String getSourceTaskId() { return sourceTaskId; }
    public void setSourceTaskId(String value) { sourceTaskId = value; }
    public String getQuestionText() { return questionText; }
    public void setQuestionText(String value) { questionText = value; }
    public String getExpectedSql() { return expectedSql; }
    public void setExpectedSql(String value) { expectedSql = value; }
    public String getNote() { return note; }
    public void setNote(String value) { note = value; }
    public String getIntentCode() { return intentCode; }
    public void setIntentCode(String value) { intentCode = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
