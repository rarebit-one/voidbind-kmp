plugins {
    kotlin("multiplatform") version "2.0.21"
}

group = "one.rarebit.voidbind"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    google()
}

kotlin {
    // `expect`/`actual` classes are a Beta feature we lean on for DeviceKeyStore;
    // opt in to silence the (expected) stability warning.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // JVM is the primary buildable + testable target in this environment
    // (no Android SDK here, so no androidTarget; that would need AGP + the SDK).
    jvm {
        // Toolchain: JDK 21 is installed host-native.
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    // iOS targets are declared for structure. Building/testing them needs the
    // Kotlin/Native toolchain (auto-downloaded) and, for device builds, Xcode.
    // The verified build in CI/dev here is JVM + common only. The default
    // hierarchy template auto-creates the shared `iosMain` / `iosTest` source
    // sets that both iOS targets depend on (src/iosMain holds the Secure Enclave
    // `actual`).
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
