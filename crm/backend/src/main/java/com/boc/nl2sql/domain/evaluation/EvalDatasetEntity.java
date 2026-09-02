package com.boc.nl2sql.domain.evaluation;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 评测集聚合：同一时刻最多一份草稿，发布版本只读保留。 */
@TableName("eval_dataset")
public class EvalDatasetEntity {
    @TableId
    private Long id;
    private String name;
    private String description;
    private String status;
    private Integer version;
    private Integer itemCount;
    private LocalDateTime publishedAt;
    private Long publishedBy;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean isDraft() { return EvalDatasetStatus.DRAFT.name().equals(status); }

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer value) { version = value; }
    public Integer getItemCount() { return itemCount; }
    public void setItemCount(Integer value) { itemCount = value; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime value) { publishedAt = value; }
    public Long getPublishedBy() { return publishedBy; }
    public void setPublishedBy(Long value) { publishedBy = value; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long value) { createdBy = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
