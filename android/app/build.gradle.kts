import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val serverEnvironmentFile = rootProject.file("../server.env")
require(serverEnvironmentFile.isFile) { "Copy server.env.example to server.env and set ZENPTT_DOMAIN" }
val serverEnvironment = serverEnvironmentFile.readLines()
val serverDomain = serverEnvironment.firstOrNull()
    ?.takeIf { it.startsWith("ZENPTT_DOMAIN=") }
    ?.substringAfter('=')
require(!serverDomain.isNullOrBlank() && serverDomain.matches(Regex("^[A-Za-z0-9.-]+$"))) {
    "The first server.env line must contain a valid ZENPTT_DOMAIN"
}
fun historicalAddress(name: String): String {
    val value = serverEnvironment.firstOrNull { it.startsWith("$name=") }?.substringAfter('=') ?: ""
    require(value.isEmpty() || value.matches(Regex("^wss?://[A-Za-z0-9.-]+(?::[0-9]{1,5})?$"))) {
        "$name must be an empty value or a ws:// or wss:// server address"
    }
    return value
}
val legacyServerAddress = historicalAddress("ZENPTT_ANDROID_LEGACY_SERVER_ADDRESS")
val preSnapshotServerAddress = historicalAddress("ZENPTT_ANDROID_PRE_SNAPSHOT_SERVER_ADDRESS")
val signingFile = rootProject.file("signing.properties")
val signingProperties = Properties()
if (signingFile.isFile) {
    signingFile.inputStream().use(signingProperties::load)
    for (name in listOf("storeFile", "storePassword", "keyAlias", "keyPassword")) {
        require(!signingProperties.getProperty(name).isNullOrBlank()) {
            "android/signing.properties must define $name"
        }
    }
    require(rootProject.file(signingProperties.getProperty("storeFile")).isFile) {
        "The signing key named by android/signing.properties does not exist"
    }
}

android {
    namespace = "app.zenptt"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "app.zenptt"
        minSdk = 31
        targetSdk = 35
        versionCode = 115
        versionName = "0.14.4"
        buildConfigField("String", "DEFAULT_SERVER_ADDRESS", "\"wss://$serverDomain\"")
        buildConfigField("String", "LEGACY_SERVER_ADDRESS", "\"$legacyServerAddress\"")
        buildConfigField("String", "PRE_SNAPSHOT_SERVER_ADDRESS", "\"$preSnapshotServerAddress\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    if (signingFile.isFile) signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file(signingProperties.getProperty("storeFile"))
            storePassword = signingProperties.getProperty("storePassword")
            keyAlias = signingProperties.getProperty("keyAlias")
            keyPassword = signingProperties.getProperty("keyPassword")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    ndkVersion = "27.0.12077973"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        dex.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

configurations.configureEach {
    exclude(group = "androidx.compose.material", module = "material-icons-core")
}

dependencies {
    implementation(project(":headset"))
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
