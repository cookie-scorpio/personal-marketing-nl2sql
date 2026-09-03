package com.boc.nl2sql.controller.quality;

import com.boc.nl2sql.common.api.ApiResponse;
import com.boc.nl2sql.common.web.WebRequestSupport;
import com.boc.nl2sql.service.monitoring.InsightService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 优化洞察接口：失败热点、错误聚类、澄清与修复案例、模型调用成本；权限沿用 /api/v1/quality/** 的质量审计角色。 */
@RestController
@RequestMapping("/api/v1/quality/insight")
public class QualityInsightController {
    private final InsightService insight;

    public QualityInsightController(InsightService insight) {
        this.insight = insight;
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview(
            @RequestParam(defaultValue = "168", name = "hours") int hours,
            HttpServletRequest request) {
        return ApiResponse.success(insight.overview(hours), WebRequestSupport.requestId(request));
    }
}
