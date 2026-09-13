package com.pura.ratelimiter.dto;

public record ConfigureResponse(
    int windowSeconds, int requestPerWindow, String clientId, String resource) {}
