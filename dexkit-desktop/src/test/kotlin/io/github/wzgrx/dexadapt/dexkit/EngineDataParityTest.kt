package io.github.wzgrx.dexadapt.dexkit

import io.github.wzgrx.dexadapt.dexlib.DexIndex
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.luckypray.dexkit.DexKitBridge
import java.io.File

/**
 * 两个数据源给出的“原始数据”一致：抽几千个类，逐个比源文件名、注解、每个方法的操作码、数字常量、注解。
 * 解析器在两边是同一份代码，数据一致，查询结果才会一致。没有样本或桌面版 DexKit 时跳过。
 */
class EngineDataParityTest {
    private val apk = File(System.getenv("DEXADAPT_SAMPLES")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/projects/xhs-apks"), "cn-9500803-device.apk")

    @Test
    fun `dexlib and dexkit see the same class and method data`() {
        assumeTrue("no sample $apk", apk.isFile)
        assumeTrue("desktop libdexkit not built", DesktopDexKit.available)
        DesktopDexKit.load()
        val index = DexIndex.load(apk)
        val names = index.classes.keys.sorted().filterIndexed { i, _ -> i % 97 == 0 }
        val diffs = ArrayList<String>()
        var methods = 0
        DexKitBridge.create(apk.path).use { bridge ->
            val kit = DexKitSource(bridge, "")
            for (n in names) {
                val a = index.classByName(n)!!
                val b = kit.classByName(n) ?: run { diffs += "$n missing in dexkit"; continue }
                if (a.sourceFile != b.sourceFile) diffs += "$n sourceFile ${a.sourceFile} vs ${b.sourceFile}"
                if (a.annotations.sorted() != b.annotations.sorted()) diffs += "$n annotations ${a.annotations} vs ${b.annotations}"
                val bm = b.methods.associateBy { it.signature }
                for (m in a.methods) {
                    val k = bm[m.signature] ?: run { diffs += "${m.signature} missing in dexkit"; null } ?: continue
                    methods++
                    if (m.opcodes != k.opcodes) {
                        val i = m.opcodes.indices.firstOrNull { it >= k.opcodes.size || m.opcodes[it] != k.opcodes[it] } ?: m.opcodes.size
                        diffs += "${m.signature} opcodes at $i/${m.opcodes.size}/${k.opcodes.size}: ${m.opcodes.drop(i).take(4)} vs ${k.opcodes.drop(i).take(4)}"
                    }
                    if (m.numbers != k.numbers) diffs += "${m.signature} numbers ${(m.numbers - k.numbers).take(5)} / ${(k.numbers - m.numbers).take(5)}"
                    if (m.annotations.sorted() != k.annotations.sorted()) diffs += "${m.signature} annotations ${m.annotations} vs ${k.annotations}"
                }
            }
        }
        System.err.println("compared ${names.size} classes, $methods methods, ${diffs.size} differences")
        diffs.groupingBy { it.substringAfter(' ').substringBefore(' ') }.eachCount().forEach { (k, v) -> System.err.println("  $k: $v") }
        diffs.take(15).forEach { System.err.println("  $it") }
        assertEquals(emptyList<String>(), diffs.take(15))
    }
}
