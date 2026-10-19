plugins {
    alias(libs.plugins.kotlin.jvm)
}

// DexKit 只发布 AAR；纯 JVM 模块编译时用 AAR 里的 classes.jar（compileOnly）。Android 上由使用方依赖 AAR 本身，
// 电脑上的测试和工具另外加 classes.jar、FlatBuffers 和桌面版 libdexkit（scripts/build-dexkit-desktop.sh）。
val dexkitAar: Configuration = configurations.create("dexkitAar") { isTransitive = false }
val dexkitClasses = tasks.register<Sync>("dexkitClassesJar") {
    from({ zipTree(dexkitAar.singleFile) }) { include("classes.jar") }
    into(layout.buildDirectory.dir("dexkit"))
}
val dexkitJar = files(layout.buildDirectory.file("dexkit/classes.jar")).builtBy(dexkitClasses)

dependencies {
    dexkitAar("org.luckypray:dexkit:${libs.versions.dexkit.get()}@aar")
    api(project(":core"))
    compileOnly(dexkitJar)
    testImplementation(dexkitJar)
    testImplementation(libs.dexkit.flatbuffers)
    testImplementation(libs.junit)
}
