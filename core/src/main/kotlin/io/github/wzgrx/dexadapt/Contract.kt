package io.github.wzgrx.dexadapt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 方法符号的“契约”：找到的位置是不是原来那个意思。查询只保证唯一命中；参数顺序变了、方法被拆开、返回值换了含义，
 * 查询照样命中，Hook 却会做错事。契约写下运行时应该看到的样子（某个参数非空、是某个类型、字符串像什么、数字在什么范围、
 * 在不在主线程被调用），手机上本机适配出的新表先**观察**：只记录、不改行为，被调用够 [minCalls] 次且都满足才放开，
 * 不满足就当“未适配”。电脑上生成、验证过的内置表不需要观察。
 *
 * 契约不参与解析，不算进目录指纹（改契约不会让各手机重扫）。
 */
@Serializable
data class Contract(
    /** 参数下标（从 0 开始，不含 this）→ 规则。 */
    val args: Map<Int, ValueRule> = emptyMap(),
    /** this（实例方法的接收者）。 */
    val receiver: ValueRule? = null,
    /** 返回值（方法正常返回时检查）。 */
    val returns: ValueRule? = null,
    /** 是否必须在主线程被调用；null 不检查。 */
    val mainThread: Boolean? = null,
    /** 满足多少次调用才算通过。 */
    val minCalls: Int = 3,
) {
    init {
        require(minCalls >= 1) { "minCalls 至少 1" }
        require(args.keys.all { it >= 0 }) { "参数下标不能是负数" }
    }

    /** 和解析出的位置对不上的地方（参数下标越界、void 方法却检查返回值、静态方法检查 this、基本类型用了对象规则）；电脑上验证内置表时用。 */
    fun problems(target: Resolved.Method, isStatic: Boolean? = null): List<String> = buildList {
        args.keys.filter { it >= target.params.size }.forEach { add("参数 $it 不存在（方法只有 ${target.params.size} 个参数）") }
        if (returns != null && target.returns == "void") add("方法没有返回值")
        if (receiver != null && isStatic == true) add("静态方法没有 this")
        for ((i, rule) in args) {
            val type = target.params.getOrNull(i) ?: continue
            if (rule.needsReference && type in PRIMITIVES) add("参数 $i 是 $type，不能用 ${rule.describe()}")
        }
        if (returns != null && returns.needsReference && target.returns in PRIMITIVES) add("返回值是 ${target.returns}，不能用 ${returns.describe()}")
    }
}

private val PRIMITIVES = setOf("boolean", "byte", "short", "char", "int", "long", "float", "double")

/** 运行时值的规则。 */
@Serializable
sealed interface ValueRule {
    /** 规则需要对象（不能用在基本类型上）。 */
    val needsReference: Boolean get() = false

    fun describe(): String

    @Serializable
    @SerialName("notNull")
    data object NotNull : ValueRule {
        override val needsReference get() = true
        override fun describe() = "非空"
    }

    /** 是 [of] 或它的子类（JVM 类名，或 `$id` 指一个类符号）。null 不算满足。 */
    @Serializable
    @SerialName("instanceOf")
    data class InstanceOf(val of: String) : ValueRule {
        override val needsReference get() = true
        override fun describe() = "是 $of"
    }

    /** `toString()` 整个匹配正则 [regex]。null 不算满足。 */
    @Serializable
    @SerialName("matches")
    data class Matches(val regex: String) : ValueRule {
        override fun describe() = "匹配 /$regex/"
    }

    /** 数字（含 char、boolean 的 0/1）在 [min]..[max]（含两端）。 */
    @Serializable
    @SerialName("range")
    data class Range(val min: Double, val max: Double) : ValueRule {
        init {
            require(min <= max) { "range 的 min 大于 max" }
        }

        override fun describe() = "在 $min..$max"
    }
}
