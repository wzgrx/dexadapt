package io.github.wzgrx.dexadapt.runtime

import io.github.wzgrx.dexadapt.ClassSymbol
import io.github.wzgrx.dexadapt.FieldSymbol
import io.github.wzgrx.dexadapt.MethodSymbol
import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.Symbol
import io.github.wzgrx.dexadapt.SymbolTable
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

/**
 * 某个符号在当前宿主上用不了（没适配、没找到、签名对不上）。[absent] 为 true 时是这个版本本来就没有（[SymbolTable.absent]），
 * 不是适配失败：界面上应显示“这个版本没有”，不该报错。
 */
class SymbolUnavailable(val symbolId: String, reason: String, val absent: Boolean = false) : Exception("$symbolId: $reason")

/**
 * 宿主进程里把符号表变成反射对象：按表里的位置用宿主的 ClassLoader 加载，核对返回值 / 字段类型，结果缓存。
 * 不扫描 dex；表里缺的、加载不到的符号只让用到它的地方失败（[SymbolUnavailable]），其余照常。
 *
 * 表和按它查出来的结果放在一起、整体替换：本机适配在宿主运行中完成时 [replace] 成新表（热适配），
 * 正在用旧表查找的线程不会把旧结果写进新缓存。[log] 接模块自己的日志。
 */
class SymbolLinker(
    table: SymbolTable?,
    private val loader: ClassLoader,
    /** 没有表时的说明（给界面看），例如“此版本未适配”。 */
    private val unsupported: String = "此版本未适配",
    private val log: (String) -> Unit = {},
    /**
     * 哪些符号可以用推测的位置（[SymbolTable.guesses]）。默认都不用；用的话必须保证这些点在观察模式下验证过才真正起作用
     * （例如只允许有契约的方法符号，Hook 由 ContractMonitor 把关）。
     */
    private val useGuess: (String) -> Boolean = { false },
) {
    private class State(val table: SymbolTable?, val cache: ConcurrentHashMap<String, Result<Any>> = ConcurrentHashMap(),
                        val ids: ConcurrentHashMap<Any, String> = ConcurrentHashMap()) {
        val absent: Set<String> = table?.absent?.toHashSet().orEmpty()
        /** 反查：查出来的 Class / Executable / Field → 符号 id。 */

    }

    @Volatile
    private var state = State(table)
    private val pinned = ThreadLocal<State?>()
    private val reads = ThreadLocal<MutableSet<String>?>()

    data class ReadSet<T>(val value: T, val ids: Set<String>)

    /** 一次Hook里的多次读取绑定同一份表；进行中的旧调用不会读到新旧混合数据。 */
    fun <T> withSnapshot(block: () -> T): T {
        val previous = pinned.get()
        pinned.set(state)
        return try { block() } finally { if (previous == null) pinned.remove() else pinned.set(previous) }
    }

    /** 安装必须读当前全局表，不能继承触发换表的旧Hook快照。 */
    fun <T> captureReads(block: () -> T): ReadSet<T> {
        val previous = reads.get()
        val previousPin = pinned.get()
        val ids = LinkedHashSet<String>()
        reads.set(ids); pinned.set(state)
        return try { ReadSet(block(), ids.toSet()) } finally {
            previous?.addAll(ids)
            if (previous == null) reads.remove() else reads.set(previous)
            if (previousPin == null) pinned.remove() else pinned.set(previousPin)
        }
    }

    val table: SymbolTable? get() = state.table
    val supported: Boolean get() = table != null

    /** 换成新表（热适配）：连同按旧表查过的结果一起换掉。 */
    @Synchronized fun replace(newTable: SymbolTable?) {
        state = State(newTable)
    }

    /** 只允许同一内容的交付元数据更新；身份、目录和目标变化必须走正常替换。 */
    @Synchronized fun updateDelivery(expected: SymbolTable, delivered: SymbolTable): Boolean {
        val old = state
        if (old.table !== expected || expected.versionCode != delivered.versionCode ||
            expected.packageName != delivered.packageName || expected.dexSha256 != delivered.dexSha256 ||
            expected.partial != delivered.partial || expected.entries != delivered.entries ||
            expected.missing != delivered.missing || expected.absent != delivered.absent || expected.guesses != delivered.guesses ||
            expected.catalog.isNotEmpty() && expected.catalog != delivered.catalog) return false
        state = State(delivered, old.cache, old.ids)
        return true
    }

    /** 只改当前表的交付令牌；解析对象和进行中的读取快照保持有效。 */
    @Synchronized fun updateRecoveryToken(expected: SymbolTable, token: String): Boolean {
        val old = state
        if (old.table !== expected) return false
        state = State(expected.copy(recoveryToken = token), old.cache, old.ids)
        return true
    }

    fun clazz(symbol: ClassSymbol): Class<*> = lookup(symbol) { r: Resolved.Class -> load(r.name) } as Class<*>

    fun method(symbol: MethodSymbol): Executable = lookup(symbol) { r: Resolved.Method ->
        val owner = load(r.owner)
        val params = r.params.map(::load).toTypedArray()
        if (r.isConstructor) {
            owner.getDeclaredConstructor(*params)
        } else {
            owner.getDeclaredMethod(r.name, *params).also { m ->
                check(m.returnType == load(r.returns)) { "return type changed: ${m.returnType.name}" }
            }
        }.apply { isAccessible = true }
    } as Executable

    /**
     * 多签名访问器：先以当前表链接一次，再按实际 JVM 签名验证和调用。
     * 精确验证基本类型的装箱类型，不做数值转换；只解包实际宿主调用抛出的异常。
     */
    fun invokeChecked(symbol: MethodSymbol, receiver: Any?, args: Array<out Any?>): Any? {
        val target = method(symbol)
        val params = target.parameterTypes
        fun requireArgument(ok: Boolean, reason: () -> String) {
            require(ok) {
                val name = if (target is Constructor<*>) "<init>" else target.name
                val descriptor = target.declaringClass.name + "#" + name +
                    params.joinToString(prefix = "(", postfix = ")") { it.typeName }
                "${symbol.id} → $descriptor：${reason()}"
            }
        }
        val staticOrConstructor = target is Constructor<*> || Modifier.isStatic(target.modifiers)
        requireArgument(if (staticOrConstructor) receiver == null else target.declaringClass.isInstance(receiver)) {
            if (staticOrConstructor) "接收者应为 null" else "接收者应为 ${target.declaringClass.name}"
        }
        requireArgument(params.size == args.size) { "需要 ${params.size} 个参数，收到 ${args.size} 个" }
        for (i in params.indices) {
            val type = params[i]
            val arg = args[i]
            val compatible = if (arg == null) !type.isPrimitive else (BOXED[type] ?: type).isInstance(arg)
            requireArgument(compatible) { "参数 ${i + 1} 需要 ${type.typeName}，收到 ${arg?.javaClass?.name ?: "null"}" }
        }
        return try {
            when (target) {
                is Constructor<*> -> target.newInstance(*args)
                is Method -> target.invoke(receiver, *args)
            }
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }

    /** [symbol] 是这个版本本来就没有的（[SymbolTable.absent]），不是没适配上。 */
    fun absent(symbol: Symbol): Boolean = symbol.id in (pinned.get() ?: state).absent

    fun methodOrNull(symbol: MethodSymbol): Executable? = runCatching { method(symbol) }.getOrNull()

    /** [member]（[clazz]、[method]、[field] 查出来的对象）是哪个符号；不是按表查出来的返回 null。 */
    fun idOf(member: Any): String? = (pinned.get() ?: state).ids[member]

    fun field(symbol: FieldSymbol): Field = lookup(symbol) { r: Resolved.Field ->
        load(r.owner).getDeclaredField(r.name).apply { isAccessible = true }.also { f ->
            check(f.type == load(r.type)) { "field type changed: ${f.type.name}" }
        }
    } as Field

    fun available(symbol: Symbol): Boolean = runCatching {
        when (symbol) {
            is ClassSymbol -> clazz(symbol)
            is MethodSymbol -> method(symbol)
            is FieldSymbol -> field(symbol)
        }
    }.isSuccess

    /** [symbol] 用不了的原因；能用时为 null。 */
    fun problem(symbol: Symbol): String? {
        val s = pinned.get() ?: state
        return if (available(symbol)) null else s.cache[symbol.id]?.exceptionOrNull()?.message
            ?: s.table?.missing?.get(symbol.id) ?: unsupported
    }

    @Suppress("UNCHECKED_CAST")
    private fun <R : Resolved> lookup(symbol: Symbol, build: (R) -> Any): Any {
        reads.get()?.add(symbol.id)
        val s = pinned.get() ?: state // 整个查找只用这一份表和缓存
        return s.cache.getOrPut(symbol.id) {
            runCatching {
                val t = s.table ?: throw SymbolUnavailable(symbol.id, unsupported)
                if (symbol.id in s.absent) throw SymbolUnavailable(symbol.id, "这个版本没有", absent = true)
                val entry = t.entries[symbol.id]
                    ?: t.guesses[symbol.id]?.takeIf { useGuess(symbol.id) }?.at?.also { log("symbol ${symbol.id} uses a guessed location: ${t.guesses[symbol.id]?.how}") }
                    ?: throw SymbolUnavailable(symbol.id, t.missing[symbol.id]?.let { "未找到（$it）" } ?: "符号表中没有")
                build(entry as R).also { s.ids[it] = symbol.id }
            }.onFailure { log("symbol ${symbol.id} unavailable: ${it.message}") }
        }.getOrThrow()
    }

    private fun load(name: String): Class<*> {
        PRIMITIVES[name]?.let { return it }
        if (name.endsWith("[]")) return java.lang.reflect.Array.newInstance(load(name.removeSuffix("[]")), 0).javaClass
        return Class.forName(name, false, loader)
    }

    private companion object {
        val BOXED = mapOf(
            Boolean::class.javaPrimitiveType to Boolean::class.javaObjectType,
            Byte::class.javaPrimitiveType to Byte::class.javaObjectType,
            Short::class.javaPrimitiveType to Short::class.javaObjectType,
            Char::class.javaPrimitiveType to Char::class.javaObjectType,
            Int::class.javaPrimitiveType to Int::class.javaObjectType,
            Long::class.javaPrimitiveType to Long::class.javaObjectType,
            Float::class.javaPrimitiveType to Float::class.javaObjectType,
            Double::class.javaPrimitiveType to Double::class.javaObjectType,
        )

        val PRIMITIVES = mapOf(
            "void" to Void.TYPE, "boolean" to java.lang.Boolean.TYPE, "byte" to java.lang.Byte.TYPE,
            "short" to java.lang.Short.TYPE, "char" to Character.TYPE, "int" to Integer.TYPE,
            "long" to java.lang.Long.TYPE, "float" to java.lang.Float.TYPE, "double" to java.lang.Double.TYPE,
        )
    }
}
