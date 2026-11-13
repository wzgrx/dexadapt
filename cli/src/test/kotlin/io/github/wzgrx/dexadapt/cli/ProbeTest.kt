package io.github.wzgrx.dexadapt.cli

import io.github.wzgrx.dexadapt.ClassQuery
import io.github.wzgrx.dexadapt.TypeRef
import io.github.wzgrx.dexadapt.resolve.ClassView
import io.github.wzgrx.dexadapt.resolve.FieldView
import io.github.wzgrx.dexadapt.resolve.MethodView
import io.github.wzgrx.dexadapt.resolve.SymbolResolver
import io.github.wzgrx.dexadapt.resolve.SymbolSource
import io.github.wzgrx.dexadapt.spec.Spec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeTest {
    @Test fun `selected root includes transitive regressions but ignores independent symbols`() {
        val source = object : SymbolSource {
            override val dexSha256 = "fixture"
            override fun methodsUsingString(s: String): List<MethodView> = emptyList()
            override fun classByName(name: String): ClassView? = if (name == "example.Existing") {
                object : ClassView {
                    override val name = "example.Existing"
                    override val superName = "java.lang.Object"
                    override val interfaces = emptyList<String>()
                    override val flags = 0
                    override val methods = emptyList<MethodView>()
                    override val fields = emptyList<FieldView>()
                }
            } else null
            override fun classesInPackage(packagePrefix: String): Sequence<ClassView> = emptySequence()
            override fun classesInExactPackage(pkg: String): Sequence<ClassView> = emptySequence()
            override fun candidateClasses(q: ClassQuery, typeName: (TypeRef) -> String): Sequence<ClassView> = emptySequence()
        }
        val spec = """
            symbols:
              - id: owner
                class: {name: example.Existing}
              - id: render
                method: {owner: ${'$'}owner, name: render}
              - id: child
                method: {owner: ${'$'}owner, calledBy: [${'$'}render]}
              - id: unrelated
                class: {name: example.Missing}
        """.trimIndent()
        val before = Spec.fromYaml(spec).catalog.symbols
        val after = Spec.fromYaml(spec.replace("example.Existing", "example.New")).catalog.symbols
        val rows = probeRows(after, before, listOf("owner"), SymbolResolver(source, 1), SymbolResolver(source, 1)).associateBy { it.id }
        assertEquals(setOf("owner", "render", "child"), rows.keys)
        assertEquals("regressed", rows.getValue("owner").change)
        assertEquals("example.Existing", rows.getValue("owner").baseline?.target)
        assertEquals(listOf("owner"), rows.getValue("render").current.dependencyPath)
        assertEquals("blocked", rows.getValue("child").current.status)
        assertTrue(ProbeReport("example", 1, null, "", "dexlib", 0, 0, rows.values.toList()).failed)
    }
}
