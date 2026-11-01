plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "com.example.cet6.ui.state"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":core-study"))
    api(project(":core-ui"))
    api(platform(libs.compose.bom))
    api(libs.activity.compose)
    api(libs.activity.ktx)
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    api(libs.compose.material.icons.extended)
    api(libs.lifecycle.viewmodel.compose)
    api(libs.lifecycle.viewmodel.ktx)
    api(libs.lifecycle.runtime.compose)
    api(libs.core.ktx)
    api(libs.haze)
    api(libs.markwon.core)
    api(libs.markwon.tables)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}

tasks.withType<Test>().configureEach { workingDir(rootProject.file("app")) }
