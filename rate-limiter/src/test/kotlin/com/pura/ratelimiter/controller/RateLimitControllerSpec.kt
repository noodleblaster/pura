package com.pura.ratelimiter.controller

import com.pura.ratelimiter.model.RateLimitDecision
import com.pura.ratelimiter.service.RateLimiterService
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(RateLimitController::class)
@ApplyExtension(SpringExtension::class)
class RateLimitControllerSpec : FunSpec() {
  @Autowired
  private lateinit var mockMvc: MockMvc

  @MockitoBean
  private lateinit var rateLimiterService: RateLimiterService

  init {
    test("returns 200 with allowed=true when under the limit") {
      `when`(rateLimiterService.checkRateLimit(eq("client-a"), eq("orders")))
        .thenReturn(RateLimitDecision(true, 4, null))

      mockMvc
        .perform(
          post("/api/v1/ratelimit")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"client_id":"client-a","resource":"orders"}"""),
        ).andExpect(status().isOk)
        .andExpect(jsonPath("$.allowed").value(true))
        .andExpect(jsonPath("$.remaining").value(4))
        .andExpect(jsonPath("$.retry_after").doesNotExist())
    }

    test("returns 200 (not 429) with allowed=false and retry_after when over the limit") {
      `when`(rateLimiterService.checkRateLimit(eq("client-a"), eq("orders")))
        .thenReturn(RateLimitDecision(false, 0, 6L))

      mockMvc
        .perform(
          post("/api/v1/ratelimit")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"client_id":"client-a","resource":"orders"}"""),
        ).andExpect(status().isOk)
        .andExpect(jsonPath("$.allowed").value(false))
        .andExpect(jsonPath("$.retry_after").value(6))
    }

    test("rejects a request with a blank client_id with 400") {
      mockMvc
        .perform(
          post("/api/v1/ratelimit")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"client_id":"","resource":"orders"}"""),
        ).andExpect(status().isBadRequest)
    }
  }
}
