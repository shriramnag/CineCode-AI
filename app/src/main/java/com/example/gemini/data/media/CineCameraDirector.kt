package com.shriram.cinecode.data.media

import org.json.JSONArray
import org.json.JSONObject

/**
 * CineCameraDirector: Generates 3D Cinematic and Indian Mythological camera movement,
 * volumetric lighting, and environment depth prompts for video & storyboard pipelines.
 */
object CineCameraDirector {

    enum class CameraMovement(val label: String, val promptDirective: String) {
        SLOW_DOLLY_IN("Slow Dolly-In", "smooth slow 24fps anamorphic dolly-in toward the focal subject"),
        ORBITAL_ARC("360° Orbital Arc", "sweeping 180-to-360 degree orbital gimbal arc around the hero"),
        CRANE_UP_REVEAL("Epic Crane-Up Reveal", "majestic crane-up tilt reveal expanding into vast architectural scale"),
        LOW_ANGLE_PAN("Dramatic Low-Angle Pan", "heroic low-angle upward pan emphasizing divine stature and grandeur"),
        PARALLAX_TRACK("Multi-Layer Parallax Track", "lateral tracking shot with strong foreground-to-background parallax"),
        PUSH_IN_ZOOM("Intense Push-In Zoom", "dramatic slow push-in zoom building emotional tension")
    }

    enum class AmbientLighting(val label: String, val promptDirective: String) {
        DIVINE_GOLDEN_AURA("दैवीय स्वर्ण आभा (Divine Golden Aura)", "radiant golden halo rim-lighting with heavenly volumetric god-rays"),
        VEDIC_DAWN("वैदिक सूर्योदय (Vedic Dawn)", "warm saffron sunrise haze piercing through morning mist and temple spires"),
        COSMIC_ASTRAL("ब्रह्मांडीय नील तेज (Cosmic Astral)", "deep indigo nebula glow with luminous celestial cyan and gold particles"),
        TEMPLE_DIYA_GLOW("दीपक स्वर्ण प्रकाश (Sacred Diya Glow)", "flickering warm oil-lamp amber highlights contrasted with cool stone shadows"),
        BATTLE_CHIAROSCURO("रणभूमि नाटकीय प्रकाश (Epic Chiaroscuro)", "high-contrast storm clouds, lightning rim sparks, and dramatic ember glow")
    }

    data class CameraDirectionPlan(
        val scenePrompt: String,
        val style: String,
        val movement: CameraMovement,
        val lighting: AmbientLighting,
        val environmentDepth: String,
        val lensSpec: String,
        val masterVideoPrompt: String
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("scenePrompt", scenePrompt)
            put("style", style)
            put("cameraMovement", movement.label)
            put("lighting", lighting.label)
            put("environmentDepth", environmentDepth)
            put("lensSpec", lensSpec)
            put("masterVideoPrompt", masterVideoPrompt)
        }
    }

    fun directScene(
        sceneDescription: String,
        style: String = "mythological_3d",
        preferredMovement: String? = null,
        preferredLighting: String? = null
    ): CameraDirectionPlan {
        val lower = "$sceneDescription $style".lowercase()

        val movement = when {
            !preferredMovement.isNullOrBlank() -> CameraMovement.entries.firstOrNull {
                it.name.equals(preferredMovement, ignoreCase = true) ||
                    it.label.contains(preferredMovement, ignoreCase = true)
            } ?: CameraMovement.CRANE_UP_REVEAL
            lower.contains("temple") || lower.contains("मंदिर") || lower.contains("palace") || lower.contains("कुरुक्षेत्र") ->
                CameraMovement.CRANE_UP_REVEAL
            lower.contains("warrior") || lower.contains("battle") || lower.contains("युद्ध") || lower.contains("शिव") || lower.contains("shiva") ->
                CameraMovement.ORBITAL_ARC
            lower.contains("forest") || lower.contains("jungle") || lower.contains("हवाई") || lower.contains("mountain") ->
                CameraMovement.PARALLAX_TRACK
            lower.contains("close") || lower.contains("dialogue") || lower.contains("emotion") || lower.contains("क्रोध") ->
                CameraMovement.PUSH_IN_ZOOM
            else -> CameraMovement.SLOW_DOLLY_IN
        }

        val lighting = when {
            !preferredLighting.isNullOrBlank() -> AmbientLighting.entries.firstOrNull {
                it.name.equals(preferredLighting, ignoreCase = true) ||
                    it.label.contains(preferredLighting, ignoreCase = true)
            } ?: AmbientLighting.DIVINE_GOLDEN_AURA
            lower.contains("cosmos") || lower.contains("universe") || lower.contains("space") || lower.contains("ब्रह्मांड") || lower.contains("2050") ->
                AmbientLighting.COSMIC_ASTRAL
            lower.contains("war") || lower.contains("storm") || lower.contains("क्रोध") || lower.contains("battle") ->
                AmbientLighting.BATTLE_CHIAROSCURO
            lower.contains("diya") || lower.contains("aarti") || lower.contains("आरती") || lower.contains("temple") || lower.contains("मंदिर") ->
                AmbientLighting.TEMPLE_DIYA_GLOW
            lower.contains("dawn") || lower.contains("sunrise") || lower.contains("सूर्य") ->
                AmbientLighting.VEDIC_DAWN
            else -> AmbientLighting.DIVINE_GOLDEN_AURA
        }

        val styleDescriptor = when (style.lowercase()) {
            "2d_realistic", "2d" -> "hyper-detailed 2D realistic painted cinematic illustration, painterly micro-textures"
            "futuristic_2050", "scifi" -> "2050 neo-futuristic photorealistic 8K Unreal Engine 5.5 raytraced render"
            else -> "photorealistic 3D Indian Mythological epic cinema, Unreal Engine 5 volumetric grandeur, 8K IMAX clarity"
        }

        val environmentDepth = "3-plane atmospheric depth: floating sacred dust & golden embers in foreground, " +
            "crisp carved stone / hero focal subject in midground, receding misty Himalayan & temple silhouettes in deep background"

        val lensSpec = "35mm ARRI Alexa Anamorphic f/1.4, creamy optical bokeh, natural film grain, 24fps cinematic motion blur"

        val masterPrompt = buildString {
            append(sceneDescription.trim())
            append(". Style: ").append(styleDescriptor)
            append(". Camera: ").append(movement.promptDirective)
            append(". Lighting: ").append(lighting.promptDirective)
            append(". Depth: ").append(environmentDepth)
            append(". Optics: ").append(lensSpec).append(".")
        }

        return CameraDirectionPlan(
            scenePrompt = sceneDescription.trim(),
            style = style,
            movement = movement,
            lighting = lighting,
            environmentDepth = environmentDepth,
            lensSpec = lensSpec,
            masterVideoPrompt = masterPrompt
        )
    }

    fun directStoryboard(scriptText: String, style: String = "mythological_3d"): List<CameraDirectionPlan> {
        val sentences = scriptText.split(Regex("[.!?।\n]+")).map { it.trim() }.filter { it.isNotBlank() }
        if (sentences.isEmpty()) return listOf(directScene(scriptText.ifBlank { "Epic 3D Mythological Opening Scene" }, style))
        return sentences.mapIndexed { idx, line ->
            val fallbackMove = CameraMovement.entries[idx % CameraMovement.entries.size].name
            directScene(line, style, preferredMovement = fallbackMove)
        }
    }

    fun storyboardAsJson(scriptText: String, style: String = "mythological_3d"): JSONArray =
        JSONArray().apply {
            directStoryboard(scriptText, style).forEachIndexed { idx, plan ->
                put(plan.toJson().put("sceneIndex", idx + 1).put("timestamp", "00:${String.format("%02d", idx * 4)}"))
            }
        }
}
