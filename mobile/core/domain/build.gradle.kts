plugins {
    id("zhf.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:shared"))
        }
        commonTest.dependencies {
            implementation(project(":core:domain-testing"))
        }
    }
}
