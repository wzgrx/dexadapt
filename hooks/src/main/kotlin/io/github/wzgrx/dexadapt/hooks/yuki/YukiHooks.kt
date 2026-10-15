package io.github.wzgrx.dexadapt.hooks.yuki

import com.highcapable.yukihookapi.hook.core.YukiMemberHookCreator
import com.highcapable.yukihookapi.hook.core.api.priority.YukiHookPriority
import com.highcapable.yukihookapi.hook.param.HookParam
import com.highcapable.yukihookapi.hook.param.PackageParam
import io.github.wzgrx.dexadapt.MethodSymbol
import io.github.wzgrx.dexadapt.runtime.SymbolHooks

/**
 * YukiHookAPI（1.3）：在 `encApp { }` / `loadApp { }` 里写 `hookSymbol(hooks, symbol) { before { }; after { } }`，
 * 回调里的写法（`args`、`instance`、`result`）和 Yuki 的一样。成员找不到时不挂、返回 null；观察中回调不跑，只记录原方法的调用。
 */
fun PackageParam.hookSymbol(
    hooks: SymbolHooks,
    symbol: MethodSymbol,
    priority: YukiHookPriority = YukiHookPriority.DEFAULT,
    block: YukiHookScope.() -> Unit,
): YukiMemberHookCreator.MemberHookCreator.Result? = hooks.hook(symbol) { member, gate ->
    val scope = YukiHookScope().apply(block)
    // 同一次调用 before 时决定闸门，after 用同一个结论（栈：被 Hook 的方法可能递归调用自己）
    val opened = ThreadLocal.withInitial { ArrayDeque<Boolean>() }
    member.hook(priority) {
        before {
            val open = gate.open
            opened.get()!!.addLast(open)
            if (open) scope.before?.invoke(this)
        }
        after {
            val open = opened.get()!!.removeLastOrNull() ?: gate.open
            if (open) scope.after?.invoke(this)
            else if (throwable == null) gate.record(instanceOrNull, args.toList(), result, SymbolHooks.onMainThread())
        }
    }
}

/** [hookSymbol] 里的写法：before、after 各一个，接收者是 Yuki 的 [HookParam]。 */
class YukiHookScope {
    internal var before: (HookParam.() -> Unit)? = null
    internal var after: (HookParam.() -> Unit)? = null
    fun before(block: HookParam.() -> Unit) { before = block }
    fun after(block: HookParam.() -> Unit) { after = block }
}
