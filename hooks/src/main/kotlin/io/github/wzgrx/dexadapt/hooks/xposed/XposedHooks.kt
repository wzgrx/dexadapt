package io.github.wzgrx.dexadapt.hooks.xposed

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.wzgrx.dexadapt.MethodSymbol
import io.github.wzgrx.dexadapt.runtime.SymbolHooks

/** 一次调用在 before 时决定闸门开没开，after 用同一个结论（中途观察通过也不会只跑半个 Hook）。 */
internal const val OPEN_EXTRA = "io.github.wzgrx.dexadapt.open"

/**
 * 传统 Xposed API（XposedBridge）：`hooks.hookMethod(symbol, before = { param -> }, after = { param -> })`。
 * 成员找不到时不挂、返回 null；观察中 [before]、[after] 都不跑，只记录原方法的调用。
 */
fun SymbolHooks.hookMethod(
    symbol: MethodSymbol,
    priority: Int = XC_MethodHook.PRIORITY_DEFAULT,
    before: (XC_MethodHook.MethodHookParam) -> Unit = {},
    after: (XC_MethodHook.MethodHookParam) -> Unit = {},
): XC_MethodHook.Unhook? = hook(symbol) { member, gate ->
    XposedBridge.hookMethod(member, object : XC_MethodHook(priority) {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val open = gate.open
            param.setObjectExtra(OPEN_EXTRA, open)
            if (open) before(param)
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            if (param.getObjectExtra(OPEN_EXTRA) == true) {
                after(param)
            } else if (!param.hasThrowable()) {
                gate.record(param.thisObject, param.args?.toList().orEmpty(), param.result, SymbolHooks.onMainThread())
            }
        }
    })
}
