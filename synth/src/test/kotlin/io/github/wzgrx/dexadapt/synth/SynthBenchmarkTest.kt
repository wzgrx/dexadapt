package io.github.wzgrx.dexadapt.synth

import io.github.wzgrx.dexadapt.MethodQuery
import io.github.wzgrx.dexadapt.MethodShape
import io.github.wzgrx.dexadapt.MethodSymbol
import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.SymbolTable
import io.github.wzgrx.dexadapt.dexlib.DexIndex
import io.github.wzgrx.dexadapt.resolve.SymbolResolver
import io.github.wzgrx.dexadapt.type
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * 用红薯助手的真实历史检验自动生成和修复（小红书国内版 9.49.1 → 9.50.0）：
 * - 修复：9.50.0 里 attachBrowser 加了参数，旧查询找不到；当时手工的修法是去掉参数条件，修复要自己找到这一条；
 * - 生成：给 9.49.1 里的 hideOverlay 方法生成查询，在 9.50.0 里要命中正确的那个方法（红薯助手的表就是答案）；
 * - 抽样：红薯助手的类符号，生成的查询在另一个版本里命中正确类的比例。
 */
class SynthBenchmarkTest {
    companion object {
        private val samples = File(System.getenv("DEXADAPT_SAMPLES")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/projects/xhs-apks"))
        private val tables = File(System.getenv("REDBOOK_SYMBOLS") ?: (System.getProperty("user.home") + "/projects/redbook-helper-offline/core/symbols/src/main/resources/symbols"))
        private var old: DexIndex? = null
        private var new: DexIndex? = null
        private lateinit var oldTable: SymbolTable
        private lateinit var newTable: SymbolTable

        @BeforeClass @JvmStatic
        fun load() {
            val a = File(samples, "cn-9491803-device.apk")
            val b = File(samples, "cn-9500803-device.apk")
            if (!a.isFile || !b.isFile || !File(tables, "9491803.json").isFile) return
            old = DexIndex.load(a)
            new = DexIndex.load(b)
            oldTable = SymbolTable.fromJson(File(tables, "9491803.json").readText())
            newTable = SymbolTable.fromJson(File(tables, "9500803.json").readText())
        }

        @AfterClass @JvmStatic
        fun free() { old = null; new = null }
    }

    @Test
    fun `repair finds the real 9_50_0 fix for attachBrowser`() {
        assumeTrue("no samples", old != null)
        val controller = "com.xingin.matrix.notedetail.imagecontent.imagegallery.AsyncImageGalleryController"
        // 修之前的查询：控制器里打 attachImageBrowser 日志、(boolean) → void
        val before = MethodSymbol("allMediaViewer.attachBrowser", MethodQuery(owner = type(controller), shape = MethodShape(params = listOf(type("boolean")), returns = type("void"), strings = listOf("attachImageBrowser"))))
        assertTrue("旧版能找到", SymbolResolver(old!!, 0).resolve(before).isSuccess)
        assertTrue("新版找不到", SymbolResolver(new!!, 0).resolve(before).isFailure)
        val fix = Repair(old!!, new!!).repair(before)
        System.err.println("repair: ${fix.diagnosis} → ${fix.action} → ${fix.newLocation}\n${fix.yaml}")
        assertEquals("去掉条件 params", fix.action)
        assertEquals(text(newTable.entries.getValue("allMediaViewer.attachBrowser")), fix.newLocation)
    }

    @Test
    fun `suggest writes a query for hideOverlay that survives the upgrade`() {
        assumeTrue("no samples", old != null)
        val target = oldTable.entries.getValue("feedCopy.hideOverlay")
        val s = Suggester(old!!, others = listOf(new!!)).suggest(target, "feedCopy.hideOverlay")
        System.err.println("suggest hideOverlay: ${s.why}\n" + io.github.wzgrx.dexadapt.spec.Spec(null, io.github.wzgrx.dexadapt.Catalog(s.extra + listOfNotNull(s.query))).toYaml())
        assertNotNull(s.query)
        assertEquals(newTable.entries.getValue("feedCopy.hideOverlay"), SymbolResolver(new!!, 0).resolve(s.query!!).getOrNull())
    }

    @Test
    fun `suggested class queries land on the right class in the other version`() {
        assumeTrue("no samples", old != null)
        val ids = oldTable.entries.filter { it.value is Resolved.Class && newTable.entries[it.key] is Resolved.Class }.keys.sorted()
        val sample = ids.filterIndexed { i, _ -> i % 3 == 0 }
        var made = 0
        var right = 0
        val wrong = ArrayList<String>()
        for (id in sample) {
            val s = Suggester(old!!, others = listOf(new!!)).suggest(oldTable.entries.getValue(id), id)
            val q = s.query ?: continue
            made++
            val got = SymbolResolver(new!!, 0).resolve(q).getOrNull()
            if (got == newTable.entries[id]) right++ else wrong += "$id (${s.why}) → $got, truth ${newTable.entries[id]}"
        }
        System.err.println("suggest classes: ${sample.size} tried, $made generated, $right land on the right class in 9.50.0")
        wrong.take(10).forEach { System.err.println("  wrong $it") }
        assertTrue("generated too few", made >= sample.size * 0.8)
        assertTrue("wrong class in the other version", right >= made * 0.95)
    }

    private fun text(r: Resolved) = Repair.text(r)
}
