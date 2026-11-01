plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}
kotlin { jvmToolchain(21) }
dependencies {
    api(project(":desktop-ui-common"))
    implementation(project(":review-core"))
    api(compose.desktop.currentOs)
    api(compose.material3)
    api(compose.materialIconsExtended)
    api(libs.json)
    api(libs.coroutines.swing)
    testImplementation(libs.junit)
}
