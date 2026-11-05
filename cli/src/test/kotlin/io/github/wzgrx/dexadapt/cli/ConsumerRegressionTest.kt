package io.github.wzgrx.dexadapt.cli

import io.github.wzgrx.dexadapt.SymbolTable
import io.github.wzgrx.dexadapt.resolve.SymbolResolver
import io.github.wzgrx.dexadapt.spec.Spec
import io.github.wzgrx.dexadapt.resolve.forBuild
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File

/** 样本路径和golden均由本机manifest提供；宿主APK及数据不进入工具源码或分发包。 */
class ConsumerRegressionTest {
    @Serializable private data class Case(val label: String, val apk: String, val spec: String, val table: String, val build: String, val packageName: String, val ids: List<String>)

    @Test fun `consumer history roles preserve golden targets with both engines`() {
        val path=System.getenv("DEXADAPT_CONSUMER_MANIFEST")
        val required=System.getenv("DEXADAPT_REQUIRE_CONSUMERS")=="1"
        if(path.isNullOrBlank()) {
            assertFalse("发版要求跨模块样本，但没有提供manifest",required)
            assumeTrue("未配置跨模块样本manifest",false)
            return
        }
        val cases=Json.decodeFromString(ListSerializer(Case.serializer()),File(path).readText())
        assertTrue("需要至少两个宿主包名",cases.map { it.packageName }.distinct().size>=2)
        for(case in cases) {
            val spec=Spec.read(File(case.spec))
            val wanted=spec.catalog.symbols.filter { it.id in case.ids }
            assertEquals(case.ids.toSet(),wanted.map { it.id }.toSet())
            val golden=SymbolTable.fromJson(File(case.table).readText())
            var digest: String?=null
            for(engine in listOf(Engine.DEXLIB,Engine.DEXKIT)) {
                Engines.open(File(case.apk),engine).use { opened ->
                    assertEquals(case.packageName,opened.info.packageName)
                    assertEquals(golden.versionCode,opened.info.versionCode)
                    val actual=SymbolResolver.forBuild(opened.source,opened.info.versionCode,case.build).table(wanted)
                    assertTrue("${case.label} $engine: ${actual.missing}",actual.missing.isEmpty())
                    for(id in case.ids)assertEquals("${case.label} $engine $id",golden.entries.getValue(id),actual.entries[id])
                    if(digest==null)digest=actual.dexSha256 else assertEquals(digest,actual.dexSha256)
                    println("${case.label} $engine ${actual.entries.size} roles verified")
                }
            }
        }
    }
}
