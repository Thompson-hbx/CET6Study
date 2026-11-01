plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}
kotlin { jvmToolchain(21) }
application { mainClass.set("com.example.cet6.server.ServerKt") }
dependencies {
    implementation(project(":sync-core"))
    implementation(libs.json)
    implementation(libs.sqlite.jdbc)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.slf4j.simple)
    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
}
