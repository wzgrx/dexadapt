plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core"))
    implementation(libs.dexlib2)
    testImplementation(libs.junit)
}
