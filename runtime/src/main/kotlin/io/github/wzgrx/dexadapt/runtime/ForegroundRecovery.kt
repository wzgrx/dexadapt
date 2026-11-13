package io.github.wzgrx.dexadapt.runtime

import io.github.wzgrx.dexadapt.SymbolTable

/** 前台询问只能使用当前完整身份已验证的表；瞬时身份不可用不是缺表。 */
object ForegroundRecovery {
    sealed interface Result {
        data class Available(val table: SymbolTable) : Result
        data object NeedsScan : Result
        data object Unavailable : Result
    }

    fun select(expectedVersion: Long, identity: HostCodeIdentity?, catalog: String,
               local: () -> SymbolTable?, bundled: () -> SymbolTable?): Result {
        if (identity == null || identity.versionCode != expectedVersion) return Result.Unavailable
        val current = local()
        val choice = AdaptPolicy.chooseVerified(identity, current, null, catalog).table
            ?: AdaptPolicy.chooseVerified(identity, null, bundled(), catalog).table
        return choice?.let { Result.Available(it.copy(catalog = catalog, recoveryToken = "")) } ?: Result.NeedsScan
    }
}
