package com.aiinterview

import com.aiinterview.shared.ai.LlmProviderRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class Application(
    private val llmRegistry: LlmProviderRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun logLlmProvider() {
        log.info("LLM provider: {}", llmRegistry.getProviderName())
    }
}

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
