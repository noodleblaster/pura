package com.pura.ratelimiter.controller

import com.pura.ratelimiter.dto.ConfigureResponse
import com.pura.ratelimiter.service.ConfigurationService
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(ConfigureController::class)
@ApplyExtension(SpringExtension::class)
class ConfigureControllerSpec : FunSpec() {
  @Autowired
  private lateinit var mockMvc: MockMvc

  @MockitoBean
  private lateinit var configurationService: ConfigurationService

  init {
    test("sets the global default and echoes it back with 200") {
      `when`(configurationService.applyConfiguration(any())).thenReturn(
        ConfigureResponse(10, 5, null, null),
      )

      mockMvc
        .perform(
          post("/api/v1/configure")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"window_seconds":10,"request_per_window":5}"""),
        ).andExpect(status().isOk)
        .andExpect(jsonPath("$.window_seconds").value(10))
        .andExpect(jsonPath("$.request_per_window").value(5))
    }

    test("rejects a non-positive window_seconds with 400") {
      mockMvc
        .perform(
          post("/api/v1/configure")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"window_seconds":0,"request_per_window":5}"""),
        ).andExpect(status().isBadRequest)
    }
  }
}
