package io.github.wzgrx.dexadapt.dexkit

import io.github.wzgrx.dexadapt.ClassQuery
import io.github.wzgrx.dexadapt.MethodQuery
import io.github.wzgrx.dexadapt.TypeRef
import io.github.wzgrx.dexadapt.resolve.AnnotationText
import io.github.wzgrx.dexadapt.resolve.ClassView
import io.github.wzgrx.dexadapt.resolve.FieldView
import io.github.wzgrx.dexadapt.resolve.MethodView
import io.github.wzgrx.dexadapt.resolve.SymbolSource
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.BatchFindMethodUsingStrings
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.AnnotationData
import org.luckypray.dexkit.result.AnnotationEncodeValue
import org.luckypray.dexkit.result.MethodData

/**
 * 手机上的符号数据源：用 DexKit（原生、按需读 dex，不把两百多 MB 的 dex 读进 Java 堆）查宿主 APK。
 * 只负责给候选：按类名取类、按字符串找方法、按包列类；没有好入口的类/方法查询把能表达的条件（包、父类、字段、
 * 方法形状、调用）下推给 DexKit 过滤。是否真正匹配由 [io.github.wzgrx.dexadapt.resolve.SymbolResolver]
 * 逐条核对，和电脑上生成内置符号表是同一套逻辑。只在一个后台线程里用。
 *
 * 放在纯 JVM 模块里（不依赖 Android）：模块 App 用它在手机上适配，电脑上的一致性校验（DexKitParityTest）用
 * 同一份代码加桌面版 libdexkit 跑每个内置版本的 APK，保证手机上的本机适配和离线符号表结果一致。
 */
class DexKitSource(
    private val bridge: DexKitBridge,
    override val dexSha256: String,
    /** 批量查询失败时的提示（手机上写 logcat；电脑上的一致性测试不需要）。 */
    private val warn: (String, Throwable) -> Unit = { _, _ -> },
) : SymbolSource {
    private val classes = HashMap<String, ClassView?>()
    private val byString = HashMap<String, List<MethodView>>()

    override fun classByName(name: String): ClassView? = classes.getOrPut(name) {
        runCatching { bridge.getClassData(descriptor(name)) }.getOrNull()?.let(::KClass)
    }

    override fun methodsUsingString(s: String): List<MethodView> = byString.getOrPut(s) {
        bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings(s))).map(::KMethod)
    }

    /**
     * 全部查询里的字符串一次批量查（batchFindMethodUsingStrings，DexKit 只扫一遍 dex），以后 [methodsUsingString]
     * 直接用结果；每组一个字符串、完全相等，和单独查询同一语义。批量失败（老设备上内存不够等）时不预取，照旧逐个查。
     */
    override fun prefetchStrings(strings: Collection<String>) {
        val todo = strings.filter { it !in byString }
        if (todo.isEmpty()) return
        runCatching {
            todo.chunked(BATCH).forEach { chunk ->
                val found = bridge.batchFindMethodUsingStrings(
                    BatchFindMethodUsingStrings.create().groups(chunk.associateWith { listOf(it) }, StringMatchType.Equals, false),
                )
                chunk.forEach { str -> byString[str] = found[str].orEmpty().map(::KMethod) }
            }
        }.onFailure { warn("batch string lookup failed, falling back to single queries", it) }
    }

    override fun classesInPackage(packagePrefix: String): Sequence<ClassView> =
        bridge.findClass(FindClass.create().searchPackages(packagePrefix)).asSequence().map(::view)

    override fun classesInExactPackage(pkg: String): Sequence<ClassView> =
        classesInPackage(pkg).filter { it.name.substringBeforeLast('.') == pkg }

    override fun candidateClasses(q: ClassQuery, typeName: (TypeRef) -> String): Sequence<ClassView> {
        val m = ClassMatcher.create()
        q.superclass?.let { m.superClass(typeName(it), StringMatchType.Equals, false) }
        q.interfaces.forEach { m.addInterface(typeName(it), StringMatchType.Equals, false) }
        q.fieldNames.forEach { m.addFieldForName(it, StringMatchType.Equals, false) }
        q.fieldTypes.forEach { m.addFieldForType(typeName(it), StringMatchType.Equals, false) }
        q.methods.forEach { shape ->
            val mm = MethodMatcher.create()
            shape.name?.let { mm.name(it, StringMatchType.Equals, false) }
            shape.returns?.let { mm.returnType(typeName(it), StringMatchType.Equals, false) }
            shape.params?.let { p -> if (p.none { it == TypeRef.Any }) mm.paramTypes(p.map(typeName)) else mm.paramCount(p.size) }
            if (shape.strings.isNotEmpty()) mm.usingEqStrings(shape.strings)
            m.addMethod(mm)
        }
        val find = FindClass.create().matcher(m)
        q.packagePrefix?.let { find.searchPackages(it) }
        return bridge.findClass(find).asSequence().map(::view)
    }

    override fun candidateMethodsInPackage(packagePrefix: String, q: MethodQuery, typeName: (TypeRef) -> String, calls: List<String>, fields: List<String>): Sequence<MethodView> {
        val mm = MethodMatcher.create()
        val shape = q.shape
        shape.name?.let { mm.name(it, StringMatchType.Equals, false) }
        shape.returns?.let { mm.returnType(typeName(it), StringMatchType.Equals, false) }
        shape.params?.let { p -> if (p.none { it == TypeRef.Any }) mm.paramTypes(p.map(typeName)) else mm.paramCount(p.size) }
        if (shape.strings.isNotEmpty()) mm.usingEqStrings(shape.strings)
        calls.forEach { sig -> methodDescriptor(sig)?.let(mm::addInvoke) }
        return bridge.findMethod(FindMethod.create().searchPackages(packagePrefix).matcher(mm)).asSequence().map(::KMethod)
    }

    /** 批量候选不进缓存（父类是 Enum 这类查询会给出几千个类，连同懒加载的方法一起留着会撑爆 Java 堆）。 */
    private fun view(data: ClassData): ClassView = classes[data.name] ?: KClass(data)

    private class KClass(private val data: ClassData) : ClassView {
        override val name: String = data.name
        override val superName: String? by lazy { data.superClass?.name }
        override val interfaces: List<String> by lazy { data.interfaces.map { it.name } }
        override val flags: Int = data.accessFlags
        override val fields: List<FieldView> by lazy { data.fields.map(::KField) }
        override val methods: List<MethodView> by lazy { data.methods.map(::KMethod) }
        override val sourceFile: String? by lazy { data.sourceFile.takeIf { it.isNotEmpty() } }
        override val annotations: List<String> by lazy { data.annotations.map(::annotationText) }
    }

    private class KField(data: FieldData) : FieldView {
        override val owner: String = data.className
        override val name: String = data.name
        override val type: String = data.typeName
        override val flags: Int = data.accessFlags
    }

    private class KMethod(private val data: MethodData) : MethodView {
        override val owner: String = data.className
        override val name: String = data.name
        override val params: List<String> = data.paramTypeNames
        override val returns: String = data.returnTypeName
        override val flags: Int = data.accessFlags
        override val strings: Set<String> by lazy { data.usingStrings.toHashSet() }
        override val calls: Set<String> by lazy { data.invokes.mapTo(HashSet()) { MethodView.signature(it.className, it.name, it.paramTypeNames, it.returnTypeName) } }
        override val fieldRefs: Set<String> by lazy { data.usingFields.mapTo(HashSet()) { "${it.field.className}#${it.field.name}" } }
        override val signature: String = MethodView.signature(owner, name, params, returns)
        // DexKit 把 switch / 数组的数据块报成 nop；nop 不算指令（和 dexlib 那边一致）。
        override val opcodes: List<String> by lazy { data.opNames.filter { it != "nop" } }
        // usingNumbers 在 DexKit 里还标着实验性。
        @OptIn(org.luckypray.dexkit.annotations.DexKitExperimentalApi::class)
        override val numbers: Set<Long> by lazy {
            data.usingNumbers.mapTo(HashSet()) { n -> if (n.bitWidth <= 32) n.rawBits.toInt().toLong() else n.rawBits }
        }
        override val annotations: List<String> by lazy { data.annotations.map(::annotationText) }
    }

    companion object {
        /** 注解写成 [AnnotationText]：只保留字符串值（含字符串数组），和 dexlib 那边一致。 */
        private fun annotationText(a: AnnotationData): String =
            AnnotationText.of(a.typeName, a.elements.mapNotNull { e -> encoded(e.value.value)?.let { e.name to it } })

        private fun encoded(v: Any?): String? = when (v) {
            is String -> v
            is AnnotationEncodeValue -> encoded(v.value)
            is Collection<*> -> v.mapNotNull(::encoded).takeIf { it.isNotEmpty() }?.joinToString(",")
            else -> v?.javaClass?.methods?.firstOrNull { it.name == "getValues" && it.parameterCount == 0 }
                ?.let { m -> (m.invoke(v) as? Collection<*>)?.mapNotNull(::encoded)?.takeIf { it.isNotEmpty() }?.joinToString(",") }
        }

        /** 一次批量查多少个字符串（全部几百个一起查时结果集太大，分几批）。 */
        private const val BATCH = 200

        /** JVM 写法的类型名 → dex 描述符（`int[]` → `[I`，`a.b.C` → `La/b/C;`）。 */
        fun descriptor(type: String): String {
            if (type.endsWith("[]")) return "[" + descriptor(type.removeSuffix("[]"))
            return when (type) {
                "void" -> "V"; "boolean" -> "Z"; "byte" -> "B"; "short" -> "S"; "char" -> "C"
                "int" -> "I"; "long" -> "J"; "float" -> "F"; "double" -> "D"
                else -> "L" + type.replace('.', '/') + ";"
            }
        }

        /** [MethodView.signature]（`owner#name(p1,p2)ret`）→ DexKit 的方法描述符（`Lowner;->name(P1P2)R`）。 */
        fun methodDescriptor(signature: String): String? {
            val owner = signature.substringBefore('#')
            val rest = signature.substringAfter('#', "")
            val name = rest.substringBefore('(')
            val params = rest.substringAfter('(', "").substringBefore(')')
            val ret = rest.substringAfterLast(')', "")
            if (owner.isEmpty() || name.isEmpty() || ret.isEmpty()) return null
            val p = if (params.isEmpty()) "" else params.split(',').joinToString("") { descriptor(it) }
            return "${descriptor(owner)}->$name($p)${descriptor(ret)}"
        }
    }
}
