import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
}

/**
 * 各 Hook 框架发布的是 AAR（libxposed、YukiHookAPI、EzXHelper）：用 artifact transform 取出里面的 classes.jar，
 * 只用来编译（compileOnly）；使用方自己依赖框架，DexAdapt 不打包任何框架。
 */
abstract class AarClasses : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    abstract val input: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val aar = input.get().asFile
        ZipFile(aar).use { zip ->
            val entry = zip.getEntry("classes.jar") ?: return
            val out = outputs.file(aar.nameWithoutExtension + ".jar")
            zip.getInputStream(entry).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        }
    }
}

val artifactType = Attribute.of("artifactType", String::class.java)
val aar: Configuration = configurations.create("aar") { isTransitive = false }

dependencies {
    registerTransform(AarClasses::class) {
        from.attribute(artifactType, "aar")
        to.attribute(artifactType, "aar-classes")
    }
    aar("${libs.libxposed.api.get()}@aar")
    aar("${libs.yukihookapi.get()}@aar")
    aar("${libs.ezxhelper.get()}@aar")

    api(project(":runtime"))
    compileOnly(files(aar.incoming.artifactView { attributes.attribute(artifactType, "aar-classes") }.files))
    compileOnly(libs.xposed.api)
    compileOnly(libs.kavaref.core)
    compileOnly(files(gradle.extra["androidJar"] as String))
    testImplementation(libs.junit)
}
