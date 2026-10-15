package io.github.wzgrx.dexadapt.resolve

import io.github.wzgrx.dexadapt.ClassQuery
import io.github.wzgrx.dexadapt.TypeRef

/** 单测用的内存数据源：几行代码搭出一个“APK”，语义和电脑上的全量索引一致（候选全给，由解析器核对）。 */
class FakeSource(private val classes: List<FakeClass>) : SymbolSource {
    override val dexSha256 = "fake"
    private val byName = classes.associateBy { it.name }

    override fun classByName(name: String): ClassView? = byName[name]
    override fun methodsUsingString(s: String): List<MethodView> = classes.flatMap { it.methods }.filter { s in it.strings }
    override fun classesInPackage(packagePrefix: String): Sequence<ClassView> = classes.asSequence().filter { it.name.startsWith("$packagePrefix.") }
    override fun classesInExactPackage(pkg: String): Sequence<ClassView> = classes.asSequence().filter { it.name.substringBeforeLast('.') == pkg }
    override fun candidateClasses(q: ClassQuery, typeName: (TypeRef) -> String): Sequence<ClassView> = classes.asSequence()
}

class FakeClass(
    override val name: String,
    override val superName: String? = "java.lang.Object",
    override val interfaces: List<String> = emptyList(),
    override val flags: Int = 0,
) : ClassView {
    override val fields = ArrayList<FieldView>()
    override val methods = ArrayList<MethodView>()

    fun field(name: String, type: String, flags: Int = 0) = apply { fields += FakeField(this.name, name, type, flags) }

    fun method(
        name: String,
        params: List<String> = emptyList(),
        returns: String = "void",
        flags: Int = 0,
        strings: Set<String> = emptySet(),
        calls: Set<String> = emptySet(),
        fieldRefs: Set<String> = emptySet(),
    ) = apply { methods += FakeMethod(this.name, name, params, returns, flags, strings, calls, fieldRefs) }
}

data class FakeField(override val owner: String, override val name: String, override val type: String, override val flags: Int) : FieldView

data class FakeMethod(
    override val owner: String,
    override val name: String,
    override val params: List<String>,
    override val returns: String,
    override val flags: Int,
    override val strings: Set<String>,
    override val calls: Set<String>,
    override val fieldRefs: Set<String>,
) : MethodView
