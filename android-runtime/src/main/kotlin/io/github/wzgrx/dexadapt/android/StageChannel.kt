package io.github.wzgrx.dexadapt.android

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import io.github.wzgrx.dexadapt.runtime.StageNotice
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/** Android Binder 只传就绪标记，不推送可直接安装的符号表。 */
object StageChannel {
    const val METHOD = "adapt.stage.subscribe.v1"
    const val CALLBACK = "stageCallback"
    const val EPOCH = "stageServer"
    private const val DESCRIPTOR = "io.github.wzgrx.dexadapt.StageSignal.v1"
    private const val NOTICE = IBinder.FIRST_CALL_TRANSACTION

    class Receiver(private val trustedUid: Int, private val receive: (StageNotice) -> Unit) : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != NOTICE) return super.onTransact(code, data, reply, flags)
            if (getCallingUid() != trustedUid) return false
            data.enforceInterface(DESCRIPTOR)
            val raw = data.readString() ?: return false
            StageNotice.parse(raw)?.let { runCatching { receive(it) } }
            return true
        }
    }

    class Publisher {
        val epoch: String = java.util.UUID.randomUUID().toString()
        private data class Target(val binder: IBinder, val death: IBinder.DeathRecipient)
        private val targets = ConcurrentHashMap<String, Target>()
        private val latest = ConcurrentHashMap<String, StageNotice>()
        private val serial = AtomicLong()
        private val queued = AtomicBoolean()
        private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "adapt-stage-notify").apply { isDaemon = true } }

        fun subscribe(pkg: String, binder: IBinder): String {
            val death = IBinder.DeathRecipient { targets.computeIfPresent(pkg) { _, old -> if (old.binder === binder) null else old } }
            binder.linkToDeath(death, 0)
            targets.put(pkg, Target(binder, death))?.let { runCatching { it.binder.unlinkToDeath(it.death, 0) } }
            schedule()
            return epoch
        }
        fun publish(pkg: String, version: Long, digest: String, catalog: String, partial: Boolean, at: Long) {
            val notice = StageNotice(epoch, serial.incrementAndGet(), pkg, version, digest, catalog, partial, at)
            latest.compute(pkg) { _, old -> if (old == null || old.sequence < notice.sequence) notice else old }
            schedule()
        }
        private fun remove(pkg: String, target: Target) {
            if (targets.remove(pkg, target)) runCatching { target.binder.unlinkToDeath(target.death, 0) }
        }
        private fun schedule() {
            if (!queued.compareAndSet(false, true)) return
            io.execute {
                val boundary = serial.get()
                try {
                    latest.forEach { (pkg, notice) ->
                        val target = targets[pkg] ?: return@forEach
                        val data = Parcel.obtain()
                        try {
                            data.writeInterfaceToken(DESCRIPTOR)
                            data.writeString(notice.toJson())
                            if (!target.binder.transact(NOTICE, data, null, IBinder.FLAG_ONEWAY)) remove(pkg, target)
                        } catch (_: android.os.RemoteException) { remove(pkg, target) }
                        finally { data.recycle() }
                    }
                } finally {
                    sent.accumulateAndGet(boundary, ::maxOf)
                    queued.set(false)
                    // 发布发生在发送期间时再合并一轮，旧标记由宿主序号门禁丢弃。
                    if (latest.values.any { it.sequence > sent.get() }) schedule()
                }
            }
        }
        private val sent = AtomicLong()
    }
}
