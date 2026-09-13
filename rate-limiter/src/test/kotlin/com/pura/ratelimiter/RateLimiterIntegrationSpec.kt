package com.pura.ratelimiter

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ApplyExtension(SpringExtension::class)
class RateLimiterIntegrationSpec : FunSpec() {
  @Autowired
  private lateinit var restTemplate: TestRestTemplate

  @LocalServerPort
  var port: Int = 0

  private fun jsonEntity(body: String): HttpEntity<String> {
    val headers = HttpHeaders()
    headers.contentType = MediaType.APPLICATION_JSON
    return HttpEntity(body, headers)
  }

  init {
    test("configuring a client+resource override then exceeding it blocks with 200 and allowed=false") {
      val configureResponse =
        restTemplate.postForEntity(
          "http://localhost:$port/api/v1/configure",
          jsonEntity(
            """{"window_seconds":10,"request_per_window":2,"client_id":"integration-client","resource":"orders"}""",
          ),
          String::class.java,
        )
      configureResponse.statusCode.value() shouldBe 200

      val ratelimitEntity = jsonEntity("""{"client_id":"integration-client","resource":"orders"}""")
      val url = "http://localhost:$port/api/v1/ratelimit"

      val first = restTemplate.postForEntity(url, ratelimitEntity, String::class.java)
      val second = restTemplate.postForEntity(url, ratelimitEntity, String::class.java)
      val third = restTemplate.postForEntity(url, ratelimitEntity, String::class.java)

      first.statusCode.value() shouldBe 200
      first.body!! shouldContain "\"allowed\":true"
      second.body!! shouldContain "\"allowed\":true"
      third.statusCode.value() shouldBe 200
      third.body!! shouldContain "\"allowed\":false"
    }
  }
}
