plugins {
    `kotlin-dsl`
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

ktlint {
    version.set("1.8.0")
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.plugin)
    implementation(libs.ktlint.gradle.plugin)
}

// kotlin-dsl adds generated accessors (build/) to the main source set: lint only our own sources
listOf("runKtlintCheckOverMainSourceSet", "runKtlintFormatOverMainSourceSet").forEach { name ->
    tasks.named<org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask>(name) {
        setSource(fileTree("src/main/kotlin"))
    }
}
