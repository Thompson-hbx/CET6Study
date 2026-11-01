// Plain Kotlin with no UI dependency, so the Android app and the Compose Desktop app can both use it.
plugins { alias(libs.plugins.kotlin.jvm) }
kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
dependencies {
    testImplementation(libs.junit)
}
