package com.subjex.platform.app.delivery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SamplePathCircuitBreakerTest {

    @Test
    void consecutiveFailuresOpenTheBreakerAndSuccessClearsTheStreak() {
        SamplePathCircuitBreaker breaker = new SamplePathCircuitBreaker(3);
        assertTrue(breaker.allowCall());
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();
        breaker.recordFailure();
        breaker.recordFailure();
        assertFalse(breaker.isOpen());
        breaker.recordFailure();
        assertTrue(breaker.isOpen());
        assertFalse(breaker.allowCall());
    }
}
