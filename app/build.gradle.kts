plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
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
        ((name.startsWith("merge") && name.endsWith("Assets")) || name.contains("lint", ignoreCase = true))
    ) {
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
    sourceSets.getByName("androidTest").assets.srcDir("testdata")
    sourceSets.getByName("test").java.srcDir("src/testShared/java")
    sourceSets.getByName("androidTest").java.srcDir("src/testShared/java")
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
    implementation(project(":sync-core"))
    implementation(project(":design-tokens"))
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.activity.ktx)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.haze)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core.ktx)
    implementation(libs.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.markwon.core)
    implementation(libs.markwon.tables)
    testImplementation(libs.junit)
    // android.jar only stubs org.json; the reference implementation lets protocol parsers run on the JVM.
    testImplementation(libs.json)
}
