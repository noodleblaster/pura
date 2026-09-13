package com.pura.ratelimiter.model;

public record RateLimitRule(int windowSeconds, int requestPerWindow) {}
