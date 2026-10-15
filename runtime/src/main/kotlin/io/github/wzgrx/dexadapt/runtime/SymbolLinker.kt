package io.github.wzgrx.dexadapt.runtime

import io.github.wzgrx.dexadapt.ClassSymbol
import io.github.wzgrx.dexadapt.FieldSymbol
import io.github.wzgrx.dexadapt.MethodSymbol
import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.Symbol
import io.github.wzgrx.dexadapt.SymbolTable
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

/** 某个符号在当前宿主上用不了（没适配、没找到、签名对不上）。 */
class SymbolUnavailable(val symbolId: String, reason: String) : Exception("$symbolId: $reason")

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
) {
    private class State(val table: SymbolTable?) {
        val cache = ConcurrentHashMap<String, Result<Any>>()
    }

    @Volatile
    private var state = State(table)

    val table: SymbolTable? get() = state.table
    val supported: Boolean get() = table != null

    /** 换成新表（热适配）：连同按旧表查过的结果一起换掉。 */
    fun replace(newTable: SymbolTable) {
        state = State(newTable)
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

    fun methodOrNull(symbol: MethodSymbol): Executable? = runCatching { method(symbol) }.getOrNull()

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
        val s = state
        return if (available(symbol)) null else s.cache[symbol.id]?.exceptionOrNull()?.message
            ?: s.table?.missing?.get(symbol.id) ?: unsupported
    }

    @Suppress("UNCHECKED_CAST")
    private fun <R : Resolved> lookup(symbol: Symbol, build: (R) -> Any): Any {
        val s = state // 整个查找只用这一份表和缓存
        return s.cache.getOrPut(symbol.id) {
            runCatching {
                val t = s.table ?: throw SymbolUnavailable(symbol.id, unsupported)
                val entry = t.entries[symbol.id]
                    ?: throw SymbolUnavailable(symbol.id, t.missing[symbol.id]?.let { "未找到（$it）" } ?: "符号表中没有")
                build(entry as R)
            }.onFailure { log("symbol ${symbol.id} unavailable: ${it.message}") }
        }.getOrThrow()
    }

    private fun load(name: String): Class<*> {
        PRIMITIVES[name]?.let { return it }
        if (name.endsWith("[]")) return java.lang.reflect.Array.newInstance(load(name.removeSuffix("[]")), 0).javaClass
        return Class.forName(name, false, loader)
    }

    private companion object {
        val PRIMITIVES = mapOf(
            "void" to Void.TYPE, "boolean" to java.lang.Boolean.TYPE, "byte" to java.lang.Byte.TYPE,
            "short" to java.lang.Short.TYPE, "char" to Character.TYPE, "int" to Integer.TYPE,
            "long" to java.lang.Long.TYPE, "float" to java.lang.Float.TYPE, "double" to java.lang.Double.TYPE,
        )
    }
}
