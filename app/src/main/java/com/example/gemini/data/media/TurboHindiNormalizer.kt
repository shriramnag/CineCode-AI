package com.example.gemini.data.media

object TurboHindiNormalizer {
    fun normalizeScript(rawText: String): String {
        var processed = rawText
            // Replace common numbers with spoken words or clear separation
            .replace("2032", "दो हज़ार बत्तीस")
            .replace("2050", "दो हज़ार पचास")
            .replace("108", "एक सौ आठ")
            .replace("4GB", "चार जीबी")
            .replace("AI", "एआई")
            // Clean up stutter-inducing excessive dots or symbols
            .replace("...", "।")
            .replace("..", "।")
            .replace("?.", "?")
            .replace("!.", "!")

        // Ensure proper Hindi purna viram ( । ) instead of English dots in mid sentences
        if (!processed.endsWith("।") && !processed.endsWith("?") && !processed.endsWith("!")) {
            processed += "।"
        }

        return processed
    }

    fun generateAudioFileName(title: String): String {
        val sanitized = title.take(25)
            .replace(Regex("[^a-zA-Z0-9्अ-हॉॉाीूूृेैोौंः\\s]"), "")
            .replace("\\s+".toRegex(), "_")
        return "Shiv_AI_Realistic_${sanitized}_${System.currentTimeMillis()}.wav"
    }
}
