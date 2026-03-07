package com.aiinterview.shared.ai

import kotlinx.coroutines.runBlocking
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component

@Component
class LlmProviderHealthIndicator(
    private val registry: LlmProviderRegistry,
) : HealthIndicator {

    override fun health(): Health {
        val provider = registry.primary()
        val healthy = runBlocking {
            try {
                provider.healthCheck()
            } catch (_: Exception) {
                false
            }
        }
        return if (healthy) {
            Health.up()
                .withDetail("provider", registry.getProviderName())
                .build()
        } else {
            Health.down()
                .withDetail("provider", registry.getProviderName())
                .build()
        }
    }
}
