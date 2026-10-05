plugins {
    id("zhf.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":adapters:scene"))
            api(project(":core:application"))
        }
    }
}
