package io.github.wzgrx.dexadapt

/**
 * A type in a query: either a fixed JVM name (`java.lang.String`, `int`, `java.util.List`)
 * or another class symbol that is resolved first.
 */
sealed interface TypeRef {
    data class Exact(val name: String) : TypeRef
    data class Of(val symbol: ClassSymbol) : TypeRef
    /** Matches any type; for obfuscated parameters next to a stable one. */
    data object Any : TypeRef

    /** The direct superclass of another class symbol (for members declared on an obfuscated base). */
    data class SuperOf(val symbol: ClassSymbol) : TypeRef
}

fun type(name: String): TypeRef = TypeRef.Exact(name)

/** Description of a class by features that survive R8 renaming between builds. */
data class ClassQuery(
    /** Unobfuscated name (activities, public SDK classes). */
    val name: String? = null,
    /** Restrict to a package, e.g. `com.xingin.matrix`; matches sub-packages too. */
    val packagePrefix: String? = null,
    val superclass: TypeRef? = null,
    val interfaces: List<TypeRef> = emptyList(),
    /** Strings that methods of this class reference (all must appear somewhere in the class). */
    val strings: List<String> = emptyList(),
    /** Field types the class declares (each must be present). */
    val fieldTypes: List<TypeRef> = emptyList(),
    /** Field names that survive R8 (Gson/serialization models keep them). */
    val fieldNames: List<String> = emptyList(),
    /** Methods the class must declare. */
    val methods: List<MethodShape> = emptyList(),
    val isInterface: Boolean? = null,
    val isAbstract: Boolean? = null,
    /**
     * 和另一个类在同一个包里（不含子包）。混淆后的包名各版本不同（如 `h08` / `ev7`），没有字符串的小类
     * 可以借同包里一个能唯一定位的类来找。
     */
    val samePackageAs: TypeRef? = null,
    /**
     * 是这个类里某个字段的声明类型（从那个类的字段类型里找）。两个形状相同的混淆接口，只有一个被某个已知类
     * 当字段用时，靠它区分，不用按版本钉死——新版本本机适配时钉子不起作用。
     */
    val fieldOf: TypeRef? = null,
    /**
     * 在这些方法里被 `new` 出来（方法体调用了它的构造方法）。没有字符串的小事件类、回调类，靠“谁创建它”定位，
     * 不写死混淆名（例如派发“开始播放”事件的方法里只 new 了一个事件类）。
     */
    val instantiatedIn: List<MethodSymbol> = emptyList(),
    /** 声明了这些方法（方法能靠调用关系等找到、类本身只有形状时，用“它是那个方法的所属类”定位）。 */
    val declares: List<MethodSymbol> = emptyList(),
    /** dex 里保留的源文件名（`SourceFile` 属性，很多 App 为了崩溃堆栈留着，例如 `FeedbackOverlay.kt`）。 */
    val sourceFile: String? = null,
    /** 类上的注解，每一项是要出现在某个注解里的文字（注解类型名或它的字符串值）。 */
    val annotations: List<String> = emptyList(),
)

/** Shape of a method used inside [ClassQuery.methods] or as the main part of a [MethodQuery]. */
data class MethodShape(
    val name: String? = null,
    val params: List<TypeRef>? = null,
    val returns: TypeRef? = null,
    val strings: List<String> = emptyList(),
    val isStatic: Boolean? = null,
    /** 方法体里用到的数字常量（全部要出现），例如特征明显的 magic number、业务码。 */
    val numbers: List<Long> = emptyList(),
    /** 方法上的注解，每一项是要出现在某个注解里的文字，例如 `retrofit2.http.GET`、`/api/sns/v1/note/feed`。 */
    val annotations: List<String> = emptyList(),
    /**
     * 指令过滤器：这些操作码按顺序出现在方法体里（中间可以夹别的指令），写法同 smali，例如
     * `["const-string", "invoke-virtual", "move-result-object"]`。比整段操作码序列宽松，小改动不会断。
     */
    val opcodes: List<String> = emptyList(),
)

data class MethodQuery(
    val owner: TypeRef? = null,
    /** When [owner] is null, the declaring class is searched in this package. */
    val packagePrefix: String? = null,
    val shape: MethodShape = MethodShape(),
    /** Methods this method calls (each must be invoked somewhere in its body). */
    val calls: List<MethodSymbol> = emptyList(),
    /** Fixed methods this method calls, as `owner#name`, e.g. `android.view.View#setVisibility`. */
    val callsExact: List<String> = emptyList(),
    /** Fields this method reads or writes. */
    val usesFields: List<FieldSymbol> = emptyList(),
    /** When true the method touches no field besides [usesFields] (e.g. a plain getter, not `toString`). */
    val onlyTheseFields: Boolean = false,
    /** 排除这些方法（形状相同的兄弟方法里，其它几个已经能靠别的特征唯一找到时用）。 */
    val excludes: List<MethodSymbol> = emptyList(),
    /**
     * 被这些方法调用（每个调用者的方法体里都有对它的调用）。形状相同的兄弟方法里，只有一个被某个能靠字符串
     * 找到的方法调用时，靠它区分。没有 [owner]、字符串和包名时，候选就是调用者调用到的方法。
     */
    val calledBy: List<MethodSymbol> = emptyList(),
)

data class FieldQuery(
    val owner: TypeRef,
    val name: String? = null,
    val type: TypeRef? = null,
    val isStatic: Boolean? = null,
    /** 被这些方法读或写（只按类型匹配的字段，有多个同类型字段时靠“谁用它”区分）。 */
    val accessedIn: List<MethodSymbol> = emptyList(),
)

/** A per-build override found by hand. The finder still checks that it exists. */
sealed interface Pin {
    data class Class(val name: String) : Pin
    data class Method(val owner: String, val name: String, val params: List<String>) : Pin
    data class Field(val owner: String, val name: String) : Pin
}

/**
 * 一个 Hook 点。[id] 在整个目录里唯一（习惯写成 `组.名字`）。
 *
 * 构建标识（[pins]、[absentIn] 的键）由使用方自己定，例如 `CN_9500803`；只对已知构建生效，
 * 新版本本机适配时不看它们。
 */
sealed class Symbol(val id: String) {
    /** 确认没有这个符号的构建（该版本没有这个功能）。 */
    var absentIn: Set<String> = emptySet()
        internal set
    /** 某个已知构建里手工钉死的位置（解析器仍会核对它符合查询）。 */
    var pins: Map<String, Pin> = emptyMap()
        internal set

    override fun toString() = id
}

class ClassSymbol(id: String, val query: ClassQuery) : Symbol(id)
class MethodSymbol(id: String, val query: MethodQuery) : Symbol(id)
class FieldSymbol(id: String, val query: FieldQuery) : Symbol(id)

fun <S : Symbol> S.pin(build: String, pin: Pin): S = apply { pins = pins + (build to pin) }
fun <S : Symbol> S.absentIn(vararg builds: String): S = apply { absentIn = absentIn + builds }
