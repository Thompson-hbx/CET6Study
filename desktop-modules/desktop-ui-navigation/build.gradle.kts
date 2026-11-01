plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}
kotlin { jvmToolchain(21) }
dependencies {
    api(project(":desktop-feature-today"))
    api(project(":desktop-feature-review"))
    api(project(":desktop-feature-study"))
    api(project(":desktop-feature-settings"))
    api(project(":desktop-feature-words"))
    api(compose.desktop.currentOs)
    api(compose.material3)
    api(compose.materialIconsExtended)
    api(libs.json)
    api(libs.coroutines.swing)
    api(project(":diagnostics"))
    testImplementation(libs.junit)
}
