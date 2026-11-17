package com.example.gemini.data.media

object TurboHindiNormalizer {

    enum class VoiceEmotion(
        val tag: String,
        val labelHi: String,
        val pitch: Float,
        val speechRate: Float,
        val reverbDb: Float,
        val breathPauseMs: Int
    ) {
        DEVOTION("devotion", "भक्ति / पावन", 0.94f, 0.88f, 6.5f, 450),
        ANGER("anger", "क्रोध / रौद्र", 1.08f, 1.14f, 3.5f, 220),
        EXCITEMENT("excitement", "उत्साह / वीर रस", 1.10f, 1.10f, 2.5f, 280),
        FEAR("fear", "भय / रहस्य", 0.86f, 0.82f, 8.0f, 520),
        SERENE("serene", "शांत / कथावाचन", 1.00f, 0.98f, 1.8f, 350)
    }

    data class EmotionalVoiceScript(
        val emotion: VoiceEmotion,
        val normalizedText: String,
        val taggedDialogue: String,
        val pitch: Float,
        val speechRate: Float,
        val reverbDb: Float,
        val pauseCount: Int,
        val breathCount: Int
    )

    fun normalizeScript(rawText: String): String {
        var processed = rawText
            // Strip raw inline control tags if normalizing plain spoken text
            .replace(Regex("</?emotion[^>]*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<pause[^>]*/>|\\[pause\\]", RegexOption.IGNORE_CASE), ", ")
            .replace(Regex("<breath\\s*/?>|\\[breath\\]", RegexOption.IGNORE_CASE), ", ")
            // Replace common numbers with spoken words or clear separation
            .replace("2032", "दो हज़ार बत्तीस")
            .replace("2050", "दो हज़ार पचास")
            .replace("2026", "दो हज़ार छब्बीस")
            .replace("108", "एक सौ आठ")
            .replace("4GB", "चार जीबी")
            .replace("AI", "एआई")
            // Clean up stutter-inducing excessive dots or symbols
            .replace("...", "।")
            .replace("..", "।")
            .replace("?.", "?")
            .replace("!.", "!")
            .trim()

        // Ensure proper Hindi purna viram ( । ) instead of English dots in mid sentences
        if (processed.isNotEmpty() && !processed.endsWith("।") && !processed.endsWith("?") && !processed.endsWith("!")) {
            processed += "।"
        }

        return processed
    }

    fun detectEmotion(text: String): VoiceEmotion {
        val explicitTag = Regex("<emotion\\s+(?:type|name|value)?\\s*=?\\s*['\"]?([a-zA-Z_]+)['\"]?\\s*>", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.lowercase()
        when (explicitTag) {
            "anger", "krodh", "rage", "fierce" -> return VoiceEmotion.ANGER
            "devotion", "bhakti", "sacred", "prayer" -> return VoiceEmotion.DEVOTION
            "excitement", "utsah", "heroic", "joy" -> return VoiceEmotion.EXCITEMENT
            "fear", "bhay", "horror", "mystery" -> return VoiceEmotion.FEAR
            "serene", "calm", "narrative" -> return VoiceEmotion.SERENE
        }

        val lower = text.lowercase()
        return when {
            listOf("क्रोध", "रौद्र", "युद्ध", "विनाश", "संहार", "सावधान", "आक्रोश", "गर्जना", "war", "rage", "angry")
                .any { lower.contains(it) } -> VoiceEmotion.ANGER
            listOf("भक्ति", "प्रभु", "महादेव", "शिव", "कृष्ण", "राम", "मंत्र", "आरती", "देव", "पावन", "प्रणाम", "divine", "temple", "bhakti")
                .any { lower.contains(it) } -> VoiceEmotion.DEVOTION
            listOf("उत्साह", "विजय", "जय", "चलो", "अद्भुत", "कमाल", "क्रांति", "भविष्य", "2050", "victory", "epic")
                .any { lower.contains(it) } -> VoiceEmotion.EXCITEMENT
            listOf("भय", "डर", "रहस्य", "अंधेरा", "सन्नाटा", "खतरा", "साया", "डरपोक", "fear", "shadow", "dark")
                .any { lower.contains(it) } -> VoiceEmotion.FEAR
            else -> VoiceEmotion.SERENE
        }
    }

    /**
     * Shiv AI Multi-Voice Emotion Controller: inspects emotional cues, applies <emotion>, <pause>, <breath>
     * directives, and computes vocal pitch, speed rate, and background reverb depth.
     */
    fun processEmotionalDialogue(rawText: String): EmotionalVoiceScript {
        val emotion = detectEmotion(rawText)
        val explicitPauses = Regex("<pause[^>]*/>|\\[pause\\]", RegexOption.IGNORE_CASE).findAll(rawText).count()
        val explicitBreaths = Regex("<breath\\s*/?>|\\[breath\\]", RegexOption.IGNORE_CASE).findAll(rawText).count()

        val normalized = normalizeScript(rawText)
        val sentenceSplit = normalized.split("।").filter { it.isNotBlank() }
        val autoPauses = (sentenceSplit.size - 1).coerceAtLeast(1)

        val tagged = buildString {
            append("<emotion type=\"${emotion.tag}\" pitch=\"${emotion.pitch}\" rate=\"${emotion.speechRate}\" reverbDb=\"${emotion.reverbDb}\">")
            sentenceSplit.forEachIndexed { idx, clause ->
                append(clause.trim()).append("।")
                if (idx < sentenceSplit.lastIndex) {
                    append(" <breath/> <pause ms=\"${emotion.breathPauseMs}\"/> ")
                }
            }
            append("</emotion>")
        }

        return EmotionalVoiceScript(
            emotion = emotion,
            normalizedText = normalized,
            taggedDialogue = tagged,
            pitch = emotion.pitch,
            speechRate = emotion.speechRate,
            reverbDb = emotion.reverbDb,
            pauseCount = maxOf(explicitPauses, autoPauses),
            breathCount = maxOf(explicitBreaths, (sentenceSplit.size - 1).coerceAtLeast(0))
        )
    }

    fun generateAudioFileName(title: String): String {
        val sanitized = title.take(25)
            .replace(Regex("[^a-zA-Z0-9्अ-हॉॉाीूूृेैोौंः\\s]"), "")
            .replace("\\s+".toRegex(), "_")
        return "Shiv_AI_Realistic_${sanitized}_${System.currentTimeMillis()}.wav"
    }
}

