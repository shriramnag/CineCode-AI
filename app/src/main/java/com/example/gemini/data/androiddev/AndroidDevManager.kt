package com.shriram.cinecode.data.androiddev

import android.content.Context
import com.shriram.cinecode.data.local.LocalEnvironmentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class AndroidToolchainStatus(
    val hasJava: Boolean = false,
    val hasGradle: Boolean = false,
    val hasAapt2: Boolean = false,
    val hasD8: Boolean = false,
    val hasAdb: Boolean = false,
    val summary: String = ""
)

data class AndroidScaffoldResult(
    val projectDir: String,
    val packageName: String,
    val filesCreated: Int
)

/**
 * On-phone Android App Builder manager for CineCode-AI.
 * Scaffolds lightweight Jetpack Compose Android apps inside workspace and checks installed local toolchain.
 */
object AndroidDevManager {

    suspend fun inspectToolchain(context: Context): AndroidToolchainStatus = withContext(Dispatchers.IO) {
        val binDir = LocalEnvironmentManager.getBinDir(context)
        fun hasTool(name: String): Boolean =
            File(binDir, name).exists() || File("/data/data/com.termux/files/usr/bin/$name").exists()

        val cmdCheck = runCatching {
            LocalEnvironmentManager.executeCommand(
                command = "command -v java gradle aapt2 d8 adb 2>/dev/null || true",
                context = context,
                timeoutSeconds = 10
            ).output
        }.getOrDefault("")

        val hasJava = hasTool("java") || cmdCheck.contains("java")
        val hasGradle = hasTool("gradle") || cmdCheck.contains("gradle")
        val hasAapt2 = hasTool("aapt2") || cmdCheck.contains("aapt2")
        val hasD8 = hasTool("d8") || cmdCheck.contains("d8")
        val hasAdb = hasTool("adb") || cmdCheck.contains("adb")

        val summary = buildString {
            append("Java: ").append(if (hasJava) "Ready" else "Missing")
            append(" | Gradle: ").append(if (hasGradle) "Ready" else "Missing")
            append(" | aapt2: ").append(if (hasAapt2) "Ready" else "Missing")
            append(" | d8: ").append(if (hasD8) "Ready" else "Missing")
        }
        AndroidToolchainStatus(hasJava, hasGradle, hasAapt2, hasD8, hasAdb, summary)
    }

    suspend fun scaffoldComposeApp(
        context: Context,
        appName: String,
        packageName: String
    ): Result<AndroidScaffoldResult> = withContext(Dispatchers.IO) {
        runCatching {
            val safeName = appName.trim().replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "CineApp" }
            val cleanPkg = packageName.trim().lowercase().replace(Regex("[^a-z0-9.]"), "")
                .ifBlank { "com.cinecode.app" }
            val projectsRoot = LocalEnvironmentManager.getProjectsDir(context).apply { mkdirs() }
            val root = File(projectsRoot, safeName)
            val pkgPath = cleanPkg.replace('.', '/')

            val mainDir = File(root, "app/src/main/java/$pkgPath").apply { mkdirs() }
            val resValues = File(root, "app/src/main/res/values").apply { mkdirs() }
            val appDir = File(root, "app").apply { mkdirs() }

            File(root, "settings.gradle.kts").writeText(
                """
                pluginManagement {
                    repositories { google(); mavenCentral(); gradlePluginPortal() }
                }
                dependencyResolutionManagement {
                    repositories { google(); mavenCentral() }
                }
                rootProject.name = "$safeName"
                include(":app")
                """.trimIndent()
            )

            File(root, "build.gradle.kts").writeText(
                """
                plugins {
                    id("com.android.application") version "8.7.3" apply false
                    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
                    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
                }
                """.trimIndent()
            )

            File(appDir, "build.gradle.kts").writeText(
                """
                plugins {
                    id("com.android.application")
                    id("org.jetbrains.kotlin.android")
                    id("org.jetbrains.kotlin.plugin.compose")
                }
                android {
                    namespace = "$cleanPkg"
                    compileSdk = 35
                    defaultConfig {
                        applicationId = "$cleanPkg"
                        minSdk = 24
                        targetSdk = 35
                        versionCode = 1
                        versionName = "1.0"
                    }
                    compileOptions {
                        sourceCompatibility = JavaVersion.VERSION_17
                        targetCompatibility = JavaVersion.VERSION_17
                    }
                }
                dependencies {
                    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
                    implementation("androidx.activity:activity-compose:1.9.3")
                    implementation("androidx.compose.material3:material3")
                }
                """.trimIndent()
            )

            File(root, "app/src/main/AndroidManifest.xml").writeText(
                """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application android:label="@string/app_name">
                        <activity android:name=".MainActivity" android:exported="true">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN" />
                                <category android:name="android.intent.category.LAUNCHER" />
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
                """.trimIndent()
            )

            File(resValues, "strings.xml").writeText(
                """
                <resources>
                    <string name="app_name">$safeName</string>
                </resources>
                """.trimIndent()
            )

            File(mainDir, "MainActivity.kt").writeText(
                """
                package $cleanPkg

                import android.os.Bundle
                import androidx.activity.ComponentActivity
                import androidx.activity.compose.setContent
                import androidx.compose.foundation.layout.*
                import androidx.compose.material3.*
                import androidx.compose.ui.Alignment
                import androidx.compose.ui.Modifier
                import androidx.compose.ui.unit.dp

                class MainActivity : ComponentActivity() {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        setContent {
                            MaterialTheme {
                                Surface(modifier = Modifier.fillMaxSize()) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(24.dp)) {
                                        Text("Built with CineCode-AI On-Phone Builder", style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                            }
                        }
                    }
                }
                """.trimIndent()
            )

            AndroidScaffoldResult(root.absolutePath, cleanPkg, 6)
        }
    }

    fun buildCommandForProject(projectDir: String): String =
        "cd \"${projectDir.replace("\"", "\\\"")}\" && gradle assembleDebug --no-daemon --max-workers=2"
}
