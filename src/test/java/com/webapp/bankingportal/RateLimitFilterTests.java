package com.webapp.bankingportal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.webapp.bankingportal.ratelimit.RateLimitFilter;
import com.webapp.bankingportal.ratelimit.RateLimitProperties;
import com.webapp.bankingportal.ratelimit.RedisRateLimiter;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTests {

    @Mock
    private RedisRateLimiter rateLimiter;

    private RateLimitProperties properties;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.getAuth().setLimit(5);
        properties.getAuth().setWindowSeconds(60);
        properties.getTransaction().setLimit(3);
        properties.getTransaction().setWindowSeconds(60);
        properties.getDefaultBucket().setLimit(100);
        properties.getDefaultBucket().setWindowSeconds(60);

        filter = new RateLimitFilter(rateLimiter, properties);
    }

    @Test
    void nonApiPath_isNeverRateLimited() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
    }

    @Test
    void disabledRateLimiting_skipsCheckEntirely() throws Exception {
        properties.setEnabled(false);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/users/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
    }

    @Test
    void allowedRequest_passesThroughToChain() throws Exception {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/users/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
    }

    @Test
    void exceededLimit_returns429WithRetryAfterHeader() throws Exception {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/users/login");
        request.setRemoteAddr("203.0.113.5");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(429, response.getStatus());
        assertEquals("60", response.getHeader("Retry-After"));
    }

    @Test
    void authPathUsesAuthBucketLimit() throws Exception {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/users/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        verify(rateLimiter).tryAcquire(anyString(), org.mockito.ArgumentMatchers.eq(5), any());
    }

    @Test
    void transactionPathUsesTransactionBucketLimit() throws Exception {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/account/fund-transfer");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        verify(rateLimiter).tryAcquire(anyString(), org.mockito.ArgumentMatchers.eq(3), any());
    }

    @Test
    void redisUnavailable_failsOpenAndAllowsRequest() throws Exception {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/account/deposit");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
    }
}
