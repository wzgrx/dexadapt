plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core"))
    implementation(libs.dexlib2)
    testImplementation(libs.junit)
}

tasks.test {
    // IndexCacheTest 同时放两份小红书的完整索引
    maxHeapSize = "12g"
    testLogging { events("standard_error") }
}
