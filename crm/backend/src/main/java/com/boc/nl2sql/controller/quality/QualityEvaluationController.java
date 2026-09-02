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

/** 数据回流与评测后台接口；访问权限由 SecurityConfig 限制为质量审计角色。 */
@RestController
@RequestMapping("/api/v1/quality/evaluation")
public class QualityEvaluationController {
    private final EvaluationService evaluation;

    public QualityEvaluationController(EvaluationService evaluation) {
        this.evaluation = evaluation;
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
                body.expectedSql(), body.note(), user);
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
        return ApiResponse.success(evaluation.addItem(body.questionText(), body.expectedSql(), body.note(), user),
                WebRequestSupport.requestId(request));
    }

    @PutMapping("/items/{itemId}")
    public ApiResponse<Void> updateItem(
            @PathVariable long itemId,
            @RequestBody DatasetItemRequest body,
            @AuthenticationPrincipal CurrentUser user,
            HttpServletRequest request) {
        evaluation.updateItem(itemId, body.questionText(), body.expectedSql(), body.note(), user);
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
    public record AcceptCandidateRequest(String questionText, String expectedSql, String note) { }

    public record IgnoreCandidateRequest(String note) { }

    public record DatasetItemRequest(String questionText, String expectedSql, String note) { }
}
