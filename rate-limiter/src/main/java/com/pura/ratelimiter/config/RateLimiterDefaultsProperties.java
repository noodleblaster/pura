package com.pura.ratelimiter.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ratelimiter.default")
public record RateLimiterDefaultsProperties(
    @Positive int windowSeconds, @Positive int requestPerWindow) {}
