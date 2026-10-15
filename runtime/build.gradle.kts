plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// 模块运行时用的部分（纯 JVM，Android 上直接用）：选表策略、要不要本机扫描、把符号表变成反射对象。
dependencies {
    api(project(":core"))
    testImplementation(libs.junit)
}
