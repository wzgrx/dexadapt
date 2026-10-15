package io.github.wzgrx.dexadapt.jadx

import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.dexlib.DexIndex
import io.github.wzgrx.dexadapt.synth.Suggester
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** jadx 的节点转成的位置，在 DexAdapt 的索引里能找到（用红薯助手自己的 APK：只压缩不混淆）。 */
class JadxNodesTest {
    @Test
    fun `jadx nodes map to locations dexadapt knows`() {
        val apk = File(System.getProperty("user.home") + "/projects/redbook-helper-offline/app/build/outputs/apk/release/app-release.apk")
        assumeTrue(apk.isFile)
        val args = JadxArgs().apply { inputFiles.add(apk); isSkipResources = true }
        JadxDecompiler(args).use { jadx ->
            jadx.load()
            val cls = jadx.classes.first { it.rawName == "com.skyhand.redbookhelper.app.adapt.HostAdapter" }
            val method = cls.methods.first { !it.isConstructor && !it.isClassInit }
            val index = DexIndex.load(apk)
            val suggester = Suggester(index)
            val c = DexAdaptJadxPlugin.target(cls) as Resolved.Class
            val m = DexAdaptJadxPlugin.target(method) as Resolved.Method
            assertTrue(c.name, index.classByName(c.name) != null)
            assertTrue(m.toString(), index.classByName(m.owner)!!.methods.any { it.name == m.name && it.params == m.params && it.returns == m.returns })
            val field = cls.fields.first()
            val f = DexAdaptJadxPlugin.target(field) as Resolved.Field
            assertTrue(f.toString(), index.classByName(f.owner)!!.fields.any { it.name == f.name && it.type == f.type })
            val s = suggester.suggest(m, "jadx.evaluate")
            assertTrue(s.why, !s.why.contains("不在这个版本里"))
            System.err.println("suggest ${m.name}: ${s.why} -> ${s.query}")
        }
    }
}
