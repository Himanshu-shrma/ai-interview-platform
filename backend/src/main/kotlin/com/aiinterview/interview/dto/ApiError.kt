package com.aiinterview.interview.dto

import java.time.Instant

data class ApiError(
    val error: String,
    val message: String,
    val timestamp: String = Instant.now().toString(),
)
