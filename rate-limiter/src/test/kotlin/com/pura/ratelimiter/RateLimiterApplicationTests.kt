package com.pura.ratelimiter

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
@ApplyExtension(SpringExtension::class)
class RateLimiterApplicationTests : FunSpec() {
  init {
    test("the application context loads") {
      // SpringBootTest fails this test itself if the context can't start.
    }
  }
}
