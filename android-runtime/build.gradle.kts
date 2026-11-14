plugins { alias(libs.plugins.kotlin.jvm) }

// Android IPC 适配层不引入任何 Hook 框架；宿主和模块共用同一份通知协议。
dependencies {
    api(project(":runtime"))
    compileOnly(files(gradle.extra["androidJar"] as String))
}
