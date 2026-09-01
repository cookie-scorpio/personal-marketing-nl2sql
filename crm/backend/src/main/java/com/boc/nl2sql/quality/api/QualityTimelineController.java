package com.boc.nl2sql.quality.api;

import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.common.api.ApiResponse;
import com.boc.nl2sql.common.web.WebRequestSupport;
import com.boc.nl2sql.quality.collection.QualityFacts;
import com.boc.nl2sql.quality.event.QualityEventType;
import com.boc.nl2sql.quality.event.QualityFact;
import com.boc.nl2sql.quality.query.QualityTimelineQuery;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/quality")
public class QualityTimelineController {
    private final QualityTimelineQuery timeline;
    private final QualityFacts facts;

    public QualityTimelineController(QualityTimelineQuery timeline, QualityFacts facts) {
        this.timeline = timeline;
        this.facts = facts;
    }

    @GetMapping("/tasks/{taskId}/events")
    public ApiResponse<List<Map<String, Object>>> taskEvents(
            @PathVariable String taskId,
            @RequestParam(defaultValue = "0", name = "after_id") long afterId,
            @RequestParam(defaultValue = "200", name = "page_size") int pageSize,
            @AuthenticationPrincipal CurrentUser user,
            HttpServletRequest request) {
        List<Map<String, Object>> result = timeline.taskTimeline(taskId, afterId, pageSize);
        facts.publish(QualityFact.builder(QualityEventType.QUALITY_TIMELINE_VIEWED, "QUALITY")
                .requestId(WebRequestSupport.requestId(request)).taskId(taskId).userId(user.userId())
                .summary("quality timeline viewed").detail("returned_events", result.size()).build());
        return ApiResponse.success(result, WebRequestSupport.requestId(request));
    }
}
