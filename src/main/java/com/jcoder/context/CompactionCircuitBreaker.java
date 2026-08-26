package com.jcoder.context;

/**
 * 自动上下文压缩熔断器。
 *
 * 连续失败达到上限后，停止自动调用摘要模型，
 * 避免每轮都额外消耗一次 LLM 请求。
 */
public final class CompactionCircuitBreaker {

    public static final int DEFAULT_MAX_FAILURES = 3;

    private final int maxFailures;
    private int consecutiveFailures;

    public CompactionCircuitBreaker() {
        this(DEFAULT_MAX_FAILURES);
    }

    CompactionCircuitBreaker(int maxFailures) {
        if (maxFailures < 1) {
            throw new IllegalArgumentException(
                    "maxFailures must be positive"
            );
        }

        this.maxFailures = maxFailures;
    }

    public synchronized boolean allowAutomaticAttempt() {
        return consecutiveFailures < maxFailures;
    }

    public synchronized void recordFailure() {
        if (consecutiveFailures < maxFailures) {
            consecutiveFailures++;
        }
    }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
    }

    public synchronized boolean isOpen() {
        return consecutiveFailures >= maxFailures;
    }

    public synchronized void reset() {
        consecutiveFailures = 0;
    }

    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }

    public int maxFailures() {
        return maxFailures;
    }
}