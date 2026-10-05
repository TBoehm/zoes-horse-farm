plugins {
    id("zhf.kmp-library")
}

// Test fixtures of :core:domain (fakes, helpers). Used only as a commonTest dependency.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:domain"))
            api(kotlin("test"))
        }
    }
}
