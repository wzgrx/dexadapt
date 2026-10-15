plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":apk"))
    implementation(project(":dexlib"))
    implementation(project(":dexkit-desktop"))
    implementation(project(":diff"))
    implementation(project(":synth"))
    implementation(project(":libs"))
    implementation(project(":runtime"))
    testImplementation(libs.junit)
}

application {
    mainClass.set("io.github.wzgrx.dexadapt.cli.MainKt")
    applicationName = "dexadapt"
    applicationDefaultJvmArgs = listOf("-Xmx12g", "--enable-native-access=ALL-UNNAMED")
}

// MCP 服务的 serverInfo.version 读这里
tasks.jar {
    manifest { attributes("Implementation-Title" to "dexadapt", "Implementation-Version" to project.version) }
}
