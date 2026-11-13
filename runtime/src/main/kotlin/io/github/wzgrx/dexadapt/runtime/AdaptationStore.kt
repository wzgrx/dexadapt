package io.github.wzgrx.dexadapt.runtime

import io.github.wzgrx.dexadapt.Hex
import io.github.wzgrx.dexadapt.SymbolTable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

interface AdaptationStore {
    fun bundled(identity: HostCodeIdentity): SymbolTable?
    fun local(identity: HostCodeIdentity, catalog: String): SymbolTable?
    fun save(identity: HostCodeIdentity, table: SymbolTable)
}

/** 用模块私有目录。只保存完整表；容量与清理策略由使用方管理。 */
class DirectoryAdaptationStore(
    private val dir: File,
    private val bundledTable: (HostCodeIdentity) -> SymbolTable? = { null },
) : AdaptationStore {
    override fun bundled(identity: HostCodeIdentity): SymbolTable? = bundledTable(identity)

    override fun local(identity: HostCodeIdentity, catalog: String): SymbolTable? = runCatching {
        val path = path(identity, catalog)
        if (path.length() !in 1..MAX_TABLE_BYTES) null else SymbolTable.fromJson(path.readText())
    }.getOrNull()?.takeIf { !it.partial && it.catalog == catalog && identity.matches(it) }

    override fun save(identity: HostCodeIdentity, table: SymbolTable) {
        require(!table.partial && table.catalog.isNotBlank() && identity.matches(table))
        val bytes = table.toJson().toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() <= MAX_TABLE_BYTES)
        Files.createDirectories(dir.toPath())
        val temp = Files.createTempFile(dir.toPath(), "adapt-", ".tmp")
        try {
            temp.toFile().outputStream().use { it.write(bytes); it.fd.sync() }
            Files.move(temp, path(identity, table.catalog).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun path(identity: HostCodeIdentity, catalog: String): File {
        val raw = listOf(identity.packageName, identity.versionCode.toString(), identity.dexSha256, catalog).joinToString("\n")
        val id = Hex.of(MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)))
        return File(dir, "$id.json")
    }

    private companion object { const val MAX_TABLE_BYTES = 8L * 1024 * 1024 }
}
