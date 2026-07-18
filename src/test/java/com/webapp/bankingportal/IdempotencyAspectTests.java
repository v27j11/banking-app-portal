package com.webapp.bankingportal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.webapp.bankingportal.idempotency.Idempotent;
import com.webapp.bankingportal.idempotency.IdempotencyAspect;
import com.webapp.bankingportal.idempotency.IdempotentRecord;
import com.webapp.bankingportal.service.CacheService;
import com.webapp.bankingportal.type.CacheKeyType;

@ExtendWith(MockitoExtension.class)
class IdempotencyAspectTests {

    @Mock
    private CacheService cacheService;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private Idempotent idempotent;

    private IdempotencyAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new IdempotencyAspect(cacheService);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void setRequest(MockHttpServletRequest request) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void noIdempotencyKeyHeader_proceedsNormallyWithoutTouchingCache() throws Throwable {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/account/deposit");
        setRequest(request);

        ResponseEntity<String> expected = ResponseEntity.ok("done");
        when(joinPoint.proceed()).thenReturn(expected);

        Object result = aspect.around(joinPoint, idempotent);

        assertEquals(expected, result);
        verify(cacheService, never()).get(any(), any(Class.class), any(String[].class));
        verify(cacheService, never()).putIfAbsent(any(), any(), anyLong(), any(String[].class));
    }

    @Test
    void firstRequestWithKey_acquiresLockProceedsAndCachesResponse() throws Throwable {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/account/deposit");
        request.addHeader("Idempotency-Key", "key-1");
        setRequest(request);

        when(idempotent.ttlSeconds()).thenReturn(86_400L);
        when(joinPoint.getArgs()).thenReturn(new Object[] { "payload" });
        when(cacheService.get(eq(CacheKeyType.IDEMPOTENCY), eq(IdempotentRecord.class), any(String[].class)))
                .thenReturn(Optional.empty());
        when(cacheService.putIfAbsent(eq(CacheKeyType.IDEMPOTENCY), any(IdempotentRecord.class), eq(30L),
                any(String[].class))).thenReturn(true);

        ResponseEntity<String> expected = ResponseEntity.ok("{\"msg\": \"Cash deposited successfully\"}");
        when(joinPoint.proceed()).thenReturn(expected);

        Object result = aspect.around(joinPoint, idempotent);

        assertEquals(expected, result);
        verify(cacheService, times(1)).put(eq(CacheKeyType.IDEMPOTENCY), any(IdempotentRecord.class), eq(86_400L),
                any(String[].class));
    }

    @Test
    void duplicateRequestWithSamePayload_returnsCachedResponseWithoutProceeding() throws Throwable {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/account/deposit");
        request.addHeader("Idempotency-Key", "key-1");
        setRequest(request);

        Object[] args = { "payload" };
        when(joinPoint.getArgs()).thenReturn(args);

        IdempotentRecord cached = new IdempotentRecord(
                java.util.Arrays.hashCode(args), 200, "{\"msg\": \"Cash deposited successfully\"}");
        when(cacheService.get(eq(CacheKeyType.IDEMPOTENCY), eq(IdempotentRecord.class), any(String[].class)))
                .thenReturn(Optional.of(cached));

        Object result = aspect.around(joinPoint, idempotent);

        ResponseEntity<?> response = (ResponseEntity<?>) result;
        assertEquals(200, response.getStatusCode().value());
        assertEquals("{\"msg\": \"Cash deposited successfully\"}", response.getBody());
        verify(joinPoint, never()).proceed();
    }

    @Test
    void sameKeyDifferentPayload_returnsConflict() throws Throwable {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/account/deposit");
        request.addHeader("Idempotency-Key", "key-1");
        setRequest(request);

        when(joinPoint.getArgs()).thenReturn(new Object[] { "different-payload" });

        IdempotentRecord cached = new IdempotentRecord(999_999, 200, "{\"msg\": \"Cash deposited successfully\"}");
        when(cacheService.get(eq(CacheKeyType.IDEMPOTENCY), eq(IdempotentRecord.class), any(String[].class)))
                .thenReturn(Optional.of(cached));

        Object result = aspect.around(joinPoint, idempotent);

        ResponseEntity<?> response = (ResponseEntity<?>) result;
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verify(joinPoint, never()).proceed();
    }

    @Test
    void concurrentInFlightDuplicate_returnsConflict() throws Throwable {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/account/deposit");
        request.addHeader("Idempotency-Key", "key-1");
        setRequest(request);

        when(joinPoint.getArgs()).thenReturn(new Object[] { "payload" });

        IdempotentRecord inProgress = new IdempotentRecord(
                java.util.Arrays.hashCode(new Object[] { "payload" }), IdempotentRecord.IN_PROGRESS_STATUS, null);
        when(cacheService.get(eq(CacheKeyType.IDEMPOTENCY), eq(IdempotentRecord.class), any(String[].class)))
                .thenReturn(Optional.of(inProgress));

        Object result = aspect.around(joinPoint, idempotent);

        ResponseEntity<?> response = (ResponseEntity<?>) result;
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verify(joinPoint, never()).proceed();
    }

    @Test
    void lockNotAcquired_returnsConflict() throws Throwable {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/account/deposit");
        request.addHeader("Idempotency-Key", "key-1");
        setRequest(request);

        when(joinPoint.getArgs()).thenReturn(new Object[] { "payload" });
        when(cacheService.get(eq(CacheKeyType.IDEMPOTENCY), eq(IdempotentRecord.class), any(String[].class)))
                .thenReturn(Optional.empty());
        when(cacheService.putIfAbsent(eq(CacheKeyType.IDEMPOTENCY), any(IdempotentRecord.class), eq(30L),
                any(String[].class))).thenReturn(false);

        Object result = aspect.around(joinPoint, idempotent);

        ResponseEntity<?> response = (ResponseEntity<?>) result;
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verify(joinPoint, never()).proceed();
    }

    @Test
    void operationThrows_releasesLockAndPropagatesException() throws Throwable {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/account/withdraw");
        request.addHeader("Idempotency-Key", "key-1");
        setRequest(request);

        when(joinPoint.getArgs()).thenReturn(new Object[] { "payload" });
        when(cacheService.get(eq(CacheKeyType.IDEMPOTENCY), eq(IdempotentRecord.class), any(String[].class)))
                .thenReturn(Optional.empty());
        when(cacheService.putIfAbsent(eq(CacheKeyType.IDEMPOTENCY), any(IdempotentRecord.class), eq(30L),
                any(String[].class))).thenReturn(true);
        when(joinPoint.proceed()).thenThrow(new RuntimeException("insufficient balance"));

        try {
            aspect.around(joinPoint, idempotent);
            throw new AssertionError("Expected exception to propagate");
        } catch (RuntimeException e) {
            assertEquals("insufficient balance", e.getMessage());
        }

        verify(cacheService, times(1)).delete(eq(CacheKeyType.IDEMPOTENCY), any(String[].class));
    }
}
