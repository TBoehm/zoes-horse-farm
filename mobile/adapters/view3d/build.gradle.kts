plugins {
    id("zhf.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":adapters:scene"))
            api(project(":core:application"))
        }
        commonTest.dependencies {
            implementation(project(":core:domain-testing"))
            implementation(project(":core:application-testing"))
        }
    }
}
