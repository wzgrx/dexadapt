pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
        // 传统 Xposed API（de.robv.android.xposed:api:82）只在这里有
        maven("https://api.xposed.info/") { content { includeGroup("de.robv.android.xposed") } }
    }
}

rootProject.name = "dexadapt"

include(":core")
include(":apk")
include(":dexlib")
include(":dexkit")
include(":dexkit-desktop")
include(":diff")
include(":synth")
include(":libs")
include(":runtime")
// Hook 框架适配要编译期的 android.jar（Android SDK）；没有 SDK 的机器上不构建这个模块
if (androidJar() != null) include(":hooks")
include(":cli")

/** Android SDK 里最新的 android.jar（ANDROID_HOME，或 ~/Android/Sdk）。 */
fun androidJar(): File? {
    val sdk = System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/Android/Sdk")
    return File(sdk, "platforms").listFiles().orEmpty().sortedDescending().map { File(it, "android.jar") }.firstOrNull { it.isFile }
}
gradle.extra["androidJar"] = androidJar()?.path
