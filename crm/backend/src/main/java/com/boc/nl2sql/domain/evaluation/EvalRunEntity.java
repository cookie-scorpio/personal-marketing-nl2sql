package com.boc.nl2sql.domain.evaluation;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 一次完整评测运行的聚合记录；维度汇总由明细实时聚合。 */
@TableName("eval_run")
public class EvalRunEntity {
    @TableId
    private Long id;
    private Long datasetId;
    private Integer datasetVersion;
    private String triggerType;
    private String status;
    private Integer totalItems;
    private Integer finishedItems;
    private Integer passedItems;
    private String errorMessage;
    private Long triggeredBy;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getDatasetId() { return datasetId; }
    public void setDatasetId(Long value) { datasetId = value; }
    public Integer getDatasetVersion() { return datasetVersion; }
    public void setDatasetVersion(Integer value) { datasetVersion = value; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String value) { triggerType = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Integer getTotalItems() { return totalItems; }
    public void setTotalItems(Integer value) { totalItems = value; }
    public Integer getFinishedItems() { return finishedItems; }
    public void setFinishedItems(Integer value) { finishedItems = value; }
    public Integer getPassedItems() { return passedItems; }
    public void setPassedItems(Integer value) { passedItems = value; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String value) { errorMessage = value; }
    public Long getTriggeredBy() { return triggeredBy; }
    public void setTriggeredBy(Long value) { triggeredBy = value; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime value) { startedAt = value; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime value) { finishedAt = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
}
