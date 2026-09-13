package com.pura.ratelimiter.dto;

public record RateLimitResponse(boolean allowed, int remaining, Long retryAfter) {}
