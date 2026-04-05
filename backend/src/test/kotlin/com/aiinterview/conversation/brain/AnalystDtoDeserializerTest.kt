package com.aiinterview.conversation.brain

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AnalystDtoDeserializerTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `NewHypothesisDto with missing fields parses with defaults`() {
        val json = """{"claim":"candidate knows BFS"}"""
        val dto = mapper.readValue(json, NewHypothesisDto::class.java)
        assertEquals("candidate knows BFS", dto.claim)
        assertEquals(0.6f, dto.confidence)
        assertEquals("", dto.testStrategy)
        assertEquals(3, dto.priority)
    }

    @Test
    fun `ExchangeScoreDto with null dimension parses gracefully`() {
        val json = """{"turn":5,"score":7.5,"evidence":"good answer"}"""
        val dto = mapper.readValue(json, ExchangeScoreDto::class.java)
        assertEquals("", dto.dimension)
        assertEquals(7.5f, dto.score)
        assertEquals("good answer", dto.evidence)
    }

    @Test
    fun `HypothesisUpdateDto with unknown fields ignores them`() {
        val json = """{"id":"h_3_0","newEvidence":"confirmed by code","newStatus":"confirmed","unknownField":"ignored"}"""
        val dto = mapper.readValue(json, HypothesisUpdateDto::class.java)
        assertEquals("h_3_0", dto.id)
        assertEquals("confirmed", dto.newStatus)
        assertEquals("confirmed by code", dto.newEvidence)
    }

    @Test
    fun `AnalystDecision with malformed partial JSON still parses known fields`() {
        val json = """{"goalsCompleted":["problem_shared"],"thoughtThreadAppend":"good progress","unknownGarbage":123}"""
        val dto = mapper.readValue(json, AnalystDecision::class.java)
        assertEquals(listOf("problem_shared"), dto.goalsCompleted)
        assertEquals("good progress", dto.thoughtThreadAppend)
        assertNull(dto.nextAction)
        assertTrue(dto.newClaims.isEmpty())
    }

    @Test
    fun `NewClaimDto handles string form via custom deserializer`() {
        // The custom NewClaimDtoDeserializer handles bare strings
        val json = """{"newClaims":["BFS has O(V+E) time"],"goalsCompleted":[]}"""
        val dto = mapper.readValue(json, AnalystDecision::class.java)
        assertEquals(1, dto.newClaims.size)
        assertEquals("BFS has O(V+E) time", dto.newClaims[0].claim)
        assertEquals("general", dto.newClaims[0].topic)
    }

    @Test
    fun `NewClaimDto handles object form`() {
        val json = """{"newClaims":[{"claim":"uses hash set","topic":"data-structures","correctness":"correct"}],"goalsCompleted":[]}"""
        val dto = mapper.readValue(json, AnalystDecision::class.java)
        assertEquals(1, dto.newClaims.size)
        assertEquals("uses hash set", dto.newClaims[0].claim)
        assertEquals("data-structures", dto.newClaims[0].topic)
        assertEquals("correct", dto.newClaims[0].correctness)
    }

    @Test
    fun `CandidateProfileUpdateDto with all nulls parses to defaults`() {
        val json = """{}"""
        val dto = mapper.readValue(json, CandidateProfileUpdateDto::class.java)
        assertNull(dto.thinkingStyle)
        assertNull(dto.overallSignal)
        assertNull(dto.anxietyLevel)
        assertNull(dto.flowState)
    }

    @Test
    fun `ZdpUpdateDto with missing fields uses defaults`() {
        val json = """{"topic":"sorting"}"""
        val dto = mapper.readValue(json, ZdpUpdateDto::class.java)
        assertEquals("sorting", dto.topic)
        assertFalse(dto.canDoAlone)
        assertFalse(dto.canDoWithPrompt)
    }
}
