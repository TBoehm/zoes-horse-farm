plugins {
    id("zhf.kmp-library")
}

// Composition root: wires store, services, crash guard, i18n, audio, input, presentation, engine and
// the Filament backend. The platform shells (iOS, Android) and the Compose UI build on it.
kotlin {
    // The iOS framework the Xcode project links. Linking needs macOS: on other hosts the link tasks are
    // not created, so `qa` (which only compiles the iOS klibs) is not affected.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ZoesHorseFarm"
            isStatic = true
            // what the shells call: key and state types of the app entry points
            export(project(":adapters:platform"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":adapters:presentation"))
            api(project(":adapters:view3d"))
            api(project(":adapters:render-filament"))
            api(project(":adapters:storage"))
            api(project(":adapters:platform"))
            // NativeSurface: the shells hand `FilamentBackendFactory` a function to a Filament surface
            api(libs.filament)
        }
        commonTest.dependencies {
            implementation(project(":core:application-testing"))
            implementation(project(":core:domain-testing"))
        }
    }
}
