// Root project: ktlint for the root scripts, the purity check of domain/application and the
// aggregate gate task `qa` (the same command runs locally and in CI).
plugins {
    id("org.jlleitschuh.gradle.ktlint")
}

ktlint {
    version.set("1.8.0")
}

val forbiddenCalls =
    tasks.register<ForbiddenCallsCheck>("forbiddenCallsCheck") {
        sources.from(
            layout.projectDirectory.dir("core/domain/src"),
            layout.projectDirectory.dir("core/application/src"),
        )
        report.set(layout.buildDirectory.file("reports/forbidden-calls.txt"))
    }

val gateTasks =
    listOf(
        "ktlintCheck",
        "jvmTest",
        "compileKotlinIosArm64",
        "compileKotlinIosSimulatorArm64",
        "compileTestKotlinIosArm64",
        "compileTestKotlinIosSimulatorArm64",
    )

tasks.register("qa") {
    group = "verification"
    description = "All gates of the native app: lint/format, unit tests, iOS compilation, purity check."
    dependsOn(forbiddenCalls, "ktlintCheck", gradle.includedBuild("build-logic").task(":ktlintCheck"))
    // container projects (:core, :adapters) have no build file and no tasks
    val modules = subprojects.filter { it.buildFile.exists() }
    dependsOn(modules.flatMap { sub -> gateTasks.map { "${sub.path}:$it" } })
}
