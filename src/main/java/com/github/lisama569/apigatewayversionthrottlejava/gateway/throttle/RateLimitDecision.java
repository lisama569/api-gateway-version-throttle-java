package com.github.lisama569.apigatewayversionthrottlejava.gateway.throttle;

/** 限流判定结果 */
public record RateLimitDecision(boolean allowed, long retryAfterMillis) {

    public static RateLimitDecision allow() {
        return new RateLimitDecision(true, 0L);
    }

    public static RateLimitDecision reject(long retryAfterMillis) {
        return new RateLimitDecision(false, retryAfterMillis);
    }
}
