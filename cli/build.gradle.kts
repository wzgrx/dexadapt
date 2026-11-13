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

// 发布时显式提供跨模块样本，必须新跑，不能把之前无样本的skip当作回归通过。
tasks.test {
    // Gradle 的测试工作进程用隔离的类加载器，java.class.path 不一定含被测程序依赖。
    systemProperty("dexadapt.test.cliClasspath", sourceSets.test.get().runtimeClasspath.asPath)
    val manifest = providers.environmentVariable("DEXADAPT_CONSUMER_MANIFEST").orNull?.takeIf { it.isNotBlank() }
    inputs.property("requireConsumerSamples", providers.environmentVariable("DEXADAPT_REQUIRE_CONSUMERS").getOrElse("0"))
    if (manifest != null) {
        inputs.file(manifest)
        outputs.upToDateWhen { false }
        maxHeapSize = "6g"
    }
}
