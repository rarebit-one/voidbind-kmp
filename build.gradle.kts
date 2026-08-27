plugins {
    // Kotlin 2.3.x: cryptography-kotlin 0.6.0 (the release that adds Ed25519 +
    // X25519 across JDK/CryptoKit/OpenSSL) ships 2.3.x metadata, so the project
    // compiler must match. AGP bumped to a version compatible with Gradle 8.9.
    kotlin("multiplatform") version "2.3.20"
    id("com.android.library") version "8.7.3"
}

group = "one.rarebit.voidbind"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    google()
}

// cryptography-kotlin (whyoleg) supplies SOFTWARE Ed25519 + X25519 across every
// target — including Kotlin/Native (iOS), where there is no java.security. It
// delegates to vetted primitives per platform (JDK on JVM/Android, CryptoKit on
// Apple). The HARDWARE wrapping key that seals the Ed25519 seed stays platform
// native (AndroidKeyStore / Secure Enclave) and never touches this library.
// See docs/adr/0001-hardware-keystore-mechanism.md.
val cryptographyVersion = "0.6.0"

kotlin {
    // `expect`/`actual` classes are a Beta feature we lean on for DeviceKeyStore;
    // opt in to silence the (expected) stability warning.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // JVM — dev/test target (software key; isHardwareBacked = false).
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    // Android — StrongBox/TEE-backed AES-GCM seal over a software Ed25519 key.
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // iOS — Secure-Enclave-P256 ECIES seal over a software Ed25519 key. Building
    // the Native targets needs the Kotlin/Native toolchain (auto-downloaded); the
    // Secure Enclave `actual` uses the Security framework via the K/N platform libs.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation("dev.whyoleg.cryptography:cryptography-core:$cryptographyVersion")
            // The "optimal" provider selects the best backend per target: the JDK
            // provider on JVM/Android, CryptoKit on Apple. One dependency, no
            // per-target wiring, and Ed25519/X25519 are supported on all of them
            // (cryptography-kotlin 0.6.0).
            implementation("dev.whyoleg.cryptography:cryptography-provider-optimal:$cryptographyVersion")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        val androidMain by getting {
            dependencies {
                implementation("androidx.biometric:biometric:1.1.0")
            }
        }
    }
}

android {
    namespace = "one.rarebit.voidbind"
    compileSdk = 35
    defaultConfig {
        // minSdk 33: StrongBox (API 28+) plus a platform that reliably carries
        // Ed25519 in its provider and the modern BiometricPrompt/CryptoObject API.
        minSdk = 33
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
