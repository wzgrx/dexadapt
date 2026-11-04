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
    // decompile 命令：用 jadx 批量反编译（加载一次 APK，反编译多个类 / 整个包）
    implementation(libs.jadx.core)
    implementation(libs.jadx.dex.input)
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

// 离线分发附带本次源码对应的说明，不附带宿主APK或研究记录。
distributions {
    main {
        contents {
            from(rootProject.file("README.md"))
            from(rootProject.file("CHANGELOG.md"))
            from(rootProject.file("LICENSE"))
            into("docs") { from(rootProject.file("docs/SPEC.md"));from(rootProject.file("docs/DESIGN.md")) }
        }
    }
}
