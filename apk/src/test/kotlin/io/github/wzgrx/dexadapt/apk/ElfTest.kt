package io.github.wzgrx.dexadapt.apk

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** 和 readelf 的结果逐个比：导出符号、导入符号、依赖库、soname。需要样本 APK 和 binutils 的 readelf。 */
class ElfTest {
    private val apk = File(System.getenv("DEXADAPT_SAMPLES")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/projects/xhs-apks"), "cn-9500803-device.apk")

    private fun readelf(vararg args: String, file: File): List<String> {
        val p = ProcessBuilder(listOf("readelf", "-W") + args + file.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readLines()
        p.waitFor(30, TimeUnit.SECONDS)
        return out
    }

    @Test
    fun `matches readelf on real libraries`() {
        assumeTrue(apk.isFile && runCatching { ProcessBuilder("readelf", "--version").start().waitFor() == 0 }.getOrDefault(false))
        val dir = java.nio.file.Files.createTempDirectory("elf").toFile()
        try {
            var checked = 0
            ZipFile(apk).use { zip ->
                val libs = zip.entries().asSequence().filter { it.name.startsWith("lib/arm64-v8a/") && it.name.endsWith(".so") }.sortedBy { it.size }.toList()
                for (e in libs.take(40)) {
                    val bytes = zip.getInputStream(e).readBytes()
                    val f = File(dir, e.name.substringAfterLast('/')).apply { writeBytes(bytes) }
                    val elf = Elf.parse(bytes)
                    // readelf --dyn-syms：Num Value Size Type Bind Vis Ndx Name
                    val rows = readelf("--dyn-syms", file = f).map { it.trim().split(Regex("\\s+")) }.filter { it.size >= 8 && it[0].endsWith(":") && it[0] != "Num:" }
                    val names = rows.filter { it.size >= 8 }.map { it[7].substringBefore('@') to it[6] }.filter { it.first.isNotEmpty() }
                    val exported = names.filter { (n, ndx) -> ndx != "UND" && rows.first { r -> r[7].substringBefore('@') == n }[4] != "LOCAL" }.map { it.first }.toSortedSet()
                    val imported = names.filter { it.second == "UND" }.map { it.first }.toSortedSet()
                    assertEquals(e.name, exported, elf.exported.toSortedSet())
                    assertEquals(e.name, imported, elf.imported.toSortedSet())
                    val needed = readelf("-d", file = f).mapNotNull { Regex("\\(NEEDED\\).*\\[(.+)]").find(it)?.groupValues?.get(1) }
                    assertEquals(e.name, needed, elf.needed)
                    checked++
                }
            }
            System.err.println("compared $checked libraries with readelf")
        } finally {
            dir.deleteRecursively()
        }
    }
}
