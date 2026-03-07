package com.aiinterview.shared.ai

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@ConfigurationProperties(prefix = "llm.resolved")
@Component
data class ModelConfig(
    val interviewerModel: String = "gpt-4o",
    val backgroundModel: String = "gpt-4o-mini",
    val generatorModel: String = "gpt-4o",
    val evaluatorModel: String = "gpt-4o",
)
