plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "com.example.cet6"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.cet6"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "0.6.0-paper-ink"
        testInstrumentationRunner = "com.example.cet6.LocalDataInstrumentation"
    }
    buildFeatures { compose = true }
    // Personal third-party imports must not be distributed inside the APK.
    androidResources {
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:CVS:thumbs.db:picasa.ini:*~:exam-packs"
    }
    sourceSets.getByName("main").java.srcDir("../third_party/bee-fsrs/src")
    sourceSets.getByName("androidTest").assets.srcDir("schemas")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    testImplementation("junit:junit:4.13.2")
}
