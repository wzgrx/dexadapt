package io.github.wzgrx.dexadapt.dexlib

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 索引记录读回来要和直接解析的一模一样（类、字段、方法的每一项）。需要样本 APK。 */
class IndexCacheTest {
    private val samples = File(System.getenv("DEXADAPT_SAMPLES")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/projects/xhs-apks"))

    @Test
    fun `record round trips the whole index`() {
        val apk = File(samples, "cn-9500803-device.apk")
        assumeTrue(apk.isFile)
        val t0 = System.currentTimeMillis()
        val parsed = DexIndex.load(apk, cache = false)
        val t1 = System.currentTimeMillis()
        val key = IndexCache.key(apk, Regex("classes\\d*\\.dex"))!!
        IndexCache.write(key, parsed)
        val t2 = System.currentTimeMillis()
        val back = IndexCache.read(key)!!
        val t3 = System.currentTimeMillis()
        System.err.println("parse ${(t1 - t0) / 1000.0}s, write ${(t2 - t1) / 1000.0}s, read ${(t3 - t2) / 1000.0}s")
        assertEquals(parsed.dexSha256, back.dexSha256)
        assertEquals(parsed.classes.keys, back.classes.keys)
        for ((name, a) in parsed.classes) {
            val b = back.classes.getValue(name)
            assertEquals(name, listOf(a.superName, a.interfaces, a.flags, a.sourceFile, a.annotations), listOf(b.superName, b.interfaces, b.flags, b.sourceFile, b.annotations))
            assertEquals(name, a.fields.map { listOf(it.name, it.type, it.flags) }, b.fields.map { listOf(it.name, it.type, it.flags) })
            assertEquals(name, a.methods.size, b.methods.size)
            a.methods.zip(b.methods).forEach { (x, y) ->
                assertEquals(x.orderedStrings,y.orderedStrings)
                assertEquals(x.signature, listOf(x.signature, x.flags, x.strings, x.calls, x.fieldRefs, x.opcodes, x.numbers, x.annotations), listOf(y.signature, y.flags, y.strings, y.calls, y.fieldRefs, y.opcodes, y.numbers, y.annotations))
            }
        }
        assertEquals(parsed.methodsByString.keys, back.methodsByString.keys)
        assertEquals(parsed.methodsByString.mapValues { (_, v) -> v.map { it.signature }.toSet() }, back.methodsByString.mapValues { (_, v) -> v.map { it.signature }.toSet() })
    }
}
