package com.aiinterview.interview.dto

import com.aiinterview.interview.model.Question
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Returned to candidates — hides all solver-facing fields.
 * Fields excluded: optimal_approach, solution_hints, test_cases,
 *                  follow_up_prompts, evaluation_criteria, generation_params
 */
data class CandidateQuestionDto(
    val id: UUID?,
    val title: String,
    val description: String,
    val category: String,
    val difficulty: String,
    val topicTags: List<String>?,
    val examples: JsonNode?,
    val constraintsText: String?,
    val slug: String?,
    val timeComplexity: String?,
    val spaceComplexity: String?,
    val createdAt: OffsetDateTime?,
)

/**
 * Returned to admin / used internally by LLM agents — includes all fields.
 */
data class InternalQuestionDto(
    val id: UUID?,
    val title: String,
    val description: String,
    val category: String,
    val type: String,
    val difficulty: String,
    val topicTags: List<String>?,
    val examples: JsonNode?,
    val constraintsText: String?,
    val testCases: JsonNode?,
    val solutionHints: JsonNode?,
    val optimalApproach: String?,
    val followUpPrompts: JsonNode?,
    val evaluationCriteria: JsonNode?,
    val timeComplexity: String?,
    val spaceComplexity: String?,
    val slug: String?,
    val source: String,
    val generationParams: JsonNode?,
    val createdAt: OffsetDateTime?,
)

// ── Extension functions ────────────────────────────────────────────────────────

fun Question.toCandidateDto(objectMapper: ObjectMapper) = CandidateQuestionDto(
    id             = id,
    title          = title,
    description    = description,
    category       = category,
    difficulty     = difficulty,
    topicTags      = topicTags?.toList(),
    examples       = examples?.let { runCatching { objectMapper.readTree(it) }.getOrNull() },
    constraintsText = constraintsText,
    slug           = slug,
    timeComplexity = timeComplexity,
    spaceComplexity = spaceComplexity,
    createdAt      = createdAt,
)

fun Question.toInternalDto(objectMapper: ObjectMapper) = InternalQuestionDto(
    id                = id,
    title             = title,
    description       = description,
    category          = category,
    type              = type,
    difficulty        = difficulty,
    topicTags         = topicTags?.toList(),
    examples          = examples?.let { runCatching { objectMapper.readTree(it) }.getOrNull() },
    constraintsText   = constraintsText,
    testCases         = testCases?.let { runCatching { objectMapper.readTree(it) }.getOrNull() },
    solutionHints     = solutionHints?.let { runCatching { objectMapper.readTree(it) }.getOrNull() },
    optimalApproach   = optimalApproach,
    followUpPrompts   = followUpPrompts?.let { runCatching { objectMapper.readTree(it) }.getOrNull() },
    evaluationCriteria = evaluationCriteria?.let { runCatching { objectMapper.readTree(it) }.getOrNull() },
    timeComplexity    = timeComplexity,
    spaceComplexity   = spaceComplexity,
    slug              = slug,
    source            = source,
    generationParams  = generationParams?.let { runCatching { objectMapper.readTree(it) }.getOrNull() },
    createdAt         = createdAt,
)
