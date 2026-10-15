package io.github.wzgrx.dexadapt.resolve

import io.github.wzgrx.dexadapt.ClassQuery
import io.github.wzgrx.dexadapt.MethodQuery
import io.github.wzgrx.dexadapt.TypeRef
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * [SymbolResolver] 看到的一个宿主 APK。有两种实现：电脑上的 dexlib2 全量索引（dexadapt-dexlib 的 DexIndex，
 * 生成内置符号表），手机上的 DexKit 按需查询（dexadapt-dexkit 的 DexKitSource，给没适配的新版本现场生成符号表）。
 * 两边用同一个 [SymbolResolver]，匹配语义一致；数据源只负责“给出候选”，是否匹配由解析器逐条核对。
 * 类型名一律是 JVM 写法（`a.b.C`、`int[]`），构造方法叫 `<init>`。
 */
interface SymbolSource {
    /** 所有 classes*.dex 按文件名顺序拼起来的 SHA-256（符号表记下它，用来认出是不是同一份代码）。 */
    val dexSha256: String

    fun classByName(name: String): ClassView?

    /** 方法体里加载了字符串 [s]（完全相等）的方法。 */
    fun methodsUsingString(s: String): List<MethodView>

    /** [packagePrefix] 包及其子包里的类。 */
    fun classesInPackage(packagePrefix: String): Sequence<ClassView>

    /** 正好在 [pkg] 包里（不含子包）的类。 */
    fun classesInExactPackage(pkg: String): Sequence<ClassView>

    /**
     * 类查询既没有类名、字符串、同包类可以起手时的候选。默认是全部类（电脑上的全量索引）；
     * DexKit 把能下推的条件（包、父类、字段、方法形状）交给原生过滤，避免在手机上枚举二十多万个类。
     * 返回的只是候选，解析器还会逐条核对 [q] 的全部条件。
     */
    fun candidateClasses(q: ClassQuery, typeName: (TypeRef) -> String): Sequence<ClassView>

    /** 方法查询只给了包名时的候选（同上，DexKit 会下推名字、参数、返回值、调用和字段条件）。 */
    fun candidateMethodsInPackage(packagePrefix: String, q: MethodQuery, typeName: (TypeRef) -> String, calls: List<String>, fields: List<String>): Sequence<MethodView> =
        classesInPackage(packagePrefix).flatMap { it.methods.asSequence() }

    /**
     * 解析开始前告诉数据源会用到哪些字符串（全部查询里的），让它一次批量查好再逐条回答 [methodsUsingString]。
     * 电脑上的全量索引本来就有字符串表，什么都不做；DexKit 用一次 batchFindMethodUsingStrings 代替几百次单独查询。
     */
    fun prefetchStrings(strings: Collection<String>) {}
}

/**
 * 几个 APK 合成一个数据源（宿主装成 base.apk + 带代码的拆分包时）：按顺序找类，先找到的为准（和类加载器一样，
 * base.apk 在最前面）；按字符串、按包找的结果合并。摘要用第一个（base.apk）的。
 */
class MultiSource(private val sources: List<SymbolSource>) : SymbolSource {
    init {
        require(sources.isNotEmpty()) { "no sources" }
    }

    override val dexSha256: String get() = sources.first().dexSha256

    override fun classByName(name: String): ClassView? = sources.firstNotNullOfOrNull { it.classByName(name) }

    override fun methodsUsingString(s: String): List<MethodView> =
        sources.flatMap { src -> src.methodsUsingString(s).filter { definedIn(src, it.owner) } }

    override fun classesInPackage(packagePrefix: String): Sequence<ClassView> = merged { it.classesInPackage(packagePrefix) }

    override fun classesInExactPackage(pkg: String): Sequence<ClassView> = merged { it.classesInExactPackage(pkg) }

    override fun candidateClasses(q: ClassQuery, typeName: (TypeRef) -> String): Sequence<ClassView> = merged { it.candidateClasses(q, typeName) }

    override fun candidateMethodsInPackage(packagePrefix: String, q: MethodQuery, typeName: (TypeRef) -> String, calls: List<String>, fields: List<String>): Sequence<MethodView> =
        sources.asSequence().flatMap { src -> src.candidateMethodsInPackage(packagePrefix, q, typeName, calls, fields).filter { definedIn(src, it.owner) } }

    override fun prefetchStrings(strings: Collection<String>) = sources.forEach { it.prefetchStrings(strings) }

    /** 同名类只认最先定义它的来源里的那个（后面拆分包里的同名类不会被加载）。 */
    private fun merged(each: (SymbolSource) -> Sequence<ClassView>): Sequence<ClassView> =
        sources.asSequence().flatMap { src -> each(src).filter { definedIn(src, it.name) } }

    private fun definedIn(src: SymbolSource, className: String): Boolean =
        sources.size == 1 || sources.firstOrNull { it.classByName(className) != null }.let { it == null || it === src }
}

interface ClassView {
    val name: String
    val superName: String?
    val interfaces: List<String>
    val flags: Int
    val fields: List<FieldView>
    val methods: List<MethodView>
    /** `SourceFile` 属性（没有为 null）。 */
    val sourceFile: String? get() = null
    /** 类上的注解，每个写成 [AnnotationText] 的样子。 */
    val annotations: List<String> get() = emptyList()
    val isInterface: Boolean get() = flags and AccessFlag.INTERFACE != 0
    val isAbstract: Boolean get() = flags and AccessFlag.ABSTRACT != 0
}

interface FieldView {
    val owner: String
    val name: String
    val type: String
    val flags: Int
    val isStatic: Boolean get() = flags and AccessFlag.STATIC != 0
}

interface MethodView {
    val owner: String
    val name: String
    val params: List<String>
    val returns: String
    val flags: Int
    /** 方法体加载的字符串常量。 */
    val strings: Set<String>
    /** 调用到的方法，写成 [signature]。 */
    val calls: Set<String>
    /** 读写到的字段，写成 `owner#name`。 */
    val fieldRefs: Set<String>
    /** 方法体的操作码，按出现顺序，写成 smali 名字（`const-string`、`invoke-virtual`……）。 */
    val opcodes: List<String> get() = emptyList()
    /** 方法体里的数字常量（整数按有符号值，浮点数按位）。 */
    val numbers: Set<Long> get() = emptySet()
    /** 方法上的注解，每个写成 [AnnotationText] 的样子。 */
    val annotations: List<String> get() = emptyList()
    val isStatic: Boolean get() = flags and AccessFlag.STATIC != 0
    val signature: String get() = signature(owner, name, params, returns)

    companion object {
        fun signature(owner: String, name: String, params: List<String>, returns: String) =
            "$owner#$name(${params.joinToString(",")})$returns"
    }
}

/** 注解在两个数据源里的统一写法：`类型名 名字=值 名字=值`，字符串值原样、数组用逗号连起来，用来按文字匹配。 */
object AnnotationText {
    fun of(type: String, elements: List<Pair<String, String>>): String =
        (listOf(type) + elements.map { (k, v) -> "$k=$v" }).joinToString(" ")
}

/** dex 访问标志位（和 dexlib2 AccessFlags、DexKit getAccessFlags 同值）。 */
object AccessFlag {
    const val STATIC = 0x8
    /** 编译器生成的桥接方法（如泛型擦除后的 `invoke(Object)`）。 */
    const val BRIDGE = 0x40
    const val INTERFACE = 0x200
    const val ABSTRACT = 0x400
    /** 编译器合成的成员（`$default`、`access$`、lambda 体等）。 */
    const val SYNTHETIC = 0x1000
}

object DexDigest {
    /** [apk] 里 classes*.dex 按文件名（先长度后字典序）拼起来的 SHA-256，和内置符号表的 dexSha256 同一算法。 */
    fun of(apk: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .sortedWith(compareBy({ it.name.length }, { it.name }))
                .forEach { e ->
                    zip.getInputStream(e).use { input ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            digest.update(buf, 0, n)
                        }
                    }
                }
        }
        return io.github.wzgrx.dexadapt.Hex.of(digest.digest())
    }
}
