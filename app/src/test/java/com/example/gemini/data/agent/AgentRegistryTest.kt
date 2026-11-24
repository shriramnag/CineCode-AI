package com.shriram.cinecode.data.agent

import com.shriram.cinecode.domain.model.AgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRegistryTest {

    @Test
    fun testAllSevenAgentsRegistered() {
        val allAgents = AgentRegistry.getAllAgents()
        assertEquals("Exactly 7 specialized agents must be registered", 7, allAgents.size)

        val kinds = allAgents.map { it.kind }.toSet()
        assertTrue(kinds.contains(AgentKind.AGY))
        assertTrue(kinds.contains(AgentKind.GEMINI))
        assertTrue(kinds.contains(AgentKind.CLAUDE))
        assertTrue(kinds.contains(AgentKind.CODEX))
        assertTrue(kinds.contains(AgentKind.OPENCODE))
        assertTrue(kinds.contains(AgentKind.TERMINAL))
        assertTrue(kinds.contains(AgentKind.JARVIS))
    }

    @Test
    fun testGeminiAgentMetadata() {
        val gemini = AgentRegistry.getMetadata(AgentKind.GEMINI)
        assertEquals("Gemini Agent", gemini.displayName)
        assertEquals("gemini-3.8-flash", gemini.defaultModel)
        assertTrue("Gemini must require API key", gemini.requiresApiKey)
        assertEquals("gemini", gemini.providerKey)
        assertTrue(gemini.supportedModels.contains("gemini-3.8-flash"))
        assertTrue(gemini.supportedModels.contains("gemini-3.5-flash-lite"))
    }

    @Test
    fun testTerminalAgentSafetyMetadata() {
        val terminal = AgentRegistry.getMetadata(AgentKind.TERMINAL)
        assertEquals("Terminal Agent", terminal.displayName)
        assertTrue("Terminal agent must mandate approval for command execution", terminal.requiresApprovalForExecution)
    }

    @Test
    fun testJarvisAgentVoiceMetadata() {
        val jarvis = AgentRegistry.getMetadata(AgentKind.JARVIS)
        assertEquals("Jarvis Voice Agent", jarvis.displayName)
        assertTrue("Jarvis agent must support voice interaction", jarvis.supportsVoice)
    }
}
