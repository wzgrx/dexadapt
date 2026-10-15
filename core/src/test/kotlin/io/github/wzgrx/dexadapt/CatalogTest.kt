package io.github.wzgrx.dexadapt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {
    private object Demo : SymbolGroup("demo") {
        val bean = cls("bean", ClassQuery(strings = listOf("Bean(id=")))
        val show = method("show", MethodQuery(owner = TypeRef.Of(bean), shape = MethodShape(params = listOf(type("int")), returns = type("void"))))
        val id = field("id", FieldQuery(TypeRef.Of(bean), name = "id"))
    }

    @Test
    fun `fingerprint is stable and follows queries, pins, absences and engine`() {
        val catalog = Catalog(Demo)
        assertEquals(listOf("demo.bean", "demo.show", "demo.id"), catalog.symbols.map { it.id })
        val base = catalog.fingerprint("engine=1\n")
        assertEquals(16, base.length)
        assertEquals(base, Catalog(Demo).fingerprint("engine=1\n"))
        assertNotEquals(base, catalog.fingerprint("engine=2\n"))
        // 定义文字：查询的 toString 加上钉子（按构建标识排序）和“没有”的标注。
        val pinned = Catalog(listOf(ClassSymbol("x.a", ClassQuery(name = "a.A")).pin("CN_2", Pin.Class("a.A")).pin("CN_1", Pin.Class("a.A")).absentIn("PLAY_1")))
        val text = "x.a|ClassQuery(name=a.A, packagePrefix=null, superclass=null, interfaces=[], strings=[], fieldTypes=[], fieldNames=[], methods=[], " +
            "isInterface=null, isAbstract=null, samePackageAs=null, fieldOf=null, instantiatedIn=[], declares=[], sourceFile=null, annotations=[])|[CN_1=Class(name=a.A), CN_2=Class(name=a.A)]|[PLAY_1]"
        val expected = Hex.of(java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())).take(16)
        assertEquals(expected, pinned.fingerprint())
    }

    @Test
    fun `duplicate ids and broken references are reported`() {
        val dup = runCatching { Catalog(listOf(ClassSymbol("a", ClassQuery(name = "A")), ClassSymbol("a", ClassQuery(name = "B")))) }
        assertTrue(dup.exceptionOrNull()?.message.orEmpty().contains("duplicate"))
        val outside = ClassSymbol("o.c", ClassQuery(name = "C"))
        val user = MethodSymbol("o.m", MethodQuery(owner = TypeRef.Of(outside)))
        assertTrue(Catalog(listOf(user)).problems().single().contains("not in the catalog"))
        assertTrue(Catalog(Demo).problems().isEmpty())
    }

    @Test
    fun `an unknown entry kind only drops that entry`() {
        val text = """
            {"versionCode": 1, "dexSha256": "x", "entries": {
              "a.cls": {"kind": "class", "name": "a.B"},
              "a.future": {"kind": "constructorRef", "owner": "a.B"},
              "a.broken": {"kind": "method", "owner": "a.B"}
            }, "missing": {"a.old": "no match"}}
        """.trimIndent()
        val t = SymbolTable.fromJson(text)
        assertEquals(mapOf("a.cls" to Resolved.Class("a.B")), t.entries)
        assertEquals(setOf("a.old", "a.future", "a.broken"), t.missing.keys)
        assertTrue(t.missing.getValue("a.future").contains("constructorRef"))
        val ok = SymbolTable(1, "x", mapOf("m" to Resolved.Method("a.B", "c", listOf("int"), "void")))
        assertEquals(ok, SymbolTable.fromJson(ok.toJson()))
    }

    @Test
    fun `hex matches the usual encoding`() {
        assertEquals("00ff10ab", Hex.of(byteArrayOf(0, -1, 16, -85)))
    }
}
