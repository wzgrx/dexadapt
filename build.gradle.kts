plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    group = "io.github.wzgrx.dexadapt"
    version = "0.1.0"
}

// 每个模块：JVM 17 字节码（Android 也能用）、JUnit 4、发布到本地 Maven（红薯助手先用复合构建直接引用源码）。
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(plugin = "maven-publish")
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
            withSourcesJar()
        }
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                allWarningsAsErrors.set(true)
            }
        }
        extensions.configure<PublishingExtension> {
            publications.create<MavenPublication>("maven") {
                artifactId = "dexadapt-${project.name}"
                from(components["java"])
            }
        }
    }
}
