package io.github.wzgrx.dexadapt.runtime

import io.github.wzgrx.dexadapt.SymbolTable
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** 一个模块进程持有一个实例；启动广播、宿主首次打开和持久任务都投递到同一入口。 */
class UpgradeCoordinator(
    private val executor: Executor,
    private val currentIdentity: (String) -> HostCodeIdentity?,
    private val tables: AdaptationStore,
    private val scan: (Request, Checkpoint, (SymbolTable) -> Unit) -> SymbolTable,
    private val install: (Publication) -> Set<String>,
    private val observe: (Report) -> Unit = {},
    private val nanoTime: () -> Long = System::nanoTime,
) {
    enum class Trigger { HOST_FIRST_OPEN, HOST_PACKAGE_CHANGED, MODULE_STARTED, PERIODIC, RETRY }
    enum class Outcome { SCANNED, REUSED, SUPERSEDED, FAILED }

    data class Feature(val id: String, val symbols: Set<String>, val startupOnly: Boolean = false) {
        init { require(id.isNotBlank() && symbols.isNotEmpty()) }
    }

    data class Request(val identity: HostCodeIdentity, val catalog: String, val enabled: Set<Feature>, val trigger: Trigger) {
        init {
            require(catalog.isNotBlank())
            require(enabled.map { it.id }.distinct().size == enabled.size)
        }
    }

    data class Publication(
        val request: Request,
        val table: SymbolTable,
        val ready: Set<String>,
        val restartRequired: Set<String>,
        val requiresObservation: Boolean,
    )

    data class Report(
        val request: Request,
        val outcome: Outcome,
        val firstRestoreNanos: Long?,
        val elapsedNanos: Long,
        val error: String? = null,
    )

    fun interface Checkpoint { fun check() }

    private class Cancelled : RuntimeException()
    private class Work(val request: Request, val started: Long) {
        var cancelled = false
        var firstRestore: Long? = null
        val acknowledged = HashSet<String>()
        fun matches(other: Request): Boolean =
            !cancelled && request.identity == other.identity && request.catalog == other.catalog && request.enabled == other.enabled
    }

    private val lock = Any()
    private val pending = LinkedHashMap<String, Work>()
    private var active: Work? = null
    private var scheduled = false
    private var nextEvent = 0L
    private val latestEvent = HashMap<String, Long>()

    /** 事件入口：由模块后台线程调用；以当前安装身份为准，不使用广播携带的版本号。 */
    fun onEvent(packageName: String, catalog: String, enabled: Set<Feature>, trigger: Trigger): Boolean {
        val started = nanoTime()
        val event = synchronized(lock) { (++nextEvent).also { latestEvent[packageName] = it } }
        val identity = currentIdentity(packageName)
        require(identity == null || identity.packageName == packageName)
        val enqueue = synchronized(lock) {
            if (latestEvent[packageName] != event) return false
            latestEvent.remove(packageName)
            if (identity == null) {
                active?.takeIf { it.request.identity.packageName == packageName }?.cancelled = true
                pending.remove(packageName)
                return false
            }
            enqueueLocked(Request(identity, catalog, enabled, trigger), started)
        }
        return dispatch(enqueue)
    }

    /** 返回 false 表示已有等价任务；扫描在 executor 上运行。 */
    fun submit(request: Request): Boolean = dispatch(synchronized(lock) {
        latestEvent.remove(request.identity.packageName)
        enqueueLocked(request, nanoTime())
    })

    private enum class Enqueue { DUPLICATE, QUEUED, SCHEDULE }

    private fun enqueueLocked(request: Request, started: Long): Enqueue {
        // 防止调用方在任务排队期间修改可变集合。
        val snapshot = request.copy(enabled = request.enabled.mapTo(LinkedHashSet()) { it.copy(symbols = it.symbols.toSet()) })
        val pkg = snapshot.identity.packageName
        val queued = pending[pkg]
        val running = active?.takeIf { it.request.identity.packageName == pkg }
        if (queued?.matches(snapshot) == true || queued == null && running?.matches(snapshot) == true) {
            if (!scheduled && pending.isNotEmpty()) {
                scheduled = true
                return Enqueue.SCHEDULE
            }
            return Enqueue.DUPLICATE
        }
        running?.cancelled = true
        pending[pkg] = Work(snapshot, started)
        if (scheduled) return Enqueue.QUEUED
        scheduled = true
        return Enqueue.SCHEDULE
    }

    private fun dispatch(enqueue: Enqueue): Boolean {
        if (enqueue == Enqueue.SCHEDULE) {
            try {
                executor.execute(::drain)
            } catch (e: RejectedExecutionException) {
                synchronized(lock) { scheduled = false }
                throw e
            }
        }
        return enqueue != Enqueue.DUPLICATE
    }

    private fun drain() {
        while (true) {
            val work = synchronized(lock) {
                val first = pending.entries.firstOrNull()
                if (first == null) {
                    active = null
                    scheduled = false
                    return
                }
                first.value.also { pending.remove(first.key); active = it }
            }
            val report = execute(work)
            synchronized(lock) { if (active === work) active = null }
            // 诊断回调不应让后续扫描停住。
            try {
                observe(report)
            } catch (_: Exception) {
                // 结果已经提交；诊断由调用方单独保存。
            }
        }
    }

    private fun execute(work: Work): Report {
        val request = work.request
        fun report(outcome: Outcome, error: String? = null) =
            Report(request, outcome, work.firstRestore, nanoTime() - work.started, error)

        // 昂贵的身份检查不占队列锁；核对后再锁住提交，禁止新事件插到验收和提交之间。
        fun current() {
            synchronized(lock) { if (work.cancelled) throw Cancelled() }
            if (currentIdentity(request.identity.packageName) != request.identity) throw Cancelled()
        }
        fun deliver(table: SymbolTable, complete: Boolean, save: Boolean, observation: Boolean = true) {
            require(request.identity.matches(table) && table.catalog == request.catalog && table.partial != complete) {
                "扫描表身份或阶段无效"
            }
            require(table.entries.keys.none { it in table.absent || it in table.missing }) { "扫描表状态冲突" }
            current()
            synchronized(lock) {
                if (work.cancelled) throw Cancelled()
                val ready = request.enabled.filter { feature -> feature.symbols.all(table.entries::containsKey) }
                val restart = if (complete) ready.filter { it.startupOnly }.mapTo(sortedSetOf()) { it.id } else emptySet()
                val hot = ready.filterNot { it.startupOnly }.mapTo(sortedSetOf()) { it.id } - work.acknowledged
                if (complete || hot.isNotEmpty()) {
                    val acknowledged = install(Publication(request, table, hot, restart, observation))
                    require(hot.containsAll(acknowledged)) { "消费方确认了未提交的功能" }
                    work.acknowledged += acknowledged
                    if (acknowledged.isNotEmpty() && work.firstRestore == null) work.firstRestore = nanoTime() - work.started
                }
                if (save) {
                    // install 可能同步投递下一次升级；这时不再提交上一轮表。
                    if (work.cancelled) throw Cancelled()
                    if (currentIdentity(request.identity.packageName) != request.identity) throw Cancelled()
                    if (work.cancelled) throw Cancelled()
                    tables.save(request.identity, table)
                }
            }
        }
        return try {
            current()
            val choice = AdaptPolicy.chooseVerified(request.identity, tables.local(request.identity, request.catalog),
                tables.bundled(request.identity), request.catalog)
            val existing = choice.table
            if (existing != null) {
                // 内置表可不带 catalog；交付时绑定本次请求的目录。
                deliver(existing.copy(catalog = request.catalog), complete = true, save = false, observation = choice.observe)
                report(Outcome.REUSED)
            } else {
                val full = scan(request, Checkpoint {
                    synchronized(lock) { if (work.cancelled) throw Cancelled() }
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("扫描中断")
                }) { partial -> deliver(partial, complete = false, save = false) }
                deliver(full, complete = true, save = true)
                report(Outcome.SCANNED)
            }
        } catch (_: Cancelled) {
            report(Outcome.SUPERSEDED)
        } catch (e: Exception) {
            report(Outcome.FAILED, e.message ?: e.javaClass.simpleName)
        }
    }
}
