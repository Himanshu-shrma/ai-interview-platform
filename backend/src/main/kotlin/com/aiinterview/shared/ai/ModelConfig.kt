package com.aiinterview.shared.ai

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@ConfigurationProperties(prefix = "llm.resolved")
@Component
data class ModelConfig(
    var interviewerModel: String = "gpt-4o",
    var backgroundModel: String = "gpt-4o-mini",
    var generatorModel: String = "gpt-4o",
    var evaluatorModel: String = "gpt-4o",
)
