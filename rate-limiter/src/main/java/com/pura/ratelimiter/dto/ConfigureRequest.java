package com.pura.ratelimiter.dto;

import jakarta.validation.constraints.Positive;

public record ConfigureRequest(
    @Positive int windowSeconds,
    @Positive int requestPerWindow,
    String clientId,
    String resource) {}
