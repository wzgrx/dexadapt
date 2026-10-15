package io.github.wzgrx.dexadapt

import io.github.wzgrx.dexadapt.codegen.Accessors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessorsTest {
    private object Note : SymbolGroup("note") {
        val feed = cls("feed", ClassQuery(name = "a.NoteFeed"))
        val poi = field("poi", FieldQuery(TypeRef.Of(feed), name = "poi"))
        val title = field("title", FieldQuery(TypeRef.Of(feed), name = "title", type = type("java.lang.String")))
        val count = field("count", FieldQuery(TypeRef.Of(feed), name = "likedCount", type = type("long")))
        val bind = method("detail.bind", MethodQuery(owner = TypeRef.Of(feed), shape = MethodShape(params = listOf(type("android.view.View"), TypeRef.Of(feed)), returns = type("void"))))
        val list = method("list", MethodQuery(owner = TypeRef.Of(feed), shape = MethodShape(name = "list", returns = type("java.util.List"))))
        val init = method("init", MethodQuery(owner = TypeRef.Of(feed), shape = MethodShape(name = "<init>", params = listOf(type("int")))))
        val `class` = cls("class", ClassQuery(name = "a.Other"))
        val `in` = cls("in", ClassQuery(name = "a.In"))
    }

    @Test
    fun `generates typed accessors per group`() {
        val text = Accessors.kotlin(Catalog(Note), Accessors.Options("x.y"))
        listOf(
            "class HostAccess(private val linker: SymbolLinker, catalog: Catalog)",
            "val note: Note = Note()",
            "val feed: Class<*> get() = c(\"note.feed\")",
            "fun poi(target: Any?): Any? = read(\"note.poi\", target)",
            "fun title(target: Any?): String? = read(\"note.title\", target) as String?",
            "fun countOrNull(target: Any?): Long? = readOrNull(\"note.count\", target) as Long?",
            "fun setCount(target: Any?, value: Long) = f(\"note.count\").set(target, value)",
            "fun detailBind(receiver: Any?, a0: android.view.View?, a1: Any?) { call(\"note.detail.bind\", receiver, arrayOf<Any?>(a0, a1)) }",
            "fun list(receiver: Any?, vararg args: Any?): List<*>? = call(\"note.list\", receiver, args) as List<*>?",
            "fun init(a0: Int): Any = call(\"note.init\", null, arrayOf<Any?>(a0)) as Any",
            "val clazz: Class<*> get() = c(\"note.class\")",
            "val `in`: Class<*> get() = c(\"note.in\")",
            "val inOrNull: Class<*>? get()",
        ).forEach { assertTrue("missing: $it\n$text", text.contains(it)) }
        assertEquals("Any?", Accessors.kotlinType(TypeRef.Exact("com.xingin.Foo")))
        assertEquals("Any?", Accessors.kotlinType(TypeRef.Exact("android.util.SparseArray")))
        assertEquals("android.widget.TextView?", Accessors.kotlinType(TypeRef.Exact("android.widget.TextView")))
    }
}
