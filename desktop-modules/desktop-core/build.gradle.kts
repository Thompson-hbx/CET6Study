plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}
kotlin { jvmToolchain(21) }
dependencies {
    api(compose.desktop.currentOs)
    api(compose.material3)
    api(compose.materialIconsExtended)
    implementation(libs.okhttp)
    api(libs.json)
    api(libs.coroutines.swing)
    api(project(":sync-core"))
    implementation(project(":review-core"))
    implementation(libs.sqlite.jdbc)
    implementation(libs.jna.platform)
    testImplementation(libs.junit)
}
