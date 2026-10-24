plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
val personalExamPack = providers.gradleProperty("personalExamPack")
val personalExamPdfs = providers.gradleProperty("personalExamPdfs")
val personalAssets = layout.buildDirectory.dir("generated/personalExamAssets")
val preparePersonalExamAssets = tasks.register<Sync>("preparePersonalExamAssets") {
    onlyIf { personalExamPack.isPresent }
    from(personalExamPack.map { rootProject.file(it) }) { rename { "personal-exam-pack.json" } }
    if (personalExamPdfs.isPresent) {
        from(personalExamPdfs.map { rootProject.file(it) }) {
            include("cet6_*.pdf")
            into("personal-papers")
        }
    }
    into(personalAssets)
    doFirst {
        require(rootProject.file(personalExamPack.get()).isFile) { "Personal exam pack does not exist" }
        if (personalExamPdfs.isPresent) require(rootProject.file(personalExamPdfs.get()).isDirectory) { "Personal PDF directory does not exist" }
    }
}
tasks.configureEach {
    if (personalExamPack.isPresent &&
        ((name.startsWith("merge") && name.endsWith("Assets")) || name.contains("lint", ignoreCase = true))) {
        dependsOn(preparePersonalExamAssets)
    }
}
android {
    namespace = "com.example.cet6"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.cet6"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "0.7.0-desk"
        testInstrumentationRunner = "com.example.cet6.LocalDataInstrumentation"
    }
    buildFeatures { compose = true }
    // Ordinary builds exclude private material; explicitly requested personal builds add one exact pack.
    androidResources {
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:CVS:thumbs.db:picasa.ini:*~:exam-packs"
    }
    sourceSets.getByName("main").java.srcDir("../third_party/bee-fsrs/src")
    if (personalExamPack.isPresent) sourceSets.getByName("main").assets.srcDir(personalAssets)
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
    // android.jar only stubs org.json; the reference implementation lets protocol parsers run on the JVM.
    testImplementation("org.json:json:20240303")
}
