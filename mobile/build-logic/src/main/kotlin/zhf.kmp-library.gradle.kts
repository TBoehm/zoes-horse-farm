// Convention for every module: Kotlin Multiplatform with
// - jvm: fast unit tests on any host (CI, Linux), not shipped
// - iosArm64 / iosSimulatorArm64: the iOS app (klibs compile on any host, linking needs macOS)
// - android: added once the Android Gradle Plugin is available (needs Google Maven + Android SDK)
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jlleitschuh.gradle.ktlint")
    id("dev.detekt")
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

// Allocation tests must see what Kotlin/Native and ART allocate: HotSpot's escape analysis would
// remove short-lived boxes (e.g. from function types with primitive arguments) and hide them.
tasks.withType<Test>().configureEach {
    jvmArgs("-XX:-DoEscapeAnalysis")
}

ktlint {
    version.set("1.8.0")
}

// detekt: static analysis (complexity, potential bugs, style); formatting stays with ktlint.
detekt {
    buildUponDefaultConfig.set(true)
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    parallel.set(true)
    // the plain `detekt` task (no type resolution) covers every KMP source set of the module
    source.setFrom(
        listOf("commonMain", "commonTest", "jvmMain", "jvmTest", "iosMain", "iosTest", "androidMain")
            .map { file("src/$it/kotlin") }
            .filter { it.exists() },
    )
}

// Test fixtures (:core:*-testing) bring kotlin-test with them: only test source sets (and other
// fixture modules) may use them.
afterEvaluate {
    if (project.name.endsWith("-testing")) return@afterEvaluate
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
