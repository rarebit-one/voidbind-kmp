plugins {
    // AGP and the Kotlin Gradle plugin are already on the build classpath from the
    // root library (com.android.library 8.7.3 shares the AGP jar that also carries
    // com.android.application; kotlin("multiplatform") 2.3.20 carries kotlin.android).
    // Re-declaring a version here conflicts ("already on the classpath"), so apply
    // them version-less. The Compose compiler plugin is NOT on the classpath yet, so
    // it alone carries the version — matched to Kotlin 2.3.20.
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
}

android {
    namespace = "one.rarebit.voidbind.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "one.rarebit.voidbind"
        // minSdk 33 mirrors the library: StrongBox (API 28+) plus the modern
        // BiometricPrompt/CryptoObject API and a provider that carries Ed25519.
        minSdk = 33
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.1"

        // Engine selection at BUILD time, no source edit: `-PdeviceEngine=true` (or
        // `deviceEngine=true` in gradle.properties) selects the real hardware-backed
        // DeviceVoidbindEngine; the default (false) keeps the PreviewVoidbindEngine so
        // CI and UI review never depend on a StrongBox/TEE device.
        val deviceEngine = providers.gradleProperty("deviceEngine").map { it.toBoolean() }.getOrElse(false)
        buildConfigField("boolean", "USE_DEVICE_ENGINE", deviceEngine.toString())
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // The shared KMP library at the repo root: wire contract + hardware DeviceKeyStore.
    implementation(project(":"))

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.biometric:biometric:1.1.0")
    // A modern fragment so FragmentActivity (needed by BiometricPrompt) extends the
    // androidx.activity.ComponentActivity that activity-compose's setContent requires
    // (biometric 1.1.0 alone pulls an older fragment).
    implementation("androidx.fragment:fragment:1.8.3")

    // QR scanning: CameraX preview + analysis, ML Kit barcode decoding.
    val cameraX = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // QR generation (invite codes this device displays) — ML Kit only decodes.
    implementation("com.google.zxing:core:3.5.3")

    // The device engine's HttpTransport actual (relay + RP calls).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Pure-JVM unit tests (deep-link routing); no Android runtime needed.
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}
