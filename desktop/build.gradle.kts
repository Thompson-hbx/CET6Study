import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}
kotlin { jvmToolchain(21) }
// The serif and its OFL licence come from the phone app, so both platforms ship the same font files.
sourceSets { main { resources.srcDir("../app/src/main/res/font") } }
tasks.processResources { from("../app/src/main/assets/licenses/source-serif.txt") { into("licenses") } }
dependencies {
    implementation(project(":sync-core"))
    implementation(project(":design-tokens"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.json)
    implementation(libs.sqlite.jdbc)
    implementation(libs.okhttp)
    implementation(libs.jna.platform)
    implementation(libs.coroutines.swing)
    testImplementation(libs.junit)
}
compose.desktop {
    application {
        mainClass = "com.example.cet6.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Xiye"
            packageVersion = "0.1.0"
            modules("java.sql", "java.naming", "jdk.crypto.ec", "jdk.unsupported")
            windows {
                menuGroup = "Xiye"
                shortcut = true
            }
        }
    }
}

// JDK app-image packaging avoids Compose 1.7's unconditional WiX requirement on Windows.
tasks.jar {
    manifest { attributes["Class-Path"] = configurations.runtimeClasspath.get().files.joinToString(" ") { it.name } }
}
val portableInput = tasks.register<Sync>("preparePortable") {
    dependsOn(tasks.jar)
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("portable-input"))
    duplicatesStrategy = DuplicatesStrategy.FAIL
}
tasks.register<Exec>("packagePortable") {
    dependsOn(portableInput)
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    doFirst {
        val output = layout.buildDirectory.dir("portable").get().asFile
        val image = output.resolve("Xiye")
        check(image.canonicalFile.toPath().startsWith(layout.buildDirectory.get().asFile.canonicalFile.toPath()))
        project.delete(image)
        commandLine(
            launcher.get().metadata.installationPath.file("bin/jpackage.exe").asFile,
            "--type", "app-image", "--name", "Xiye", "--app-version", "0.1.0",
            "--input", layout.buildDirectory.dir("portable-input").get().asFile,
            "--main-jar", tasks.jar.get().archiveFileName.get(), "--main-class", "com.example.cet6.desktop.MainKt",
            "--dest", output, "--java-options", "-Dfile.encoding=UTF-8",
            "--add-modules", "java.desktop,java.sql,java.naming,java.logging,java.management,jdk.crypto.ec,jdk.unsupported,jdk.charsets",
        )
    }
}
