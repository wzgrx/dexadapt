package io.github.wzgrx.dexadapt.runtime

import io.github.wzgrx.dexadapt.resolve.DexDigest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 仅对同一份系统安装记录及同一批未变动的文件复用摘要；不持久化快速身份。 */
class InstalledDexIdentityCache(
    private val snapshot: (String) -> Installation?,
    private val digest: (List<File>) -> String = DexDigest::of,
) {
    data class Installation(val versionCode: Long, val lastUpdateTime: Long, val files: List<File>)
    private data class FileState(val path: String, val bytes: Long, val modified: FileTime, val key: Any?, val changed: FileTime?)
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
            val generation = slot.generation.get()
            val before = capture(packageName)
            if (before == null || before.installation.files.isEmpty()) {
                slot.entry = null
                return@synchronized null
            }
            val reusable = before.files.all { it.canReuse(System.currentTimeMillis()) }
            val prior = slot.entry
            if (prior?.generation == generation && prior.snapshot == before && reusable) {
                return@synchronized prior.identity.takeIf { slot.generation.get() == generation }
            }
            slot.entry = null
            val identity = try {
                onRead()
                HostCodeIdentity(packageName, before.installation.versionCode, digest(before.installation.files))
            } catch (_: IOException) {
                return@synchronized null
            } catch (_: IllegalArgumentException) {
                // 例如拆分包尚未完全落盘，安装快照没有可用的 DEX。
                return@synchronized null
            }
            if (slot.generation.get() != generation || capture(packageName) != before || slot.generation.get() != generation) {
                return@synchronized null
            }
            // 不把刚好处于文件系统计时粒度内的结果留下，避免时间走过该窗口后反而命中旧摘要。
            if (reusable) slot.entry = Entry(generation, before, identity)
            identity
        }
    }

    /** 不等磁盘读取结束；下一次查询不能复用当前代际的结果。 */
    fun invalidate(packageName: String) { slots.computeIfAbsent(packageName) { Slot() }.generation.incrementAndGet() }

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
                unix.getValue("lastModifiedTime") as FileTime, unix["fileKey"], unix["ctime"] as? FileTime)
        }
        val basic = Files.readAttributes(path, BasicFileAttributes::class.java)
        return FileState(file.absolutePath, basic.size(), basic.lastModifiedTime(), basic.fileKey(), null)
    }
}
