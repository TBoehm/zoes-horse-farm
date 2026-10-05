plugins {
    id("zhf.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":adapters:scene"))
            implementation(libs.filament)
            implementation(libs.filamat)
        }
        commonTest.dependencies {
            // the real world of the game is the best test scene for the mapping (test only)
            implementation(project(":adapters:view3d"))
            implementation(project(":core:domain"))
        }
    }
}
