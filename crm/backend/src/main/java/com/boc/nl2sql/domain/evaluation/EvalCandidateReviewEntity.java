package com.boc.nl2sql.domain.evaluation;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 候选审计事件的审核结论，按 event_id 幂等。 */
@TableName("eval_candidate_review")
public class EvalCandidateReviewEntity {
    @TableId
    private String eventId;
    private String decision;
    private Long datasetItemId;
    private Long reviewedBy;
    private String note;
    private LocalDateTime reviewedAt;

    public String getEventId() { return eventId; }
    public void setEventId(String value) { eventId = value; }
    public String getDecision() { return decision; }
    public void setDecision(String value) { decision = value; }
    public Long getDatasetItemId() { return datasetItemId; }
    public void setDatasetItemId(Long value) { datasetItemId = value; }
    public Long getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(Long value) { reviewedBy = value; }
    public String getNote() { return note; }
    public void setNote(String value) { note = value; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime value) { reviewedAt = value; }
}
