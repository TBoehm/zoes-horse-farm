pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "zoes-horse-farm"

// Clean Architecture as Gradle modules: a module can only use what it declares, so the
// dependency direction (adapters -> application -> domain -> shared) is enforced by the build.
include(
    ":core:shared",
    ":core:domain",
    ":core:application",
    ":core:domain-testing",
    ":core:application-testing",
    ":adapters:scene",
    ":adapters:view3d",
    ":adapters:render-filament",
    ":adapters:audio",
    ":adapters:storage",
    ":adapters:platform",
    ":adapters:input",
    ":adapters:i18n",
)
