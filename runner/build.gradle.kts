plugins {
    kotlin("jvm") version "2.4.20"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(files("../libs/dexkit-2.3.0.jar"))
    // dexkit-2.3.0.pom 的 runtime 依赖（AAR 不打包，需显式引入；cxx 为 Android 专用，桌面跳过）
    implementation("com.google.flatbuffers:flatbuffers-java:23.5.26")
    // 通用验证台：YAML spec 加载 + JSON 报告
    implementation("org.yaml:snakeyaml:2.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

application {
    mainClass.set("MainKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
