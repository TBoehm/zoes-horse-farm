plugins {
    id("zhf.kmp-library")
    id("zhf.kmp-serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:application"))
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
