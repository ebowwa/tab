plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ebowwa.tab.core.agent"
    compileSdk = 37
    defaultConfig { minSdk = 36 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:data"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // ML Kit stays an implementation detail of this module: the feature layer sees
    // ModelDownload / GenerationResult, never an ML Kit type.
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.mlkit.genai.schema)
    testImplementation(libs.junit)
}
