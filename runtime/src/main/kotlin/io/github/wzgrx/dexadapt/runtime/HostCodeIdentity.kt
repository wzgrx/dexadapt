package io.github.wzgrx.dexadapt.runtime

import io.github.wzgrx.dexadapt.SymbolTable
import io.github.wzgrx.dexadapt.resolve.DexDigest
import java.io.File

/** [dexSha256] 来自 base 和所有代码 split，按实际类加载顺序计算。 */
data class HostCodeIdentity(val packageName: String, val versionCode: Long, val dexSha256: String) {
    init {
        require(packageName.isNotBlank() && versionCode > 0 && SHA256.matches(dexSha256)) { "宿主完整 DEX 身份无效" }
    }

    fun matches(table: SymbolTable): Boolean =
        table.packageName == packageName && table.versionCode == versionCode && table.dexSha256 == dexSha256

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
        fun of(packageName: String, versionCode: Long, apksInLoadOrder: List<File>): HostCodeIdentity =
            HostCodeIdentity(packageName, versionCode, DexDigest.of(apksInLoadOrder))
    }
}
