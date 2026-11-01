plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

// Style (ktlint) and static analysis (detekt) for every module. Findings that predate these checks live in
// each module's baseline file, so new code is held to the rules without rewriting the whole codebase at once.
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        baseline.set(file("config/ktlint/baseline.xml"))
        filter { exclude { it.file.path.contains("generated") || it.file.path.contains("third_party") } }
    }
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        baseline = file("config/detekt/baseline.xml")
        source.setFrom("src/main/java", "src/main/kotlin", "src/test/java", "src/test/kotlin")
    }
}

tasks.wrapper {
    distributionSha256Sum = "d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab"
}
