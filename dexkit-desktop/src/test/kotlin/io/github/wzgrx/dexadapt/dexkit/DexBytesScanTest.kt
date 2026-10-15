package io.github.wzgrx.dexadapt.dexkit

import io.github.wzgrx.dexadapt.resolve.DexDigest
import io.github.wzgrx.dexadapt.resolve.SymbolResolver
import io.github.wzgrx.dexadapt.spec.Spec
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** 导出的 dex 字节（加固包脱壳后的样子）：摘要和安装包的一样，解析结果和直接读安装包逐条相同。 */
class DexBytesScanTest {
    private val apk = File(System.getenv("DEXADAPT_SAMPLES")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/projects/xhs-apks"), "cn-9500803-device.apk")
    private val spec = File(System.getenv("REDBOOK_SPEC") ?: (System.getProperty("user.home") + "/projects/redbook-helper-offline/docs/adapt/catalog.yaml"))

    @Test
    fun `dex bytes give the same digest and table as the apk`() {
        assumeTrue(apk.isFile && spec.isFile && DesktopDexKit.available)
        DesktopDexKit.load()
        val dexes = ZipFile(apk).use { z ->
            z.entries().asSequence().filter { it.name.matches(Regex("classes\\d*\\.dex")) }.sortedWith(compareBy({ it.name.length }, { it.name }))
                .map { e -> z.getInputStream(e).use { it.readBytes() } }.toList()
        }
        val symbols = Spec.read(spec).catalog.symbols
        val fromApk = DexKitScan(listOf(apk), DexDigest.of(apk)).use { SymbolResolver(it.source, 9500803).table(symbols) }
        val fromBytes = DexKitScan.ofDexBytes(dexes).use { scan ->
            assertEquals(DexDigest.of(apk), scan.source.dexSha256)
            SymbolResolver(scan.source, 9500803).table(symbols)
        }
        assertEquals(fromApk, fromBytes)
        System.err.println("dex bytes: ${dexes.size} dex, ${fromBytes.entries.size} found")
    }
}
