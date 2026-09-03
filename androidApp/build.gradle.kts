import java.util.Base64

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

// ── Release version + signing ────────────────────────────────────────────────
// `versionName` comes from the git tag: CI passes `-PreleaseVersionName=$GITHUB_REF_NAME`
// (the tag, e.g. `app-v0.7.2`); a plain local build falls back to the constant below.
// `versionCode` is DERIVED from it (major*10000 + minor*100 + patch) so it is
// monotonic with the tag and never has to be hand-bumped.
val releaseVersionName: String =
    providers.gradleProperty("releaseVersionName").orNull
        ?.trim()?.removePrefix("app-v")?.takeIf { it.isNotEmpty() }
        ?: "0.7.2"

fun versionCodeOf(name: String): Int {
    val parts = name.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    return parts.getOrElse(0) { 0 } * 10_000 + parts.getOrElse(1) { 0 } * 100 + parts.getOrElse(2) { 0 }
}

// Signing material is read from the environment (CI: repo secrets) or gradle
// properties (locally: ~/.gradle/gradle.properties). NOTHING is ever committed —
// `*.jks` is git-ignored and the keystore is materialised into build/ from base64.
// The keys live at ~/.config/rarebit-android-signing/ and in 1Password (Sysadmins).
fun releaseSecret(env: String, property: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty(property).orNull?.takeIf { it.isNotBlank() }

val releaseKeystoreBase64 = releaseSecret("RELEASE_KEYSTORE_BASE64", "release.keystoreBase64")
val releaseKeystorePassword = releaseSecret("RELEASE_KEYSTORE_PASSWORD", "release.keystorePassword")
val releaseKeyAlias = releaseSecret("RELEASE_KEY_ALIAS", "release.keyAlias")
val releaseKeyPassword = releaseSecret("RELEASE_KEY_PASSWORD", "release.keyPassword")

// Present only when the base64 keystore was supplied; otherwise the release build
// type stays unsigned (a local `assembleRelease` still works, it just isn't signed).
val releaseKeystore: File? = releaseKeystoreBase64?.let { encoded ->
    layout.buildDirectory.file("release-signing/release.jks").get().asFile.apply {
        parentFile.mkdirs()
        writeBytes(Base64.getMimeDecoder().decode(encoded))
    }
}

android {
    namespace = "one.rarebit.cruciform"
    compileSdk = 35

    defaultConfig {
        applicationId = "one.rarebit.cruciform"
        // minSdk 33 mirrors the library: StrongBox (API 28+) plus the modern
        // BiometricPrompt/CryptoObject API and a provider that carries Ed25519.
        minSdk = 33
        targetSdk = 35
        versionCode = versionCodeOf(releaseVersionName)
        versionName = releaseVersionName

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

    signingConfigs {
        create("release") {
            // Left unconfigured (and unreferenced) when no keystore was supplied.
            releaseKeystore?.let { keystore ->
                storeFile = keystore
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // minify stays OFF until proguard rules exist for the reflective bits.
            isMinifyEnabled = false
            signingConfig = releaseKeystore?.let { signingConfigs.getByName("release") }
            // A RELEASE build is the real authenticator: force the hardware-backed
            // DeviceVoidbindEngine regardless of `-PdeviceEngine`. The preview engine
            // is a debug/CI affordance and must never ship in a signed release.
            buildConfigField("boolean", "USE_DEVICE_ENGINE", "true")
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
    // The invite state machine (pairing/InviteCoordinator) is driven on a test dispatcher.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// RpPairManifestQueriesTest reads the manifest at runtime; make it a task input so an
// edit to <queries> re-runs the unit tests instead of hitting Gradle's up-to-date cache.
tasks.withType<Test>().configureEach {
    inputs.file(layout.projectDirectory.file("src/main/AndroidManifest.xml"))
        .withPropertyName("androidManifestForQueriesTest")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
