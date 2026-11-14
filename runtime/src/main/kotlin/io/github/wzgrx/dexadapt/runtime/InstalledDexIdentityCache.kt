package io.github.wzgrx.dexadapt.runtime

import io.github.wzgrx.dexadapt.resolve.DexDigest
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 仅对同一份系统安装记录及同一批未变动的文件复用摘要；私有持久缓存只保留已经完整读取的摘要。 */
class InstalledDexIdentityCache(
    private val snapshot: (String) -> Installation?,
    private val directory: File? = null,
    private val digest: (List<File>) -> String = DexDigest::of,
) {
    data class Installation(val versionCode: Long, val lastUpdateTime: Long, val files: List<File>)
    private data class FileState(val path: String, val bytes: Long, val modified: FileTime, val key: String?, val changed: FileTime?)
    private data class Snapshot(val installation: Installation, val files: List<FileState>)
    private data class Entry(val generation: Long, val snapshot: Snapshot, val identity: HostCodeIdentity)
    private class Slot {
        val generation = AtomicLong()
        var entry: Entry? = null
    }

    private val slots = ConcurrentHashMap<String, Slot>()

    fun current(packageName: String): HostCodeIdentity? = current(packageName) {}

    /** 每次实际开始完整 DEX 摘要读取仅回调一次；缓存命中不回调。 */
    fun current(packageName: String, onRead: () -> Unit): HostCodeIdentity? {
        val slot = slots.computeIfAbsent(packageName) { Slot() }
        return synchronized(slot) {
            locked(packageName) { persistent -> currentLocked(packageName, slot, onRead, persistent) }
        }
    }

    private fun currentLocked(packageName: String, slot: Slot, onRead: () -> Unit, persistent: Boolean): HostCodeIdentity? {
        return run {
            val generation = slot.generation.get()
            val before = capture(packageName)
            if (before == null || before.installation.files.isEmpty()) {
                slot.entry = null
                return@run null
            }
            val reusable = before.files.all { it.canReuse(System.currentTimeMillis()) }
            val prior = slot.entry
            if (prior?.generation == generation && prior.snapshot == before && reusable) {
                return@run prior.identity.takeIf { slot.generation.get() == generation }
            }
            slot.entry = null
            val stored = if (reusable && persistent) load(packageName, before) else null
            if (stored != null && slot.generation.get() == generation && capture(packageName) == before) {
                slot.entry = Entry(generation, before, stored)
                return@run stored
            }
            val identity = try {
                onRead()
                HostCodeIdentity(packageName, before.installation.versionCode, digest(before.installation.files))
            } catch (_: IOException) {
                return@run null
            } catch (_: IllegalArgumentException) {
                // 例如拆分包尚未完全落盘，安装快照没有可用的 DEX。
                return@run null
            }
            if (slot.generation.get() != generation || capture(packageName) != before || slot.generation.get() != generation) {
                return@run null
            }
            // 不把刚好处于文件系统计时粒度内的结果留下，避免时间走过该窗口后反而命中旧摘要。
            if (reusable) {
                slot.entry = Entry(generation, before, identity)
                if (persistent) save(packageName, before, identity)
                if (slot.generation.get() != generation) {
                    record(packageName)?.delete()
                    slot.entry = null
                    return@run null
                }
            }
            identity
        }
    }

    /** 不等磁盘读取结束；下一次查询不能复用当前代际的结果。 */
    fun invalidate(packageName: String) { slots.computeIfAbsent(packageName) { Slot() }.generation.incrementAndGet(); record(packageName)?.delete() }

    private fun record(pkg: String): File? = directory?.let { File(it, hash(pkg) + ".identity") }
    private fun hash(value: String): String = io.github.wzgrx.dexadapt.Hex.of(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
    private fun key(pkg: String, value: Snapshot): String {
        val fields = listOf("identity-v1", pkg, value.installation.versionCode.toString(),
            value.installation.lastUpdateTime.toString(), value.files.size.toString()) +
            value.files.flatMap { listOf(it.path, it.bytes.toString(), it.modified.toString(), it.key.orEmpty(), it.changed.toString()) }
        return hash(fields.joinToString("") { it.length.toString() + ":" + it })
    }

    private fun load(pkg: String, value: Snapshot): HostCodeIdentity? = runCatching {
        val lines = record(pkg)?.takeIf { it.length() in 1..1024 }?.readLines() ?: return null
        if (lines.size != 3 || lines[0] != key(pkg, value) || !lines[1].matches(Regex("[a-f0-9]{64}")) ||
            lines[2] != hash(lines[0] + "\n" + lines[1])) return null
        HostCodeIdentity(pkg, value.installation.versionCode, lines[1])
    }.getOrNull()

    private fun save(pkg: String, value: Snapshot, identity: HostCodeIdentity) {
        runCatching {
            val file = record(pkg) ?: return
            file.parentFile?.mkdirs()
            val key = key(pkg, value)
            val text = key + "\n" + identity.dexSha256
            val tmp = File(file.path + ".tmp")
            tmp.writeText(text + "\n" + hash(text) + "\n")
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }

    private fun <T> locked(pkg: String, block: (Boolean) -> T): T {
        val file = record(pkg) ?: return block(false)
        return synchronized(locks.computeIfAbsent(file.absolutePath) { Any() }) {
            val lockFile = try {
                file.parentFile?.mkdirs()
                RandomAccessFile(File(file.path + ".lock"), "rw")
            } catch (_: IOException) { null } catch (_: SecurityException) { null }
            if (lockFile == null) block(false) else lockFile.use { channel ->
                val lock = try { channel.channel.lock() } catch (_: IOException) { null }
                if (lock == null) block(false) else lock.use { block(true) }
            }
        }
    }

    private companion object { val locks = ConcurrentHashMap<String, Any>() }

    private fun capture(packageName: String): Snapshot? {
        val installation = snapshot(packageName)?.let { it.copy(files = it.files.toList()) } ?: return null
        val files = try {
            installation.files.map(::fileState)
        } catch (_: IOException) {
            return null
        }
        return Snapshot(installation, files)
    }

    private fun FileState.canReuse(nowMillis: Long): Boolean {
        if (key == null || changed == null) return false
        val instant = changed.toInstant()
        // 部分 Android 文件系统/实现的 ctime 只有秒级精度；同一秒内的多次写入可能不可区分。
        // 对该精度及不可靠的未来时钟，只返回本次读取结果，不留下可复用的条目。
        if (instant.nano == 0 && nowMillis - changed.toMillis() < 2_000L) return false
        return changed.toMillis() <= nowMillis
    }

    private fun fileState(file: File): FileState {
        val path = file.toPath()
        val unix = try {
            Files.readAttributes(path, "unix:size,lastModifiedTime,fileKey,ctime")
        } catch (_: UnsupportedOperationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        if (unix != null) {
            return FileState(file.absolutePath, unix.getValue("size") as Long,
                unix.getValue("lastModifiedTime") as FileTime, unix["fileKey"]?.toString(), unix["ctime"] as? FileTime)
        }
        val basic = Files.readAttributes(path, BasicFileAttributes::class.java)
        return FileState(file.absolutePath, basic.size(), basic.lastModifiedTime(), basic.fileKey()?.toString(), null)
    }
}
