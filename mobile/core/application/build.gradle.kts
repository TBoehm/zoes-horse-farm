plugins {
    id("zhf.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:domain"))
        }
        commonTest.dependencies {
            implementation(project(":core:application-testing"))
            // the session-level course rider builds on the domain autopilot
            implementation(project(":core:domain-testing"))
        }
    }
}
