package com.aiinterview.shared.ai

import kotlinx.coroutines.flow.Flow

interface LlmProvider {

    suspend fun complete(request: LlmRequest): LlmResponse

    fun stream(request: LlmRequest): Flow<String>

    fun providerName(): String

    suspend fun healthCheck(): Boolean
}
