package com.aiinterview.shared.ai

object LlmCostEstimator {
    fun estimateCost(model: String, promptTokens: Int, completionTokens: Int): Double = when {
        model.contains("gpt-4o-mini") -> (promptTokens * 0.00000015) + (completionTokens * 0.0000006)
        model.contains("gpt-4o")     -> (promptTokens * 0.0000025)  + (completionTokens * 0.00001)
        else                          -> 0.0
    }
}
