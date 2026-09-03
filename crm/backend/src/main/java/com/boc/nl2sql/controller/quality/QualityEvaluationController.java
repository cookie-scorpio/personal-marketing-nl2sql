package com.boc.nl2sql.controller.quality;

import com.boc.nl2sql.common.api.ApiResponse;
import com.boc.nl2sql.common.web.WebRequestSupport;
import com.boc.nl2sql.domain.authorization.CurrentUser;
import com.boc.nl2sql.service.evaluation.EvaluationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 数据回流与评测后台接口；访问权限由 SecurityConfig 限制为质量审计角色。
 * 事实类查询分两种粒度：/facts 逐条审计事实，/facts/tasks 每个任务一行。
 */
@RestController
@RequestMapping("/api/v1/quality/evaluation")
public class QualityEvaluationController {
    private final EvaluationService evaluation;

    public QualityEvaluationController(EvaluationService evaluation) {
        this.evaluation = evaluation;
    }

    /**
     * 全量任务事实分页：支持审核状态、事件类型、任务终态、意图、关键词与时间窗口组合筛选。
     * 逗号分隔的列表参数在服务层统一大写归一。
     */
    @GetMapping("/facts")
    public ApiResponse<Map<String, Object>> facts(
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(required = false, name = "event_type") String eventType,
            @RequestParam(required = false, name = "task_status") String taskStatus,
            @RequestParam(required = false) String intent,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "168", name = "hours") int hours,
            @RequestParam(defaultValue = "1", name = "page_no") int pageNo,
            @RequestParam(defaultValue = "20", name = "page_size") int pageSize,
            HttpServletRequest request) {
        return ApiResponse.success(evaluation.factPage(status, splitList(eventType), splitList(taskStatus),
                blankToNull(intent), blankToNull(keyword), hours, pageNo, pageSize),
                WebRequestSupport.requestId(request));
    }

    /** 任务视角分页：每个终态任务一行（默认视图），终态事件作为审核锚点。 */
    @GetMapping("/facts/tasks")
    public ApiResponse<Map<String, Object>> taskPage(
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(required = false, name = "task_status") String taskStatus,
            @RequestParam(required = false) String intent,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "168", name = "hours") int hours,
            @RequestParam(defaultValue = "1", name = "page_no") int pageNo,
            @RequestParam(defaultValue = "20", name = "page_size") int pageSize,
            HttpServletRequest request) {
        return ApiResponse.success(evaluation.taskPage(status, splitList(taskStatus),
                blankToNull(intent), blankToNull(keyword), hours, pageNo, pageSize),
                WebRequestSupport.requestId(request));
    }

    /** 单个任务的全链路事实时间线，供样本回流页详情抽屉使用。 */
    @GetMapping("/facts/task/{taskId}")
    public ApiResponse<java.util.List<Map<String, Object>>> taskFacts(
            @PathVariable String taskId,
            HttpServletRequest request) {
        return ApiResponse.success(evaluation.taskFacts(taskId), WebRequestSupport.requestId(request));
    }

    @GetMapping("/candidates")
    public ApiResponse<Map<String, Object>> candidates(
            @RequestParam(defaultValue = "pending") String status,
            @RequestParam(defaultValue = "1", name = "page_no") int pageNo,
            @RequestParam(defaultValue = "20", name = "page_size") int pageSize,
            HttpServletRequest request) {
        return ApiResponse.success(evaluation.candidatePage(status, pageNo, pageSize),
                WebRequestSupport.requestId(request));
    }

    @PostMapping("/candidates/{eventId}/accept")
    public ApiResponse<Map<String, Object>> accept(
            @PathVariable String eventId,
            @RequestBody AcceptCandidateRequest body,
            @AuthenticationPrincipal CurrentUser user,
            HttpServletRequest request) {
        Map<String, Object> result = evaluation.acceptCandidate(eventId, body.questionText(),
                body.expectedSql(), body.note(), body.intentCode(), user);
        return ApiResponse.success(result, WebRequestSupport.requestId(request));
    }

    @PostMapping("/candidates/{eventId}/ignore")
    public ApiResponse<Void> ignore(
            @PathVariable String eventId,
            @RequestBody(required = false) IgnoreCandidateRequest body,
            @AuthenticationPrincipal CurrentUser user,
            HttpServletRequest request) {
        evaluation.ignoreCandidate(eventId, body == null ? null : body.note(), user);
        return ApiResponse.success(null, WebRequestSupport.requestId(request));
    }

    @GetMapping("/datasets")
    public ApiResponse<java.util.List<Map<String, Object>>> datasets(HttpServletRequest request) {
        return ApiResponse.success(evaluation.listDatasets(), WebRequestSupport.requestId(request));
    }

    @GetMapping("/datasets/current")
    public ApiResponse<Map<String, Object>> currentDraft(HttpServletRequest request) {
        return ApiResponse.success(evaluation.currentDraft(), WebRequestSupport.requestId(request));
    }

    @GetMapping("/datasets/{datasetId}")
    public ApiResponse<Map<String, Object>> dataset(@PathVariable long datasetId, HttpServletRequest request) {
        return ApiResponse.success(evaluation.datasetDetail(datasetId), WebRequestSupport.requestId(request));
    }

    @PostMapping("/datasets/current/items")
    public ApiResponse<Map<String, Object>> addItem(
            @RequestBody DatasetItemRequest body,
            @AuthenticationPrincipal CurrentUser user,
            HttpServletRequest request) {
        return ApiResponse.success(
                evaluation.addItem(body.questionText(), body.expectedSql(), body.note(), body.intentCode(), user),
                WebRequestSupport.requestId(request));
    }

    @PutMapping("/items/{itemId}")
    public ApiResponse<Void> updateItem(
            @PathVariable long itemId,
            @RequestBody DatasetItemRequest body,
            @AuthenticationPrincipal CurrentUser user,
            HttpServletRequest request) {
        evaluation.updateItem(itemId, body.questionText(), body.expectedSql(), body.note(), body.intentCode(), user);
        return ApiResponse.success(null, WebRequestSupport.requestId(request));
    }

    @DeleteMapping("/items/{itemId}")
    public ApiResponse<Void> deleteItem(@PathVariable long itemId, HttpServletRequest request) {
        evaluation.deleteItem(itemId);
        return ApiResponse.success(null, WebRequestSupport.requestId(request));
    }

    @PostMapping("/datasets/{datasetId}/publish")
    public ApiResponse<Map<String, Object>> publish(
            @PathVariable long datasetId,
            @AuthenticationPrincipal CurrentUser user,
            HttpServletRequest request) {
        return ApiResponse.success(evaluation.publish(datasetId, user), WebRequestSupport.requestId(request));
    }

    @PostMapping("/datasets/{datasetId}/runs")
    public ApiResponse<Map<String, Object>> rerun(
            @PathVariable long datasetId,
            @AuthenticationPrincipal CurrentUser user,
            HttpServletRequest request) {
        return ApiResponse.success(evaluation.startRun(datasetId, user), WebRequestSupport.requestId(request));
    }

    @GetMapping("/runs")
    public ApiResponse<Map<String, Object>> runs(
            @RequestParam(defaultValue = "1", name = "page_no") int pageNo,
            @RequestParam(defaultValue = "20", name = "page_size") int pageSize,
            HttpServletRequest request) {
        return ApiResponse.success(evaluation.runPage(pageNo, pageSize), WebRequestSupport.requestId(request));
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<Map<String, Object>> run(@PathVariable long runId, HttpServletRequest request) {
        return ApiResponse.success(evaluation.runDetail(runId), WebRequestSupport.requestId(request));
    }

    /** snake_case 请求字段由 Jackson 的 SNAKE_CASE 策略绑定到 camelCase 组件。 */
    public record AcceptCandidateRequest(String questionText, String expectedSql, String note, String intentCode) { }

    public record IgnoreCandidateRequest(String note) { }

    public record DatasetItemRequest(String questionText, String expectedSql, String note, String intentCode) { }

    /** 逗号分隔的筛选列表参数拆分为去空白的字符串列表。 */
    private java.util.List<String> splitList(String value) {
        if (value == null || value.isBlank()) return java.util.List.of();
        return java.util.Arrays.stream(value.split(",")).map(String::trim)
                .filter(item -> !item.isEmpty()).toList();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
