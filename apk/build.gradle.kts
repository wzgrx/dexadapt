plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core"))
    implementation(libs.apksig)
    testImplementation(libs.junit)
}

tasks.test {
    testLogging { events("standard_error") }
}
