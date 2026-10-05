plugins {
    id("zhf.kmp-library")
}

// Screen logic without a UI toolkit (navigation, menus, settings, notices, course plan, HUD models):
// the Compose UI renders these models and forwards user actions.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:application"))
            api(project(":adapters:i18n"))
            api(project(":adapters:input"))
            api(project(":adapters:platform"))
            api(project(":adapters:audio"))
        }
        commonTest.dependencies {
            implementation(project(":core:application-testing"))
        }
    }
}
