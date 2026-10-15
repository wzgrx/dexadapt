package io.github.wzgrx.dexadapt.hooks.libxposed

import io.github.libxposed.api.XposedInterface
import io.github.wzgrx.dexadapt.MethodSymbol
import io.github.wzgrx.dexadapt.runtime.SymbolHooks

/**
 * libxposed（API 100+）：`xposed.hook(hooks, symbol) { chain -> ... }`，和 `xposed.hook(method).intercept { chain -> }` 一样写。
 * 成员找不到时不挂、返回 null；观察中只调用原方法并记录，[body] 不跑。[configure] 设优先级、异常模式等。
 */
fun XposedInterface.hook(
    hooks: SymbolHooks,
    symbol: MethodSymbol,
    configure: (XposedInterface.HookBuilder) -> Unit = {},
    body: (XposedInterface.Chain) -> Any?,
): XposedInterface.HookHandle? = hooks.hook(symbol) { member, gate ->
    val builder = hook(member)
    configure(builder)
    builder.intercept { chain ->
        if (gate.open) {
            body(chain)
        } else {
            chain.proceed().also { gate.record(chain.thisObject, chain.args, it, SymbolHooks.onMainThread()) }
        }
    }
}
