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
    jvm()
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

// Test fixtures (:core:*-testing) bring kotlin-test with them: only test source sets may use them.
afterEvaluate {
    configurations
        .filter { conf ->
            val name = conf.name
            !name.contains("Test", ignoreCase = true) &&
                (name.endsWith("Api") || name.endsWith("Implementation") || name.endsWith("CompileOnly"))
        }.forEach { conf ->
            conf.dependencies.withType(ProjectDependency::class.java).forEach { dep ->
                check(!dep.path.endsWith("-testing")) {
                    "${project.path}: '${conf.name}' must not depend on test fixtures ${dep.path}"
                }
            }
        }
}
