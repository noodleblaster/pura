package com.pura.ratelimiter.model;

public record RateLimitDecision(boolean allowed, int remaining, Long retryAfterSeconds) {}
