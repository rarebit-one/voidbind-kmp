import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    // Kotlin 2.3.x: cryptography-kotlin 0.6.0 (the release that adds Ed25519 +
    // X25519 across JDK/CryptoKit/OpenSSL) ships 2.3.x metadata, so the project
    // compiler must match. AGP bumped to a version compatible with Gradle 8.9.
    kotlin("multiplatform") version "2.3.20"
    id("com.android.library") version "8.7.3"
    // Publishes the shared client (identity/net/flow wire brain + DeviceKeyStore
    // seam) as the consumable `voidbind-client` artifact to GitHub Packages, so
    // relying-party apps (allthing-android, heyarr-mobile) depend on it over the
    // wire instead of re-implementing the login seam. See "Consuming as a
    // dependency" in README.md.
    id("maven-publish")
}

group = "one.rarebit.voidbind"
// Bump on any wire-affecting or API-affecting change; the coordinates are
// `one.rarebit.voidbind:voidbind-client`. 0.2.0 adds `VoidbindDeepLink` (the
// same-device app-to-app handoff URI, ADR-0003). 0.2.1 adds the non-throwing
// `*Catching` pairing steps + `PairingOutcome` (a relay you cannot reach is an
// error the UI renders, not a crash) and the typed `RelayHttpException`.
version = "0.2.1"

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
        // Publish only the release variant of the Android artifact
        // (`voidbind-client-android`) — deterministic, and what a consuming app
        // resolves. Debug is a build-time concern of the consuming app.
        publishLibraryVariants("release")
    }

    // iOS — Secure-Enclave-P256 ECIES seal over a software Ed25519 key. Building
    // the Native targets needs the Kotlin/Native toolchain (auto-downloaded); the
    // Secure Enclave `actual` uses the Security framework via the K/N platform libs.
    //
    // The iOS SwiftUI app links this as a single **XCFramework** named `Voidbind`
    // (device arm64 + simulator arm64 slices). `./gradlew assembleVoidbindXCFramework`
    // produces `build/XCFrameworks/{debug,release}/Voidbind.xcframework`; the Swift
    // app imports `Voidbind` and provides the `SecureEnclaveSealer` via
    // `VoidbindIos.init(...)`. Dynamic (default) is fine — the framework carries the
    // cryptography-kotlin/CryptoKit backend with it.
    val xcf = XCFramework("Voidbind")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Voidbind"
            xcf.add(this)
        }
    }

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

// ── Publishing: `voidbind-client` on GitHub Packages ───────────────────────────
// The shared client — the pure `commonMain` identity/net/flow wire brain
// (UserIdentity, DeviceIdentity, Enrolment, RelayClient, WebLoginClient,
// NotifyClient, PairflowInitiator/Responder, VoidbindCertSealer, the
// LoginApproval/DevicePairing/DeviceAuthorization coordinators, LoginQr/WebLogin,
// challenge-v2 number-match) plus the `DeviceKeyStore` hardware seam — is this KMP
// module. The per-app hardware wiring (Android `VoidbindAndroid.init(context)` +
// BiometricPrompt, iOS `SecureEnclaveSealer` via `VoidbindIos`) stays in each
// consuming app; the published artifact is the shared wire/flow brain, not the app.
//
// The Kotlin Multiplatform plugin creates one publication per target; we only
// rename each artifactId from the project name (`voidbind-kmp`) to
// `voidbind-client`, giving:
//   one.rarebit.voidbind:voidbind-client                    (root Gradle-metadata)
//   one.rarebit.voidbind:voidbind-client-jvm
//   one.rarebit.voidbind:voidbind-client-android
//   one.rarebit.voidbind:voidbind-client-iosarm64
//   one.rarebit.voidbind:voidbind-client-iossimulatorarm64
// Consumers depend on the root coordinate and Gradle resolves the right variant.
publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("voidbind-client")
            description.set(
                "Voidbind shared client — the Kotlin Multiplatform identity/net/flow " +
                    "wire brain (login/pairing/authorization) plus the DeviceKeyStore " +
                    "hardware seam. The consumable side of voidbind-kmp.",
            )
            url.set("https://github.com/rarebit-one/voidbind-kmp")
            licenses {
                license {
                    name.set("The Apache Software License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/rarebit-one/voidbind-kmp")
            credentials {
                // CI provides GITHUB_ACTOR/GITHUB_TOKEN; locally, GPR_USER/GPR_TOKEN
                // (a PAT with read:packages / write:packages) fall back in.
                username = System.getenv("GITHUB_ACTOR") ?: findProperty("gpr.user") as String?
                    ?: System.getenv("GPR_USER")
                password = System.getenv("GITHUB_TOKEN") ?: findProperty("gpr.token") as String?
                    ?: System.getenv("GPR_TOKEN")
            }
        }
    }
}

// Rename every publication's artifactId from the project name (`voidbind-kmp`) to
// `voidbind-client`. This runs in `afterEvaluate` because the Android Gradle
// Plugin sets the Android variant publication's artifactId in its OWN
// `afterEvaluate` (registered when the plugin is applied, i.e. earlier than this
// block) — so a plain `configureEach` rename is overwritten back to
// `voidbind-kmp-android`. Renaming here, after all plugin `afterEvaluate` hooks,
// makes every target — including `-android` — consistently `voidbind-client-*`,
// and keeps the root Gradle-module metadata's variant coordinates in sync.
afterEvaluate {
    publishing.publications.withType<MavenPublication>().configureEach {
        artifactId = artifactId.replace(rootProject.name, "voidbind-client")
    }
}
