import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Convention for every module: Kotlin Multiplatform with
// - jvm: fast unit tests on any host (CI, Linux), not shipped
// - iosArm64 / iosSimulatorArm64: the iOS app (klibs compile on any host, linking needs macOS)
// - android: added once the Android Gradle Plugin is available (needs Google Maven + Android SDK)
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jlleitschuh.gradle.ktlint")
}

kotlin {
    jvmToolchain(21)
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    compilerOptions {
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

ktlint {
    version.set("1.8.0")
}
