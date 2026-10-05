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
    }
}
