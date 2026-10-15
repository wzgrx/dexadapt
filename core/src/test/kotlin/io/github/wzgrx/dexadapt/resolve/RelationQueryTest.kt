package io.github.wzgrx.dexadapt.resolve

import io.github.wzgrx.dexadapt.ClassQuery
import io.github.wzgrx.dexadapt.ClassSymbol
import io.github.wzgrx.dexadapt.FieldQuery
import io.github.wzgrx.dexadapt.FieldSymbol
import io.github.wzgrx.dexadapt.MethodQuery
import io.github.wzgrx.dexadapt.MethodShape
import io.github.wzgrx.dexadapt.MethodSymbol
import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.TypeRef
import io.github.wzgrx.dexadapt.type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 第二波新增的关系条件（calledBy / instantiatedIn / declares / accessedIn）、余量和多来源数据源。 */
class RelationQueryTest {
    // 一个“定位”小模型：门面 F 有两个形状相同的 ()→B，只有 h 被 Geo#b 调用；分发器 U 的 b 被 Timer#ok 调用；
    // Picker#g new 了事件 E1（和 E2 形状相同）；F 有两个 boolean 字段，只有 flag 被 wrap 读。
    private val f = FakeClass("loc.F")
        .field("flag", "boolean").field("other", "boolean")
        .method("c", returns = "loc.B", fieldRefs = setOf("loc.F#flag"))
        .method("h", returns = "loc.B")
        .method("s", listOf("loc.Cb"), returns = "loc.Cb", fieldRefs = setOf("loc.F#flag"), calls = setOf("loc.F\$c#<init>(loc.Cb)void"))
    private val wrapper = FakeClass("loc.F\$c").method("<init>", listOf("loc.Cb")).method("x", strings = setOf("transform error"))
    private val geo = FakeClass("net.Geo").method("b", listOf("android.content.Context"), "java.lang.String", strings = setOf("kv_location"), calls = setOf("loc.F#h()loc.B", "loc.U#b(loc.Cb,loc.B)void"))
    private val u = FakeClass("loc.U").method("a", listOf("loc.Cb", "loc.Err"), flags = AccessFlag.STATIC).method("b", listOf("loc.Cb", "loc.B"), flags = AccessFlag.STATIC)
    private val v = FakeClass("loc.V").method("b", listOf("loc.Cb", "loc.B"), flags = AccessFlag.STATIC)
    private val timer = FakeClass("loc.Timer").method("ok", listOf("loc.B"), strings = setOf("OnLocationSuccess"), calls = setOf("loc.U#b(loc.Cb,loc.B)void", "loc.U#a(loc.Cb,loc.Err)void"))
    private val e1 = FakeClass("ui.player.b\$c", superName = "ui.player.b").method("<init>")
    private val e2 = FakeClass("ui.player.b\$a", superName = "ui.player.b").method("<init>")
    private val picker = FakeClass("ui.player.e").method("g", strings = setOf("NeedToStart"), calls = setOf("ui.player.b\$c#<init>()void", "android.graphics.Rect#<init>()void"))
    private val source = FakeSource(listOf(f, wrapper, geo, u, v, timer, e1, e2, picker))
    private fun resolver(src: SymbolSource = source) = SymbolResolver(src, 1)

    private val facade = ClassSymbol("t.facade", ClassQuery(name = "loc.F"))
    private val networkGeo = MethodSymbol("t.geo", MethodQuery(shape = MethodShape(strings = listOf("kv_location"))))
    private val timedOk = MethodSymbol("t.ok", MethodQuery(shape = MethodShape(strings = listOf("OnLocationSuccess"))))
    private val pick = MethodSymbol("t.pick", MethodQuery(shape = MethodShape(strings = listOf("NeedToStart"))))

    @Test
    fun `calledBy picks the sibling a known method calls`() {
        val shape = MethodShape(params = emptyList(), returns = type("loc.B"))
        val loose = MethodSymbol("t.loose", MethodQuery(owner = TypeRef.Of(facade), shape = shape))
        assertTrue(resolver().resolve(loose).exceptionOrNull()!!.message!!.startsWith("2 matches"))
        val gps = MethodSymbol("t.gps", MethodQuery(owner = TypeRef.Of(facade), shape = shape, calledBy = listOf(networkGeo)))
        assertEquals("h", (resolver().resolve(gps).getOrThrow() as Resolved.Method).name)
        // 没有所属类时，候选就是调用者调用到的方法；两个调用者都调到的才算。
        val dispatchShape = MethodShape(params = listOf(TypeRef.Any, type("loc.B")), returns = type("void"), isStatic = true)
        val dispatch = MethodSymbol("t.dispatch", MethodQuery(shape = dispatchShape, calledBy = listOf(timedOk, networkGeo)))
        assertEquals(Resolved.Method("loc.U", "b", listOf("loc.Cb", "loc.B"), "void"), resolver().resolve(dispatch).getOrThrow())
        // declares：类就是那个方法的所属类（V 形状一样，但不是它）。
        val owner = ClassSymbol("t.dispatcher", ClassQuery(methods = listOf(dispatchShape), declares = listOf(dispatch)))
        assertEquals(Resolved.Class("loc.U"), resolver().resolve(owner).getOrThrow())
        val shapeOnly = ClassSymbol("t.shapeOnly", ClassQuery(methods = listOf(dispatchShape)))
        assertTrue(resolver().resolve(shapeOnly).exceptionOrNull()!!.message!!.startsWith("2 matches"))
    }

    @Test
    fun `instantiatedIn finds the class a known method news up`() {
        val event = ClassSymbol("t.event", ClassQuery(packagePrefix = "ui", instantiatedIn = listOf(pick), methods = listOf(MethodShape(name = "<init>", params = emptyList()))))
        assertEquals(Resolved.Class("ui.player.b\$c"), resolver().resolve(event).getOrThrow())
        val byShape = ClassSymbol("t.byShape", ClassQuery(superclass = type("ui.player.b")))
        assertTrue(resolver().resolve(byShape).exceptionOrNull()!!.message!!.startsWith("2 matches"))
    }

    @Test
    fun `accessedIn tells same-typed fields apart`() {
        val init = MethodSymbol("t.init", MethodQuery(owner = TypeRef.Of(ClassSymbol("t.w", ClassQuery(strings = listOf("transform error")))), shape = MethodShape(name = "<init>")))
        val wrap = MethodSymbol("t.wrap", MethodQuery(owner = TypeRef.Of(facade), calls = listOf(init)))
        val loose = FieldSymbol("t.loose", FieldQuery(TypeRef.Of(facade), type = type("boolean")))
        assertTrue(resolver().resolve(loose).exceptionOrNull()!!.message!!.startsWith("2 matches"))
        val flag = FieldSymbol("t.flag", FieldQuery(TypeRef.Of(facade), type = type("boolean"), accessedIn = listOf(wrap)))
        assertEquals(Resolved.Field("loc.F", "flag", "boolean"), resolver().resolve(flag).getOrThrow())
    }

    @Test
    fun `margin counts candidates with one constraint dropped`() {
        val shape = MethodShape(params = emptyList(), returns = type("loc.B"))
        val gps = MethodSymbol("t.gps", MethodQuery(owner = TypeRef.Of(facade), shape = shape, calledBy = listOf(networkGeo)))
        val m = resolver().margin(gps)!!
        // 去掉 calledBy 剩 c、h 两个：唯一性只靠它（单点依赖）；去掉参数或返回值仍唯一。
        assertEquals(listOf("params→1", "returns→1", "calledBy[t.geo]→2"), m.drops.map { it.toString() })
        assertTrue(m.singlePoint)
        assertFalse(m.shapeOnly) // 关键条件是调用关系，不是形状
        val flag = FieldSymbol("t.flag2", FieldQuery(TypeRef.Of(facade), type = type("boolean"), accessedIn = listOf(MethodSymbol("t.c", MethodQuery(owner = TypeRef.Of(facade), shape = MethodShape(name = "c"))))))
        val fm = resolver().margin(flag)!!
        assertEquals(listOf("type→1", "accessedIn[t.c]→2"), fm.drops.map { it.toString() })
        // 按类名找的类没有可去掉的条件。
        assertEquals(null, resolver().margin(facade))
        val report = MarginReport.of("fp", mapOf(1L to listOf(m, fm)))
        assertEquals(setOf("t.gps", "t.flag2"), report.singlePoint)
        assertEquals(report, MarginReport.fromJson(report.toJson()))
    }

    @Test
    fun `string prefetch gets every start string once and multi source prefers the first apk`() {
        val asked = ArrayList<Collection<String>>()
        val counting = object : SymbolSource by source {
            override fun prefetchStrings(strings: Collection<String>) { asked += strings }
        }
        resolver(counting).table(listOf(networkGeo, timedOk, facade))
        assertEquals(listOf(setOf("kv_location", "OnLocationSuccess")), asked.map { it.toSet() })

        // 拆分包里也有一个 loc.F（不会被加载）和一个只在拆分包里的类。
        val split = FakeSource(listOf(FakeClass("loc.F").method("zz", strings = setOf("kv_location")), FakeClass("split.Only").method("o", strings = setOf("kv_location"))))
        val multi = MultiSource(listOf(source, split))
        assertEquals(f, multi.classByName("loc.F"))
        assertEquals(setOf("net.Geo", "split.Only"), multi.methodsUsingString("kv_location").map { it.owner }.toSet())
        assertEquals(listOf("loc.F", "loc.F\$c", "loc.U", "loc.V", "loc.Timer"), multi.classesInPackage("loc").map { it.name }.toList())
    }
}
