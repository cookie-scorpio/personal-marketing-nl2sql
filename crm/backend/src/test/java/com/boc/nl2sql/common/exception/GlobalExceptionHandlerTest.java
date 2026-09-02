package com.boc.nl2sql.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证稳定业务码与 HTTP 状态保持一致，避免客户端把“账号不存在”误当成参数错误。 */
class GlobalExceptionHandlerTest {
    @Test
    void mapsEveryNotFoundBusinessCodeToHttpNotFound() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE",
                "/api/v1/permission-admin/accounts/999");

        var response = handler.business(new BusinessException(404002, "账号不存在"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(404002);
    }
}
