package io.github.wzgrx.dexadapt.libs

import io.github.wzgrx.dexadapt.dexlib.DexIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 开源库识别的基准：
 * 1. **有标准答案的**：用 R8（和 App 正式包同一个工具）把 OkHttp 3.14.9 + Okio 混淆、压缩、扁平化包名，R8 自己的
 *    mapping.txt 就是答案，看版本认得对不对、类对应的精度和召回。
 * 2. **真实 App**：小红书 9.50.0 里认出 OkHttp 3.14.x、Retrofit、RxJava 2（不是 3）。
 * 需要 Maven 缓存（或网络）、Gradle 缓存里的 R8、Android SDK 的 android.jar、样本 APK；缺了就跳过。
 */
class LibraryBenchmarkTest {
    private val home = System.getProperty("user.home")
    private val maven = Maven()
    private val db: LibraryDb? by lazy {
        LibraryDb.bundled() ?: File(System.getenv("DEXADAPT_LIBDB") ?: "").takeIf { it.isFile }?.let(LibraryDb::read)
    }

    @Test
    fun `r8 obfuscated okhttp is recognised with its version and classes`() {
        val r8 = File("$home/.gradle/caches/modules-2/files-2.1/com.android.tools/r8").walk().firstOrNull { it.name.matches(Regex("r8-[\\d.]+\\.jar")) }
        val sdk = System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() } ?: "$home/Android/Sdk"
        val androidJar = File("$sdk/platforms").listFiles().orEmpty().sortedDescending().map { File(it, "android.jar") }.firstOrNull { it.isFile }
        val db = db
        assumeTrue("need r8, android.jar and a library db", r8 != null && androidJar != null && db?.library("com.squareup.okhttp3:okhttp") != null)
        val work = createTempDir()
        try {
            val okhttp = maven.artifact("com.squareup.okhttp3", "okhttp", "3.14.9")
            val okio = maven.artifact("com.squareup.okio", "okio", "1.17.2")
            val rules = File(work, "rules.pro").apply {
                writeText(
                    """
                    -keep,allowobfuscation class okhttp3.OkHttpClient { public *; }
                    -keep,allowobfuscation class okhttp3.OkHttpClient${'$'}Builder { public *; }
                    -keep,allowobfuscation class okhttp3.Request${'$'}Builder { public *; }
                    -keep,allowobfuscation class okhttp3.Call { public *; }
                    -keep,allowobfuscation class okhttp3.Response { public *; }
                    -keep,allowobfuscation class okhttp3.ResponseBody { public *; }
                    -repackageclasses 'z'
                    -dontwarn **
                    -printmapping ${File(work, "mapping.txt").path}
                    """.trimIndent(),
                )
            }
            val out = File(work, "out.zip")
            val p = ProcessBuilder(
                "java", "-cp", r8!!.path, "com.android.tools.r8.R8", "--release", "--min-api", "24",
                "--lib", androidJar!!.path, "--pg-conf", rules.path, "--output", out.path, okhttp.path, okio.path,
            ).redirectErrorStream(true).start()
            val log = p.inputStream.bufferedReader().readText()
            assertTrue("r8 timed out", p.waitFor(5, TimeUnit.MINUTES))
            assertEquals("r8 failed: $log", 0, p.exitValue())

            // R8 的映射：原名 -> 新名
            val truth = File(work, "mapping.txt").readLines().filter { !it.startsWith(" ") && !it.startsWith("#") && it.endsWith(":") }
                .associate { it.substringBefore(" -> ") to it.substringAfter(" -> ").removeSuffix(":") }
            val index = DexIndex.load(out)
            val found = LibraryScanner(db!!).scan(index)
            found.forEach { System.err.println("found ${it.name} ${it.versionText} recall %.2f hits ${it.hits}/${it.total} against ${it.against}, ${it.classes.size} classes".format(it.recall)) }
            val ok = found.single { it.id == "com.squareup.okhttp3:okhttp" }
            assertTrue("version ${ok.versions}", "3.14.9" in ok.versions)
            val judged = ok.classes.filter { it.library in truth }
            val correct = judged.count { truth[it.library] == it.apk }
            judged.filter { truth[it.library] != it.apk }.take(10).forEach { System.err.println("  wrong ${it.library} -> ${it.apk} (truth ${truth[it.library]}, votes ${it.votes}/${it.strings})") }
            val renamed = truth.count { (k, v) -> k.startsWith("okhttp3.") && k != v }
            System.err.println("okhttp classes: matched ${ok.classes.size}, judged ${judged.size}, correct $correct; R8 kept $renamed okhttp3 classes")
            assertTrue("precision", correct >= judged.size * 0.9)
            assertTrue("recall", correct >= 30)
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun `libraries inside xiaohongshu`() {
        val apk = File(System.getenv("DEXADAPT_SAMPLES")?.takeIf { it.isNotBlank() } ?: "$home/projects/xhs-apks", "cn-9500803-device.apk")
        val db = db
        assumeTrue(apk.isFile && db != null)
        val found = LibraryScanner(db!!).scan(DexIndex.load(apk))
        found.forEach { System.err.println("xhs: ${it.name} ${it.versionText} recall %.2f hits ${it.hits}/${it.total} against ${it.against}, ${it.classes.size} classes, ${it.namesKept} kept".format(it.recall)) }
        val ids = found.associateBy { it.id }
        assertTrue(ids.getValue("com.squareup.okhttp3:okhttp").versions.any { it.startsWith("3.14.") })
        assertTrue("io.reactivex.rxjava2:rxjava" in ids)
        assertTrue("io.reactivex.rxjava3:rxjava" !in ids)
        assertTrue("com.squareup.retrofit2:retrofit" in ids)
    }

    private fun createTempDir(): File = java.nio.file.Files.createTempDirectory("dexadapt-libs").toFile()
}
