plugins {
    alias(libs.plugins.android.library)
    // Required: LedgerStore persists a @Serializable document of its own.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ebowwa.tab.core.data"
    compileSdk = 37
    defaultConfig { minSdk = 36 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
