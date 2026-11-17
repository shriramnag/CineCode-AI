---
name: video_styles_and_creator_skills
description: CineCode-AI Creator Studio skills: CineCameraDirector (3D/Mythological camera & lighting), LocalSandboxExecutor (execute_local_code), Shiv AI Multi-Voice Emotion Controller, fetch_epic_multimedia, find_background_assets, and auto_git_push.
---

# CineCode-AI 2050 Creative & Autonomous Agent Skills

Use these tools and directives across chat, MCP servers (`cinecode_skills_mcp`), and AI Media Studio workflows.

## 1. In-App Code Execution Sandbox (`execute_local_code`)
- **Tool:** `execute_local_code`
- **Runtime:** Python (`python`), Node.js/JS (`nodejs`), HTML/JS (`html`) inside isolated `sandbox_workspace`.
- **Self-Healing:** On syntax, indentation, or runtime errors, `LocalSandboxExecutor` classifies the error (`SYNTAX_ERROR`, `INDENTATION_ERROR`, `NAME_OR_REFERENCE_ERROR`, `MISSING_DEPENDENCY`) and triggers automatic self-healing retries (`auto_self_heal: true`).
- **Usage:** Always verify generated Python/Node/HTML scripts in the sandbox before presenting final code to the user.

## 2. Advanced Animation & Frame Director (`camera_director` / `CineCameraDirector`)
- **Tool:** `camera_director`
- **Visual Styles:** `mythological_3d` (Indian Mythological 3D IMAX), `2d_realistic` (Painted Realism), `futuristic_2050` (UE5.5 Raytraced).
- **Directives:** Automatically synthesizes camera movements (`Slow Dolly-In`, `360° Orbital Arc`, `Epic Crane-Up Reveal`, `Dramatic Low-Angle Pan`, `Multi-Layer Parallax Track`, `Intense Push-In Zoom`), ambient divine lighting (`Divine Golden Aura`, `Vedic Dawn`, `Cosmic Astral`, `Sacred Diya Glow`, `Epic Chiaroscuro`), and 3-plane foreground/midground/background environment depth.

## 3. Shiv AI Multi-Voice Emotion Controller (`TurboHindiNormalizer`)
- **Supported Inline Tags:**
  - `<emotion type="devotion">...</emotion>` — Bhakti / Sacred (Pitch `0.94`, Rate `0.88`, Reverb `+6.5 dB`)
  - `<emotion type="anger">...</emotion>` — Krodh / Fierce (Pitch `1.08`, Rate `1.14`, Reverb `+3.5 dB`)
  - `<emotion type="excitement">...</emotion>` — Utsah / Heroic (Pitch `1.10`, Rate `1.10`, Reverb `+2.5 dB`)
  - `<emotion type="fear">...</emotion>` — Bhay / Mystery (Pitch `0.86`, Rate `0.82`, Reverb `+8.0 dB`)
  - `<emotion type="serene">...</emotion>` — Shant / Narration (Pitch `1.00`, Rate `0.98`, Reverb `+1.8 dB`)
- **Breath & Pause Tags:** `<breath/>`, `<pause ms="450"/>` (auto-detected from Hindi script punctuation if omitted).

## 4. Autonomous Google Research & Media Finder (`fetch_epic_multimedia` & `find_background_assets`)
- **Tools:** `fetch_epic_multimedia`, `find_background_assets`
- **Capabilities:** Uses `GeminiSearchGroundingClient` (`gemini-4-hydrogen` + Google Search Grounding) to gather verified historical/archaeological facts, Public Domain / CC0 Wikimedia Commons & Openverse images, and royalty-free Free Music Archive scores mapped to storyboard timeline cues.

## 5. Git Automation (`auto_git_push`)
- **Tool:** `auto_git_push`
- **Capabilities:** Stages, commits, and pushes verified local workspace updates inside the user's Termux/Ubuntu git repository.
