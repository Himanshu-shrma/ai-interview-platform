package com.aiinterview

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class HealthControllerTest {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Test
    fun `GET health returns 200`() {
        webTestClient.get()
            .uri("/health")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").exists()
            .jsonPath("$.db").exists()
            .jsonPath("$.redis").exists()
    }
}
