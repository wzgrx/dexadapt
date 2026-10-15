plugins {
    alias(libs.plugins.kotlin.jvm)
}

// 电脑上用 DexKit：dexadapt-dexkit + DexKit 的 JVM 类（取自 AAR）+ FlatBuffers + 桌面原生库加载（DesktopDexKit）。
// 原生库由 scripts/build-dexkit-desktop.sh 构建，放在仓库外。
dependencies {
    api(project(":dexkit"))
    api(files(rootProject.layout.projectDirectory.file("dexkit/build/dexkit/classes.jar")).builtBy(":dexkit:dexkitClassesJar"))
    api(libs.dexkit.flatbuffers)
    testImplementation(libs.junit)
}

val generateInfo = tasks.register("generateDexKitInfo") {
    val out = layout.buildDirectory.dir("generated/source/info/kotlin")
    val dexkitVersion = libs.versions.dexkit.get()
    inputs.property("dexkit", dexkitVersion)
    outputs.dir(out)
    doLast {
        val f = out.get().file("io/github/wzgrx/dexadapt/dexkit/DexKitInfo.kt").asFile
        f.parentFile.mkdirs()
        f.writeText("package io.github.wzgrx.dexadapt.dexkit\n\n/** 构建时生成（dexkit-desktop/build.gradle.kts），不要手改。 */\nobject DexKitInfo {\n    const val VERSION = \"$dexkitVersion\"\n}\n")
    }
}
kotlin.sourceSets.named("main") { kotlin.srcDir(generateInfo) }

dependencies {
    testImplementation(project(":dexlib"))
}

tasks.test {
    maxHeapSize = "10g"
    testLogging { events("standard_error") }
    // 真实 APK 样本（没有时测试跳过）。
    val samples = System.getenv("DEXADAPT_SAMPLES").orEmpty()
    environment("DEXADAPT_SAMPLES", samples)
    inputs.property("samples", samples)
}
