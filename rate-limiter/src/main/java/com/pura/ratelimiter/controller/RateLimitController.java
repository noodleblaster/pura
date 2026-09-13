package com.pura.ratelimiter.controller;

import com.pura.ratelimiter.dto.RateLimitRequest;
import com.pura.ratelimiter.dto.RateLimitResponse;
import com.pura.ratelimiter.service.RateLimiterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RateLimitController {

  private final RateLimiterService rateLimiterService;

  @PostMapping("/api/v1/ratelimit")
  public RateLimitResponse checkRateLimit(@Valid @RequestBody RateLimitRequest request) {
    var decision = rateLimiterService.checkRateLimit(request.clientId(), request.resource());
    return new RateLimitResponse(
        decision.allowed(), decision.remaining(), decision.retryAfterSeconds());
  }
}
