package com.pura.ratelimiter.dto;

import jakarta.validation.constraints.NotBlank;

public record RateLimitRequest(@NotBlank String clientId, @NotBlank String resource) {}
