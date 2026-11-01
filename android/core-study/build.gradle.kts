plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}
android {
    namespace = "com.example.cet6.core.study"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").java.srcDir(rootProject.file("third_party/bee-fsrs/src"))
    sourceSets.getByName("test").java.srcDir(rootProject.file("app/src/testShared/java"))
}

dependencies {
    api(project(":sync-core"))
    api(project(":review-core"))
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    api(libs.room.runtime)
    api(libs.room.ktx)
    ksp(libs.room.compiler)
    api(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}

ksp { arg("room.schemaLocation", rootProject.file("app/schemas").absolutePath) }

tasks.withType<Test>().configureEach { workingDir(rootProject.file("app")) }
