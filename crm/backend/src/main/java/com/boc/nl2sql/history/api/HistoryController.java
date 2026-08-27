package com.boc.nl2sql.history.api;

import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.common.api.ApiResponse;
import com.boc.nl2sql.common.api.PageResult;
import com.boc.nl2sql.common.web.WebRequestSupport;
import com.boc.nl2sql.history.application.HistoryService;
import com.boc.nl2sql.history.infrastructure.QueryHistoryEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/query-history")
public class HistoryController {
    private final HistoryService historyService;

    public HistoryController(HistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping
    public ApiResponse<PageResult<QueryHistoryEntity>> page(
            @RequestParam(name = "page_no", defaultValue = "1") int pageNo,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword,
            @AuthenticationPrincipal CurrentUser user,
            HttpServletRequest request) {
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        return ApiResponse.success(historyService.page(user.userId(), Math.max(pageNo, 1), safeSize, keyword),
                WebRequestSupport.requestId(request));
    }

    @DeleteMapping("/{historyId}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String historyId,
                                                   @AuthenticationPrincipal CurrentUser user,
                                                   HttpServletRequest request) {
        historyService.delete(user.userId(), historyId);
        return ApiResponse.success(Map.of("history_id", historyId, "deleted", true),
                WebRequestSupport.requestId(request));
    }
}
