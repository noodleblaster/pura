package com.pura.ratelimiter.controller;

import com.pura.ratelimiter.dto.ConfigureRequest;
import com.pura.ratelimiter.dto.ConfigureResponse;
import com.pura.ratelimiter.service.ConfigurationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ConfigureController {

  private final ConfigurationService configurationService;

  @PostMapping("/api/v1/configure")
  public ConfigureResponse configure(@Valid @RequestBody ConfigureRequest request) {
    return configurationService.applyConfiguration(request);
  }
}
