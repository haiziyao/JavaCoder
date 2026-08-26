package com.jcoder.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactionCircuitBreakerTest {

    @Test
    void initiallyAllowsAutomaticAttempt() {
        CompactionCircuitBreaker breaker = new CompactionCircuitBreaker();

        assertTrue(breaker.allowAutomaticAttempt());
        assertFalse(breaker.isOpen());
    }

    @Test
    void opensAfterThreeConsecutiveFailures() {
        CompactionCircuitBreaker breaker = new CompactionCircuitBreaker();

        breaker.recordFailure();
        assertTrue(breaker.allowAutomaticAttempt());
        assertFalse(breaker.isOpen());

        breaker.recordFailure();
        assertTrue(breaker.allowAutomaticAttempt());
        assertFalse(breaker.isOpen());

        breaker.recordFailure();
        assertFalse(breaker.allowAutomaticAttempt());
        assertTrue(breaker.isOpen());
        assertEquals(3, breaker.consecutiveFailures());
    }

    @Test
    void successClearsAccumulatedFailures() {
        CompactionCircuitBreaker breaker = new CompactionCircuitBreaker();
        breaker.recordFailure();
        breaker.recordFailure();

        breaker.recordSuccess();

        assertEquals(0, breaker.consecutiveFailures());
        assertTrue(breaker.allowAutomaticAttempt());
        assertFalse(breaker.isOpen());

        breaker.recordFailure();
        breaker.recordFailure();
        assertTrue(breaker.allowAutomaticAttempt(),
                "a success must restart the consecutive-failure count");
    }

    @Test
    void resetClosesAnOpenBreaker() {
        CompactionCircuitBreaker breaker = new CompactionCircuitBreaker();
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();
        assertTrue(breaker.isOpen());

        breaker.reset();

        assertEquals(0, breaker.consecutiveFailures());
        assertFalse(breaker.isOpen());
        assertTrue(breaker.allowAutomaticAttempt());
    }
}
