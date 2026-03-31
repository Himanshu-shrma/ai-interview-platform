package com.aiinterview.conversation.brain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class BrainObjectivesRegistryTest {

    @Test
    fun `CODING type returns required goals with problem_shared`() {
        val goals = BrainObjectivesRegistry.forCategory("CODING")
        assertTrue(goals.required.size >= 7,
            "CODING should have at least 7 required goals, got ${goals.required.size}")
        assertTrue(goals.required.any { it.id == "problem_shared" },
            "problem_shared must be in CODING goals")
    }

    @Test
    fun `CODING type includes solution_implemented goal`() {
        val goals = BrainObjectivesRegistry.forCategory("CODING")
        assertTrue(goals.required.any { it.id == "solution_implemented" },
            "solution_implemented must be in CODING goals")
    }

    @Test
    fun `BEHAVIORAL type returns required goals`() {
        val goals = BrainObjectivesRegistry.forCategory("BEHAVIORAL")
        assertTrue(goals.required.size >= 6,
            "BEHAVIORAL should have at least 6 required goals, got ${goals.required.size}")
    }

    @Test
    fun `SYSTEM_DESIGN type returns required goals`() {
        val goals = BrainObjectivesRegistry.forCategory("SYSTEM_DESIGN")
        assertTrue(goals.required.size >= 6,
            "SYSTEM_DESIGN should have at least 6 required goals, got ${goals.required.size}")
    }

    @Test
    fun `DSA maps to same goals as CODING`() {
        val dsa = BrainObjectivesRegistry.forCategory("DSA")
        val coding = BrainObjectivesRegistry.forCategory("CODING")
        assertEquals(coding.required.size, dsa.required.size,
            "DSA should return same goal count as CODING")
    }

    @Test
    fun `unknown category returns non-empty default`() {
        val goals = BrainObjectivesRegistry.forCategory("UNKNOWN")
        assertTrue(goals.required.isNotEmpty(),
            "Unknown category should return non-empty default goals")
    }

    @Test
    fun `null category returns non-empty default`() {
        val goals = BrainObjectivesRegistry.forCategory(null)
        assertTrue(goals.required.isNotEmpty(),
            "Null category should return non-empty default goals")
    }

    @Test
    fun `all goal types have interview_closed`() {
        for (type in listOf("CODING", "BEHAVIORAL", "SYSTEM_DESIGN")) {
            val goals = BrainObjectivesRegistry.forCategory(type)
            assertTrue(goals.required.any { it.id == "interview_closed" },
                "$type should have interview_closed goal")
        }
    }
}
