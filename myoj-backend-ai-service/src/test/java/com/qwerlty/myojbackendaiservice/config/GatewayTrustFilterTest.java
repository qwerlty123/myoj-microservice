package com.qwerlty.myojbackendaiservice.config;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayTrustFilterTest {

    @Test
    void rejectsRequestsWithoutTheGatewayToken() throws ServletException, IOException {
        GatewayTrustFilter filter = new GatewayTrustFilter("trusted");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ai/create/question");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void forwardsRequestsWithTheGatewayToken() throws ServletException, IOException {
        GatewayTrustFilter filter = new GatewayTrustFilter("trusted");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ai/create/question");
        request.addHeader("X-Gateway-Token", "trusted");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }
}
