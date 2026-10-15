plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core"))
    api(project(":apk"))
    api(project(":dexlib"))
    testImplementation(libs.junit)
}

tasks.test {
    maxHeapSize = "12g"
    testLogging { events("standard_error") }
    val samples = System.getenv("DEXADAPT_SAMPLES").orEmpty()
    environment("DEXADAPT_SAMPLES", samples)
    inputs.property("samples", samples)
}
