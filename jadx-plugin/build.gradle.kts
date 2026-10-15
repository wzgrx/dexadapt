plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":synth"))
    compileOnly(libs.jadx.core)
    testImplementation(libs.junit)
    testImplementation(libs.jadx.core)
    // 测试里用 jadx 读 APK 要它的 dex 输入插件（jadx 本体里自带）
    testImplementation(libs.jadx.dex.input)
}

// jadx 按单个 jar 装插件：把 DexAdapt 和依赖打进一个 jar（jadx 自己的类不打）。
// 安装：jadx plugins --install-jar jadx-plugin/build/libs/dexadapt-jadx-plugin.jar
val pluginJar = tasks.register<Jar>("pluginJar") {
    archiveFileName.set("dexadapt-jadx-plugin.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.map { cp -> cp.map { if (it.isDirectory) it else zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**", "module-info.class")
    }
}
tasks.named("assemble") { dependsOn(pluginJar) }

tasks.test {
    maxHeapSize = "6g"
    testLogging { events("standard_error") }
}
