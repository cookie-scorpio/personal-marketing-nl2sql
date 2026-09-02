package com.boc.nl2sql.controller.quality;

import com.boc.nl2sql.common.api.ApiResponse;
import com.boc.nl2sql.common.web.WebRequestSupport;
import com.boc.nl2sql.service.monitoring.LogQueryService;
import com.boc.nl2sql.service.monitoring.SystemHealthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 后台监控页面的聚合接口；SecurityConfig 已将 /api/v1/quality/** 限制为质量审计角色。
 * 全部只读，返回结构化 Map 以便监控页面按模块自由组装。
 */
@RestController
@RequestMapping("/api/v1/quality/monitor")
public class QualityMonitorController {
    private final SystemHealthService health;
    private final LogQueryService logs;

    public QualityMonitorController(SystemHealthService health, LogQueryService logs) {
        this.health = health;
        this.logs = logs;
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview(HttpServletRequest request) {
        return ApiResponse.success(health.overview(), WebRequestSupport.requestId(request));
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health(HttpServletRequest request) {
        return ApiResponse.success(health.healthSnapshot(), WebRequestSupport.requestId(request));
    }

    @GetMapping("/resources")
    public ApiResponse<Map<String, Object>> resources(HttpServletRequest request) {
        return ApiResponse.success(health.resourceSnapshot(), WebRequestSupport.requestId(request));
    }

    @GetMapping("/sql-health")
    public ApiResponse<Map<String, Object>> sqlHealth(
            @RequestParam(defaultValue = "24", name = "hours") int hours,
            HttpServletRequest request) {
        return ApiResponse.success(health.sqlHealth(window(hours)), WebRequestSupport.requestId(request));
    }

    @GetMapping("/business")
    public ApiResponse<Map<String, Object>> business(
            @RequestParam(defaultValue = "24", name = "hours") int hours,
            HttpServletRequest request) {
        return ApiResponse.success(health.businessSnapshot(window(hours)), WebRequestSupport.requestId(request));
    }

    @GetMapping("/logs")
    public ApiResponse<Map<String, Object>> logs(
            @RequestParam(defaultValue = "application") String file,
            @RequestParam(defaultValue = "200") int lines,
            @RequestParam(required = false) String keyword,
            HttpServletRequest request) {
        return ApiResponse.success(logs.tail(file, lines, keyword), WebRequestSupport.requestId(request));
    }

    @GetMapping("/logs/catalog")
    public ApiResponse<java.util.List<Map<String, Object>>> logCatalog(HttpServletRequest request) {
        return ApiResponse.success(logs.fileCatalog(), WebRequestSupport.requestId(request));
    }

    /** 统计窗口收敛到 1 小时至 7 天，避免任意大窗口拖垮聚合查询。 */
    private int window(int hours) {
        return Math.max(1, Math.min(hours, 24 * 7));
    }
}
