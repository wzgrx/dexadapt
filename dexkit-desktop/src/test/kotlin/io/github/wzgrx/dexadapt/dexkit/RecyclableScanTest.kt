package io.github.wzgrx.dexadapt.dexkit

import io.github.wzgrx.dexadapt.resolve.SymbolResolver
import io.github.wzgrx.dexadapt.spec.Spec
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * DexKitCacheBridge 包装（[RecyclableScan]）：解析结果和每次打开、用完关掉的 [DexKitScan] 一样；第二次扫描复用桥。
 * 用红薯助手导出的查询目录（REDBOOK_SPEC，默认 ../redbook-helper-offline/docs/adapt/catalog.yaml）。
 */
class RecyclableScanTest {
    private val apk = File(System.getenv("DEXADAPT_SAMPLES")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/projects/xhs-apks"), "cn-9500803-device.apk")
    private val spec = File(System.getenv("REDBOOK_SPEC") ?: (System.getProperty("user.home") + "/projects/redbook-helper-offline/docs/adapt/catalog.yaml"))

    @Test
    fun `recyclable bridge gives the same table and is reused`() {
        assumeTrue(apk.isFile && spec.isFile && DesktopDexKit.available)
        DesktopDexKit.load()
        val symbols = Spec.read(spec).catalog.symbols
        fun timed(block: () -> io.github.wzgrx.dexadapt.SymbolTable): Pair<io.github.wzgrx.dexadapt.SymbolTable, Long> {
            val t0 = System.currentTimeMillis()
            return block() to System.currentTimeMillis() - t0
        }
        val (plain, tPlain) = timed { DexKitScan(listOf(apk), "d").use { SymbolResolver(it.source, 9500803).table(symbols) } }
        val (first, t1) = timed { RecyclableScan.run("test", listOf(apk), "d") { SymbolResolver(it, 9500803).table(symbols) } }
        val (second, t2) = timed { RecyclableScan.run("test", listOf(apk), "d") { SymbolResolver(it, 9500803).table(symbols) } }
        System.err.println("DexKitScan ${tPlain}ms, recyclable first ${t1}ms, second ${t2}ms; ${plain.entries.size} found")
        assertEquals(plain, first)
        assertEquals(plain, second)
        RecyclableScan.releaseAll()
    }
}
