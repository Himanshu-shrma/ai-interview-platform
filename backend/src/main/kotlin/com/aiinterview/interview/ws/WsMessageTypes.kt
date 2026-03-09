package com.aiinterview.interview.ws

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.UUID

// ── Inbound (client → server) ────────────────────────────────────────────────

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = InboundMessage.CandidateMessage::class,  name = "CANDIDATE_MESSAGE"),
    JsonSubTypes.Type(value = InboundMessage.CodeRun::class,           name = "CODE_RUN"),
    JsonSubTypes.Type(value = InboundMessage.CodeSubmit::class,        name = "CODE_SUBMIT"),
    JsonSubTypes.Type(value = InboundMessage.CodeUpdate::class,        name = "CODE_UPDATE"),
    JsonSubTypes.Type(value = InboundMessage.RequestHint::class,       name = "REQUEST_HINT"),
    JsonSubTypes.Type(value = InboundMessage.EndInterview::class,      name = "END_INTERVIEW"),
    JsonSubTypes.Type(value = InboundMessage.Ping::class,              name = "PING"),
)
sealed class InboundMessage {
    data class CandidateMessage(
        val text: String,
        val codeSnapshot: CodeSnapshot? = null,
    ) : InboundMessage()
    data class CodeRun(val code: String, val language: String, val stdin: String? = null) : InboundMessage()
    data class CodeSubmit(val code: String, val language: String, val sessionQuestionId: UUID, val stdin: String? = null) : InboundMessage()
    data class CodeUpdate(val code: String, val language: String) : InboundMessage()
    data class RequestHint(val hintLevel: Int = 1) : InboundMessage()
    data class EndInterview(val reason: String = "CANDIDATE_ENDED") : InboundMessage()
    class Ping : InboundMessage()
}

// ── Outbound (server → client) ───────────────────────────────────────────────

/** Per-test-case result for CODE_SUBMIT responses. */
data class TestResult(
    val passed: Boolean,
    val input: String?,
    val expected: String?,
    val actual: String?,
    val runtimeMs: Long?,
)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = OutboundMessage.InterviewStarted::class,  name = "INTERVIEW_STARTED"),
    JsonSubTypes.Type(value = OutboundMessage.AiMessage::class,         name = "AI_MESSAGE"),
    JsonSubTypes.Type(value = OutboundMessage.AiChunk::class,           name = "AI_CHUNK"),
    JsonSubTypes.Type(value = OutboundMessage.StateChange::class,       name = "STATE_CHANGE"),
    JsonSubTypes.Type(value = OutboundMessage.CodeRunResult::class,     name = "CODE_RUN_RESULT"),
    JsonSubTypes.Type(value = OutboundMessage.CodeResult::class,        name = "CODE_RESULT"),
    JsonSubTypes.Type(value = OutboundMessage.HintResponse::class,      name = "HINT_RESPONSE"),
    JsonSubTypes.Type(value = OutboundMessage.HintDelivered::class,     name = "HINT_DELIVERED"),
    JsonSubTypes.Type(value = OutboundMessage.QuestionTransition::class, name = "QUESTION_TRANSITION"),
    JsonSubTypes.Type(value = OutboundMessage.InterviewEnded::class,    name = "INTERVIEW_ENDED"),
    JsonSubTypes.Type(value = OutboundMessage.SessionEnd::class,        name = "SESSION_END"),
    JsonSubTypes.Type(value = OutboundMessage.StateSync::class,        name = "STATE_SYNC"),
    JsonSubTypes.Type(value = OutboundMessage.Error::class,             name = "ERROR"),
    JsonSubTypes.Type(value = OutboundMessage.Pong::class,              name = "PONG"),
)
sealed class OutboundMessage {
    data class InterviewStarted(val sessionId: String, val state: String) : OutboundMessage()
    data class AiMessage(val text: String, val state: String) : OutboundMessage()
    data class AiChunk(val delta: String, val done: Boolean = false) : OutboundMessage()
    data class StateChange(val state: String) : OutboundMessage()
    /** Legacy — kept for backward compat. New code uses CodeResult. */
    data class CodeRunResult(val stdout: String?, val stderr: String?, val exitCode: Int?) : OutboundMessage()
    /** Unified result for both CODE_RUN and CODE_SUBMIT. */
    data class CodeResult(
        val status: String,
        val stdout: String?,
        val stderr: String?,
        val runtimeMs: Long?,
        val testResults: List<TestResult>? = null,
    ) : OutboundMessage()
    data class HintResponse(val hint: String, val hintsGiven: Int) : OutboundMessage()
    data class HintDelivered(val hint: String, val level: Int, val hintsRemaining: Int, val refused: Boolean = false) : OutboundMessage()
    /** Sent when transitioning from Q1 to Q2 — carries new question details. */
    data class QuestionTransition(
        val questionIndex: Int,
        val questionTitle: String,
        val questionDescription: String,
        val codeTemplates: Map<String, String>? = null,
    ) : OutboundMessage()
    data class InterviewEnded(val reason: String, val overallScore: Double) : OutboundMessage()
    /** Sent when evaluation report is ready — carries reportId for redirect. */
    data class SessionEnd(val reportId: UUID) : OutboundMessage()
    /** Sent on WS reconnect — full state recovery for the frontend. */
    data class StateSync(
        val state: String,
        val currentQuestionIndex: Int,
        val totalQuestions: Int,
        val currentQuestion: QuestionSnapshot?,
        val currentCode: String?,
        val programmingLanguage: String?,
        val hintsGiven: Int,
        val messages: List<MessageSnapshot>,
        val showCodeEditor: Boolean,
    ) : OutboundMessage()
    data class Error(val code: String, val message: String) : OutboundMessage()
    class Pong : OutboundMessage()
}

/** Lightweight question snapshot for STATE_SYNC. */
data class QuestionSnapshot(
    val title: String,
    val description: String,
    val codeTemplates: Map<String, String>? = null,
)

/** Lightweight message snapshot for STATE_SYNC. */
data class MessageSnapshot(
    val role: String,
    val content: String,
)

/** Code editor state sent with every CANDIDATE_MESSAGE for AI awareness. */
data class CodeSnapshot(
    val content: String?,
    val language: String?,
    val hasMeaningfulCode: Boolean = false,
    val lineCount: Int = 0,
    val hasRunResults: Boolean = false,
    val lastRunPassed: Boolean? = null,
)
