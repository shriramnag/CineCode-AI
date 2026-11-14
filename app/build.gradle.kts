import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.wire)
}

val versionManifestFile = rootProject.file("version.json")

@Suppress("UNCHECKED_CAST")
val versionManifest: Map<String, Any?> = if (versionManifestFile.exists()) {
    JsonSlurper().parse(versionManifestFile) as? Map<String, Any?> ?: emptyMap()
} else {
    emptyMap()
}

val appVersionCode: Int = project.findProperty("APP_VERSION_CODE")
    ?.toString()
    ?.toIntOrNull()
    ?: (versionManifest["version_code"] as? Number)?.toInt()
    ?: 341126

val appVersionName: String = project.findProperty("APP_VERSION_NAME")
    ?.toString()
    ?.takeIf { it.isNotBlank() }
    ?: (versionManifest["version_name"] as? String)?.takeIf { it.isNotBlank() }
    ?: "0.227.543-beta"

android {
    namespace = "com.shriram.cinecode"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.shriram.cinecode"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("int", "BRIDGE_PORT", "1234")
        buildConfigField("int", "HUB_PORT", "1235")
        buildConfigField("int", "BROWSER_MCP_PORT", "8765")
        buildConfigField("boolean", "IS_SHARED_USER_ID", "false")
    }

    signingConfigs {
        create("debugConfig") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debugConfig")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

wire {
    sourcePath {
        srcDir("src/main/proto")
    }
    kotlin {
        rpcRole = "client"
    }
}

configurations.all {
    resolutionStrategy {
        force("com.squareup.okhttp3:okhttp:4.12.0")
    }
    exclude(group = "com.squareup.okhttp3", module = "okhttp-android")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.wire.runtime)
    implementation(libs.wire.grpc.client)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.jsch)
    implementation(libs.sora.editor)
    implementation(libs.sora.language.java)
    implementation(libs.commons.compress)
    implementation(libs.tukaani.xz)
    implementation(libs.jsoup)
    implementation(libs.jlatexmath)
    implementation(libs.telemetrydeck)
    implementation(libs.termux.terminal.emulator)

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
