package io.github.wzgrx.dexadapt.dexlib

import io.github.wzgrx.dexadapt.MethodSymbol
import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.TypeRef
import io.github.wzgrx.dexadapt.resolve.SymbolResolver
import io.github.wzgrx.dexadapt.spec.Fingerprints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerprintImportTest {
    private val revanced = """
        package app.revanced.patches.youtube.ad

        // 新写法
        internal val showAdFingerprint = fingerprint {
            accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
            returns("V")
            parameters("Ljava/lang/String;", "I", "L")
            opcodes(
                Opcode.CONST_STRING,
                null,
                Opcode.INVOKE_VIRTUAL,
            )
            strings("ad_break (", "slot")
            custom { method, classDef ->
                method.name == "show" && classDef.type == "Lcom/example/AdController;"
            }
        }

        /* 旧写法 */
        object LegacyStaticFingerprint : MethodFingerprint(
            returnType = "Z",
            accessFlags = AccessFlags.PUBLIC or AccessFlags.STATIC,
            parameters = listOf(),
            strings = listOf("is_premium"),
            customFingerprint = { methodDef, classDef -> classDef.type.endsWith("/PremiumUtil;") },
        )
    """.trimIndent()

    @Test
    fun `revanced fingerprints become queries`() {
        val r = Fingerprints.import(revanced, Fingerprints.Format.REVANCED, "yt")
        val byId = r.symbols.associateBy { it.id }
        val ad = byId.getValue("yt.showAd").query
        assertEquals("show", ad.shape.name)
        assertEquals(TypeRef.Exact("com.example.AdController"), ad.owner)
        assertEquals(listOf(TypeRef.Exact("java.lang.String"), TypeRef.Exact("int"), TypeRef.Any), ad.shape.params)
        assertEquals(TypeRef.Exact("void"), ad.shape.returns)
        assertEquals(listOf("const-string", "invoke-virtual"), ad.shape.opcodes)
        assertEquals(listOf("ad_break (", "slot"), ad.shape.strings)
        val legacy = byId.getValue("yt.legacyStatic").query
        assertEquals(true, legacy.shape.isStatic)
        assertEquals(emptyList<TypeRef>(), legacy.shape.params)
        assertEquals(listOf("is_premium"), legacy.shape.strings)
        assertTrue(r.notes.joinToString(), r.notes.any { it.contains("后缀") } && r.notes.any { it.contains("连续") })
    }

    @Test
    fun `imported query resolves on a matching method`() {
        val ad = Fingerprints.import(revanced, Fingerprints.Format.REVANCED, "yt").symbols.first { it.id == "yt.showAd" }
        val cls = ClassInfo("com.example.AdController", "java.lang.Object", emptyList(), 1)
        val ops = listOf("const-string", "move-result", "invoke-virtual", "return-void").map(OpcodeNames::id).toByteArray()
        cls.methods += MethodInfo(cls.name, "show", listOf("java.lang.String", "int", "android.view.View"), "void", 0x11, setOf("ad_break (", "slot"), emptySet(), emptySet(), ops)
        cls.methods += MethodInfo(cls.name, "show", listOf("java.lang.String", "int"), "void", 0x11, setOf("ad_break ("), emptySet(), emptySet(), ops)
        val got = SymbolResolver(DexIndex.of(listOf(cls)), 1).resolve(ad).getOrThrow()
        assertEquals(Resolved.Method("com.example.AdController", "show", listOf("java.lang.String", "int", "android.view.View"), "void"), got)
    }

    @Test
    fun `morphe filters and qauxiliary targets`() {
        val morphe = """
            internal val playerFingerprint = Fingerprint(
                returnType = "V",
                parameters = listOf("Landroid/content/Context;"),
                filters = listOf(
                    string("player_init"),
                    literal(0x7f0a01L),
                    methodCall(definingClass = "Landroid/view/View;", name = "setVisibility"),
                ),
            )
        """.trimIndent()
        val p = Fingerprints.import(morphe, Fingerprints.Format.MORPHE, "m").symbols.single().query
        assertEquals(listOf("player_init"), p.shape.strings)
        assertEquals(listOf(0x7f0a01L), p.shape.numbers)
        assertEquals(listOf("android.view.View#setVisibility"), p.callsExact)

        val qa = """
            data object NBaseChatPie_init : DexKitTarget.UsingStr() {
                override val findMethod: Boolean = true
                override val traitString = arrayOf("input set error", "Q.aio.BaseChatPie")
                override val declaringClass = "com.tencent.mobileqq.activity.aio.core.BaseChatPie"
                override val filter = DexKitFilter.allowAll
            }
        """.trimIndent().replace("data object", "object")
        val q = Fingerprints.import(qa, Fingerprints.Format.QAUXILIARY, "qq")
        val s: MethodSymbol = q.symbols.single()
        assertEquals("qq.nBaseChatPie_init", s.id)
        assertEquals(listOf("input set error"), s.query.shape.strings)
        assertEquals(TypeRef.Exact("com.tencent.mobileqq.activity.aio.core.BaseChatPie"), s.query.owner)
        assertTrue(q.notes.single().contains("或"))
    }

    @Test
    fun `export back to revanced`() {
        val text = Fingerprints.toRevanced(Fingerprints.import(revanced, Fingerprints.Format.REVANCED, "yt").symbols)
        assertTrue(text, text.contains("internal val showAdFingerprint = fingerprint {"))
        assertTrue(text, text.contains("parameters(\"Ljava/lang/String;\", \"I\", \"L\")"))
        assertTrue(text, text.contains("opcodes(Opcode.CONST_STRING, Opcode.INVOKE_VIRTUAL)"))
        assertTrue(text, text.contains("custom { method, classDef -> method.name == \"show\" && classDef.type == \"Lcom/example/AdController;\" }"))
        assertTrue(text, text.contains("accessFlags(AccessFlags.STATIC)"))
    }
}
