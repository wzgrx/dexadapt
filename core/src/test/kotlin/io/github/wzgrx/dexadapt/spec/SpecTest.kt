package io.github.wzgrx.dexadapt.spec

import io.github.wzgrx.dexadapt.Catalog
import io.github.wzgrx.dexadapt.ClassQuery
import io.github.wzgrx.dexadapt.FieldQuery
import io.github.wzgrx.dexadapt.MethodQuery
import io.github.wzgrx.dexadapt.MethodShape
import io.github.wzgrx.dexadapt.MethodSymbol
import io.github.wzgrx.dexadapt.Pin
import io.github.wzgrx.dexadapt.SymbolGroup
import io.github.wzgrx.dexadapt.TypeRef
import io.github.wzgrx.dexadapt.absentIn
import io.github.wzgrx.dexadapt.pin
import io.github.wzgrx.dexadapt.type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecTest {
    /** 用到全部字段的一组符号。 */
    private object Full : SymbolGroup("full") {
        val base = cls("base", ClassQuery(name = "a.Base"))
        val bean = cls(
            "bean",
            ClassQuery(
                packagePrefix = "a.b", superclass = TypeRef.Of(base), interfaces = listOf(type("java.io.Serializable")), strings = listOf("Bean(id="),
                fieldTypes = listOf(type("int")), fieldNames = listOf("id"), methods = listOf(MethodShape(name = "getId", returns = type("int"))),
                isInterface = false, isAbstract = false, sourceFile = "Bean.kt", annotations = listOf("kotlin.Metadata"),
            ),
        )
        val id = field("id", FieldQuery(TypeRef.Of(bean), name = "id", type = type("int"), isStatic = false))
        val getter = method(
            "getter",
            MethodQuery(
                owner = TypeRef.Of(bean), shape = MethodShape(params = emptyList(), returns = type("int"), isStatic = false, numbers = listOf(-1, 4096),
                    annotations = listOf("retrofit2.http.GET", "/api/x"), opcodes = listOf("iget", "return")),
                usesFields = listOf(id), onlyTheseFields = true, callsExact = listOf("android.view.View#setVisibility"),
            ),
        )
        val caller = method("caller", MethodQuery(packagePrefix = "a.b", shape = MethodShape(strings = listOf("go"), params = listOf(TypeRef.Any, TypeRef.SuperOf(bean))), calls = listOf(getter), calledBy = listOf(getter), excludes = listOf(getter)))
        val made = cls("made", ClassQuery(instantiatedIn = listOf(caller), declares = listOf(getter), samePackageAs = TypeRef.Of(bean), fieldOf = TypeRef.Of(bean)))
        val pinned = cls("pinned", ClassQuery(strings = listOf("p"))).pin("CN_1", Pin.Class("a.P")).absentIn("PLAY_1", "PLAY_2")
        val pinnedMethod = method("pm", MethodQuery(owner = TypeRef.Of(bean))).pin("CN_1", Pin.Method("a.B", "m", listOf("int", "a.C[]")))
        val pinnedField = field("pf", FieldQuery(TypeRef.Of(bean), accessedIn = listOf(getter))).pin("CN_1", Pin.Field("a.B", "f"))
    }

    @Test
    fun `yaml and json round trip keep the catalog fingerprint`() {
        val catalog = Catalog(Full)
        val spec = Spec("com.example", catalog)
        val fromYaml = Spec.fromYaml(spec.toYaml())
        val fromJson = Spec.fromJson(spec.toJson())
        assertEquals("com.example", fromYaml.app)
        assertEquals(catalog.fingerprint(), fromYaml.catalog.fingerprint())
        assertEquals(catalog.fingerprint(), fromJson.catalog.fingerprint())
        assertEquals(spec.toYaml(), Spec(fromYaml.app, fromYaml.catalog).toYaml())
        assertTrue(fromYaml.catalog.problems().isEmpty())
    }

    @Test
    fun `references can come before their targets and read naturally`() {
        val yaml = """
            dexadapt: 1
            app: com.xingin.xhs
            symbols:
              - id: feedCopy.showOverlay
                method:
                  owner: ${'$'}feedCopy.overlay
                  params: [java.lang.String, int, ${'$'}feedCopy.feedbackBean]
                  returns: void
              - id: feedCopy.overlay
                class: { strings: [feedbackOverlay, dsl/negative_feedback_mask_view] }
              - id: feedCopy.feedbackBean
                class: { strings: ["CommonFeedBackBean(position="] }
        """.trimIndent()
        val spec = Spec.fromYaml(yaml)
        assertEquals(listOf("feedCopy.showOverlay", "feedCopy.overlay", "feedCopy.feedbackBean"), spec.catalog.symbols.map { it.id })
        val show = spec.catalog.symbols.first() as MethodSymbol
        assertEquals("feedCopy.overlay", (show.query.owner as TypeRef.Of).symbol.id)
        assertEquals(TypeRef.Exact("int"), show.query.shape.params!![1])
    }

    @Test
    fun `mistakes are explained`() {
        fun err(yaml: String) = runCatching { Spec.fromYaml(yaml) }.exceptionOrNull()?.message.orEmpty()
        assertTrue(err("symbols:\n  - id: a\n    method: { owner: \$b }").contains("unknown symbol \$b"))
        assertTrue(err("symbols:\n  - id: a\n    class: { superclass: \$b }\n  - id: b\n    class: { superclass: \$a }").contains("dependency cycle"))
        assertTrue(err("symbols:\n  - id: a\n    class: { name: A }\n  - id: a\n    class: { name: B }").contains("duplicate"))
        assertTrue(err("symbols:\n  - id: a\n    widget: {}").contains("class / method / field"))
        assertTrue(err("dexadapt: 99\nsymbols: []").contains("99"))
    }
}
