plugins {
    id("zhf.kmp-library")
}

// Composition root: wires store, services, crash guard, i18n, audio, input, presentation, engine and
// the Filament backend. The platform shells (iOS, Android) and the Compose UI build on it.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":adapters:presentation"))
            api(project(":adapters:view3d"))
            api(project(":adapters:render-filament"))
            api(project(":adapters:storage"))
        }
        commonTest.dependencies {
            implementation(project(":core:application-testing"))
        }
    }
}
