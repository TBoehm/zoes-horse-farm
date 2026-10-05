plugins {
    id("zhf.kmp-library")
}

// Test fixtures of :core:application (fakes, helpers). Used only as a commonTest dependency.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:application"))
            api(kotlin("test"))
        }
    }
}
