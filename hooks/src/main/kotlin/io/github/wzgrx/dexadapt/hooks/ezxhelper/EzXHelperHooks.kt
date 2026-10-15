package io.github.wzgrx.dexadapt.hooks.ezxhelper

import com.github.kyuubiran.ezxhelper.HookFactory
import com.github.kyuubiran.ezxhelper.HookFactory.`-Static`.createHook
import com.github.kyuubiran.ezxhelper.interfaces.IMethodHookCallback
import de.robv.android.xposed.XC_MethodHook
import io.github.wzgrx.dexadapt.MethodSymbol
import io.github.wzgrx.dexadapt.runtime.SymbolHooks
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * EzXHelper：`hooks.createHook(symbol) { before { param -> }; after { param -> } }`，和 `method.createHook { }` 一样写；
 * 方法和构造方法都行。成员找不到时不挂、返回 null；观察中回调不跑，只记录原方法的调用。
 */
fun SymbolHooks.createHook(symbol: MethodSymbol, priority: Int = XC_MethodHook.PRIORITY_DEFAULT, block: EzHookScope.() -> Unit): XC_MethodHook.Unhook? =
    hook(symbol) { member, gate ->
        val scope = EzHookScope().apply(block)
        val install: HookFactory.() -> Unit = {
            before(
                IMethodHookCallback { p ->
                    val open = gate.open
                    p.setObjectExtra(OPEN, open)
                    if (open) scope.before?.invoke(p)
                },
            )
            after(
                IMethodHookCallback { p ->
                    if (p.getObjectExtra(OPEN) == true) scope.after?.invoke(p)
                    else if (!p.hasThrowable()) gate.record(p.thisObject, p.args?.toList().orEmpty(), p.result, SymbolHooks.onMainThread())
                },
            )
        }
        when (member) {
            is Method -> member.createHook(priority, install)
            is Constructor<*> -> member.createHook(priority, install)
            else -> null
        }
    }

/** [createHook] 里的写法：before、after 各一个。 */
class EzHookScope {
    internal var before: ((XC_MethodHook.MethodHookParam) -> Unit)? = null
    internal var after: ((XC_MethodHook.MethodHookParam) -> Unit)? = null
    fun before(block: (XC_MethodHook.MethodHookParam) -> Unit) { before = block }
    fun after(block: (XC_MethodHook.MethodHookParam) -> Unit) { after = block }
}

private const val OPEN = "io.github.wzgrx.dexadapt.open"
