package com.aiinterview.interview.ws

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

// ── Inbound (client → server) ────────────────────────────────────────────────

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = InboundMessage.CandidateMessage::class,  name = "CANDIDATE_MESSAGE"),
    JsonSubTypes.Type(value = InboundMessage.CodeRun::class,           name = "CODE_RUN"),
    JsonSubTypes.Type(value = InboundMessage.CodeUpdate::class,        name = "CODE_UPDATE"),
    JsonSubTypes.Type(value = InboundMessage.RequestHint::class,       name = "REQUEST_HINT"),
    JsonSubTypes.Type(value = InboundMessage.EndInterview::class,      name = "END_INTERVIEW"),
    JsonSubTypes.Type(value = InboundMessage.Ping::class,              name = "PING"),
)
sealed class InboundMessage {
    data class CandidateMessage(val text: String) : InboundMessage()
    data class CodeRun(val code: String, val language: String) : InboundMessage()
    data class CodeUpdate(val code: String, val language: String) : InboundMessage()
    data class RequestHint(val hintLevel: Int = 1) : InboundMessage()
    data class EndInterview(val reason: String = "CANDIDATE_ENDED") : InboundMessage()
    class Ping : InboundMessage()
}

// ── Outbound (server → client) ───────────────────────────────────────────────

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = OutboundMessage.InterviewStarted::class,  name = "INTERVIEW_STARTED"),
    JsonSubTypes.Type(value = OutboundMessage.AiMessage::class,         name = "AI_MESSAGE"),
    JsonSubTypes.Type(value = OutboundMessage.AiChunk::class,           name = "AI_CHUNK"),
    JsonSubTypes.Type(value = OutboundMessage.StateChange::class,       name = "STATE_CHANGE"),
    JsonSubTypes.Type(value = OutboundMessage.CodeRunResult::class,     name = "CODE_RUN_RESULT"),
    JsonSubTypes.Type(value = OutboundMessage.HintResponse::class,      name = "HINT_RESPONSE"),
    JsonSubTypes.Type(value = OutboundMessage.HintDelivered::class,     name = "HINT_DELIVERED"),
    JsonSubTypes.Type(value = OutboundMessage.InterviewEnded::class,    name = "INTERVIEW_ENDED"),
    JsonSubTypes.Type(value = OutboundMessage.Error::class,             name = "ERROR"),
    JsonSubTypes.Type(value = OutboundMessage.Pong::class,              name = "PONG"),
)
sealed class OutboundMessage {
    data class InterviewStarted(val sessionId: String, val state: String) : OutboundMessage()
    data class AiMessage(val text: String, val state: String) : OutboundMessage()
    data class AiChunk(val delta: String, val done: Boolean = false) : OutboundMessage()
    data class StateChange(val state: String) : OutboundMessage()
    data class CodeRunResult(val stdout: String?, val stderr: String?, val exitCode: Int?) : OutboundMessage()
    data class HintResponse(val hint: String, val hintsGiven: Int) : OutboundMessage()
    data class HintDelivered(val hint: String, val level: Int, val hintsRemaining: Int, val refused: Boolean = false) : OutboundMessage()
    data class InterviewEnded(val reason: String, val overallScore: Double) : OutboundMessage()
    data class Error(val code: String, val message: String) : OutboundMessage()
    class Pong : OutboundMessage()
}
