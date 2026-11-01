plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}
kotlin { jvmToolchain(21) }
application { mainClass.set("com.example.cet6.server.ServerKt") }
dependencies {
    implementation(project(":sync-core"))
    implementation("org.json:json:20240303")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("io.ktor:ktor-server-netty-jvm:2.3.13")
    implementation("io.ktor:ktor-server-status-pages-jvm:2.3.13")
    implementation("org.slf4j:slf4j-simple:1.7.36")
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-server-test-host-jvm:2.3.13")
}
