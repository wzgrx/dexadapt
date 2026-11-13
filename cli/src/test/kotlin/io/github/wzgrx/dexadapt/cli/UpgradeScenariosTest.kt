package io.github.wzgrx.dexadapt.cli

import io.github.wzgrx.dexadapt.SymbolTable
import io.github.wzgrx.dexadapt.runtime.AdaptPolicy
import io.github.wzgrx.dexadapt.runtime.DirectoryAdaptationStore
import io.github.wzgrx.dexadapt.runtime.HostCodeIdentity
import io.github.wzgrx.dexadapt.runtime.StagedAdaptation
import io.github.wzgrx.dexadapt.runtime.UpgradeCoordinator
import io.github.wzgrx.dexadapt.runtime.UpgradeCoordinator.Feature
import io.github.wzgrx.dexadapt.runtime.UpgradeCoordinator.Outcome
import io.github.wzgrx.dexadapt.runtime.UpgradeCoordinator.Trigger
import io.github.wzgrx.dexadapt.spec.Spec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor

/** 真实 APK 和 DexKit；安装事件与功能确认由测试驱动，不替代 LSPosed 实机。 */
class UpgradeScenariosTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `upgrade lifecycle on real APKs with staged DexKit and persistent tables`() {
        val root = System.getenv("DEXADAPT_UPGRADE_EVIDENCE")
        if (root.isNullOrBlank()) {
            assertFalse("发布需要升级场景证据", System.getenv("DEXADAPT_REQUIRE_UPGRADE") == "1")
            assumeTrue("未配置升级场景证据目录", false)
            return
        }
        val project = File(requireNotNull(System.getenv("DEXADAPT_UPGRADE_PROJECTS")))
        val consumer = File(project, "biliclear-v4")
        val specs = Spec.read(File(consumer, "docs/adapt/catalog.yaml"))
        val catalog = specs.catalog.fingerprint()
        data class Host(val key: HostCodeIdentity, val input: Engines.Input, val build: String, val golden: SymbolTable)
        fun host(code: Long, asCode: Long = code): Host {
            val input = Engines.identify(File(project, "bili-apks/cn-$code/base.apk"))
            val golden = SymbolTable.fromJson(File(consumer, "core/symbols/src/main/resources/symbols/cn-$code.json").readText())
            assertEquals(golden.dexSha256, input.identity)
            assertEquals(code, input.info.versionCode)
            return Host(HostCodeIdentity(input.info.packageName, asCode, input.identity), input, "CN_$code", golden)
        }

        val a = host(9170300)
        val b = host(9180300)
        val c = host(9190400)
        // 模拟系统保留版本码、但替换为另一份真实 DEX。APK 的原始清单未改动。
        val replaced = c.copy(key = c.key.copy(versionCode = b.key.versionCode))
        val byKey = listOf(a, b, c, replaced).associateBy { it.key }
        val features = setOf(
            Feature("story", setOf("storyEntrance.initial", "storyEntrance.constructor")),
            Feature("startup-speed", setOf("speedExperiment.speedList"), startupOnly = true),
        )
        val dir = temp.newFolder()
        val events = ArrayDeque<Runnable>()
        val published = ArrayList<UpgradeCoordinator.Publication>()
        val reports = ArrayList<Pair<String, UpgradeCoordinator.Report>>()
        var installed = a.key
        var scenario = "first-open"
        var interruption = false
        var beforeScan: (() -> Unit)? = null
        var scans = 0
        fun store() = DirectoryAdaptationStore(dir)
        fun coordinator() = UpgradeCoordinator(Executor(events::addLast), { installed }, store(), { req, stop, publish ->
            scans++
            val h = byKey.getValue(req.identity)
            beforeScan?.also { beforeScan = null; it() }
            stop.check()
            Engines.open(h.input, Engine.DEXKIT).use { engine ->
                val full = StagedAdaptation.scan(req, engine.source, specs.catalog.symbols, stop, { partial ->
                    assertTrue(partial.partial)
                    assertTrue(partial.entries.size < h.golden.entries.size)
                    assertTrue(partial.entries.keys.containsAll(features.first().symbols))
                    assertTrue("startup-speed scanned too soon", "speedExperiment.speedList" !in partial.entries)
                    publish(partial)
                    if (interruption) throw InterruptedException("模拟中断")
                }, h.build)
                assertEquals(h.golden.entries, full.entries)
                assertEquals(h.golden.absent, full.absent)
                assertEquals(emptyMap<String, String>(), full.missing)
                full
            }
        }, { p ->
            assertEquals(installed, p.request.identity)
            assertTrue(installed.matches(p.table))
            assertEquals(p.request.catalog, p.table.catalog)
            published += p
            if (!p.table.partial) assertEquals(setOf("startup-speed"), p.restartRequired)
            // 测试消费方核对真实的目标描述符后确认恢复；不启动第三方 App。
            val expected = byKey.getValue(installed).golden.entries
            for (f in features.filter { it.id in p.ready }) {
                for (id in f.symbols) assertEquals(expected[id], p.table.entries[id])
            }
            p.ready
        }, { reports += scenario to it })
        var q = coordinator()
        fun send(trigger: Trigger) = q.onEvent(installed.packageName, catalog, features, trigger)
        fun drain() { events.removeFirst().run(); assertTrue(events.isEmpty()) }

        assertTrue(send(Trigger.HOST_FIRST_OPEN))
        assertFalse(send(Trigger.MODULE_STARTED))
        assertFalse(send(Trigger.PERIODIC))
        drain()
        assertEquals(Outcome.SCANNED, reports.last().second.outcome)
        assertTrue(published.first().table.partial)
        assertTrue(published.first().requiresObservation)
        assertEquals(setOf("story"), published.first().ready)
        assertNotNull(store().local(a.key, catalog))

        scenario = "interrupted"
        installed = b.key
        interruption = true
        send(Trigger.HOST_PACKAGE_CHANGED)
        drain()
        assertEquals(Outcome.FAILED, reports.last().second.outcome)
        assertNull(store().local(b.key, catalog))
        scenario = "restart-and-retry"
        q = coordinator()
        interruption = false
        send(Trigger.RETRY)
        drain()
        assertEquals(Outcome.SCANNED, reports.last().second.outcome)
        assertNotNull(store().local(b.key, catalog))

        scenario = "same-version-replacement"
        installed = replaced.key
        val oldTable = store().local(b.key, catalog)!!
        assertNull(AdaptPolicy.chooseVerified(installed, oldTable, b.golden, catalog).table)
        send(Trigger.HOST_PACKAGE_CHANGED)
        drain()
        assertEquals(Outcome.SCANNED, reports.last().second.outcome)
        assertEquals(replaced.key.dexSha256, store().local(replaced.key, catalog)?.dexSha256)

        scenario = "consecutive-upgrades"
        val fresh = catalog + "-changed"
        val after = published.size
        installed = a.key
        beforeScan = {
            installed = b.key
            q.onEvent(installed.packageName, fresh, features, Trigger.HOST_PACKAGE_CHANGED)
            installed = c.key
            q.onEvent(installed.packageName, fresh, features, Trigger.HOST_PACKAGE_CHANGED)
        }
        q.onEvent(installed.packageName, fresh, features, Trigger.HOST_PACKAGE_CHANGED)
        drain()
        assertEquals(listOf(Outcome.SUPERSEDED, Outcome.SCANNED), reports.takeLast(2).map { it.second.outcome })
        assertTrue(published.drop(after).all { it.request.identity == c.key })
        assertNull(store().local(a.key, fresh))
        assertNull(store().local(b.key, fresh))

        scenario = "downgrade"
        installed = a.key
        val before = scans
        send(Trigger.HOST_PACKAGE_CHANGED)
        drain()
        assertEquals(Outcome.REUSED, reports.last().second.outcome)
        assertEquals(before, scans)
        assertTrue(reports.filter { it.second.outcome in setOf(Outcome.SCANNED, Outcome.REUSED) }.all {
            val t = it.second.firstRestoreNanos
            t != null && t in 0..it.second.elapsedNanos
        })
        val evidence = buildJsonObject {
            put("scope", "真实宿主 APK、桌面 DexKit、模拟安装事件及功能确认；未运行宿主 UI 或 LSPosed")
            put("scenarios", buildJsonArray {
                for ((label, report) in reports) add(buildJsonObject {
                    put("scenario", label)
                    put("outcome", report.outcome.name)
                    put("versionCode", report.request.identity.versionCode)
                    put("dexSha256", report.request.identity.dexSha256)
                    put("firstRestoreMs", report.firstRestoreNanos?.let { JsonPrimitive(it / 1_000_000.0) } ?: kotlinx.serialization.json.JsonNull)
                    put("elapsedMs", report.elapsedNanos / 1_000_000.0)
                })
            })
        }
        File(root, "upgrade-scenarios.json").writeText(Json.encodeToString(evidence) + "\n")
    }
}
