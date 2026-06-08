import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.dschat.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dschat.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 119
        versionName = "2.6"
        vectorDrawables { useSupportLibrary = true }

        // Bake third-party service keys from a GITIGNORED keys.properties (never in the public repo).
        // Missing file → empty strings, so a fresh clone builds a keyless app (keys entered in 设置).
        val keysFile = rootProject.file("keys.properties")
        val keysProps = Properties().apply { if (keysFile.exists()) FileInputStream(keysFile).use { load(it) } }
        fun secret(name: String): String = (keysProps.getProperty(name) ?: "").trim()
        buildConfigField("String", "QWEATHER_KEY", "\"${secret("QWEATHER_KEY")}\"")
        buildConfigField("String", "QWEATHER_HOST", "\"${secret("QWEATHER_HOST")}\"")
        buildConfigField("String", "SEARCH_KEY_BAIDU", "\"${secret("SEARCH_KEY_BAIDU")}\"")
        buildConfigField("String", "SEARCH_KEY_BOCHA", "\"${secret("SEARCH_KEY_BOCHA")}\"")
        buildConfigField("String", "SEARCH_KEY_METASO", "\"${secret("SEARCH_KEY_METASO")}\"")
    }

    buildTypes {
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // AndroidX core / lifecycle / activity / navigation
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore + encrypted prefs
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Networking (OkHttp + SSE) + JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Agent tools: HTML parsing (fetch_url / search) + sandboxed JS engine (run_javascript)
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("org.mozilla:rhino:1.7.14")

    // On-device OCR (screenshots → text), Chinese + Latin, offline. Model bundled in the APK.
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    // On-device image labeling (offline) — coarse object/scene keywords for non-vision models.
    implementation("com.google.mlkit:image-labeling:17.0.9")

    // Offline PDF text extraction (Apache PDFBox port for Android). DOCX is parsed from its own
    // XML with no extra dependency (see DocumentTextExtractor).
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Background periodic work for the proactive weather monitor (survives reboot + Doze).
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // SSH/SFTP client for "control PC" mode (maintained JSch fork; modern algos, key gen, Android-friendly).
    implementation("com.github.mwiede:jsch:0.2.21")
}
