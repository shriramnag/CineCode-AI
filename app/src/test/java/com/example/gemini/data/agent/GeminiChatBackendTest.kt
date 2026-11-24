package com.shriram.cinecode.data.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiChatBackendTest {

    @Test
    fun testSSEChunkTextExtraction() {
        val jsonChunk = """
            {
                "candidates": [
                    {
                        "content": {
                            "parts": [
                                {
                                    "text": "नमस्ते! CineCode-AI में आपका स्वागत है।"
                                }
                            ]
                        }
                    }
                ]
            }
        """.trimIndent()

        val root = JSONObject(jsonChunk)
        val candidates = root.getJSONArray("candidates")
        val candidate = candidates.getJSONObject(0)
        val content = candidate.getJSONObject("content")
        val parts = content.getJSONArray("parts")
        val text = parts.getJSONObject(0).getString("text")

        assertEquals("नमस्ते! CineCode-AI में आपका स्वागत है।", text)
    }

    @Test
    fun testMultiPartDeltaExtraction() {
        val jsonChunk = """
            {
                "candidates": [
                    {
                        "content": {
                            "parts": [
                                { "text": "Part 1 " },
                                { "text": "Part 2" }
                            ]
                        }
                    }
                ]
            }
        """.trimIndent()

        val root = JSONObject(jsonChunk)
        val parts = root.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            sb.append(parts.getJSONObject(i).getString("text"))
        }

        assertEquals("Part 1 Part 2", sb.toString())
    }

    @Test
    fun testErrorMessageParsing() {
        val errorJson = """
            {
                "error": {
                    "code": 403,
                    "message": "API_KEY_INVALID",
                    "status": "PERMISSION_DENIED"
                }
            }
        """.trimIndent()

        val root = JSONObject(errorJson)
        val err = root.getJSONObject("error").getString("message")
        assertEquals("API_KEY_INVALID", err)
    }
}
