plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    application
}


group = "com.rinha"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
    applicationDefaultJvmArgs = listOf(
        "-XX:MaxRAM=50m",
        "-XX:MaxRAMPercentage=70",
        "-XX:+UseSerialGC",
        "-XX:MaxHeapFreeRatio=20",
        "-XX:MinHeapFreeRatio=10"
    )
}

repositories {
    mavenCentral()
}

dependencies {

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.logback.classic)
    implementation(libs.content.negotiation)
    implementation(libs.serialization.kotlinx.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.jedis)
    implementation(libs.sqlite.jdbc)


    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
}