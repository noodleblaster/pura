package com.pura.ratelimiter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RateLimiterApplication {
  static void main(String[] args) {
    SpringApplication.run(RateLimiterApplication.class, args);
  }
}
