package com.shriram.cinecode.data.miniapps

import kotlinx.serialization.Serializable

@Serializable
data class MiniAppManifest(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val description: String = "",
    val category: String = "Utility",
    val icon: String = "🧩",
    val entry: String = "index.html",
    val accentColor: String = "#D97757"
)

data class InstalledMiniApp(
    val manifest: MiniAppManifest,
    val entryUrl: String,
    val isBuiltIn: Boolean,
    val installDirPath: String? = null
)
