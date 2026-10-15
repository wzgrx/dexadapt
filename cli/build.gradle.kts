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
    testImplementation(libs.junit)
}

application {
    mainClass.set("io.github.wzgrx.dexadapt.cli.MainKt")
    applicationName = "dexadapt"
    applicationDefaultJvmArgs = listOf("-Xmx12g", "--enable-native-access=ALL-UNNAMED")
}
