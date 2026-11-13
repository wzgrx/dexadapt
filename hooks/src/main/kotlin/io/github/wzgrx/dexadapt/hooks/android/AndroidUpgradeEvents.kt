package io.github.wzgrx.dexadapt.hooks.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import io.github.wzgrx.dexadapt.runtime.HostCodeIdentity
import io.github.wzgrx.dexadapt.runtime.UpgradeCoordinator
import java.io.Closeable
import java.io.File

/**
 * Android 事件适配器。回调只投递持久任务，不读 APK；Worker 或宿主桥接的后台线程调用协调器。
 * 宿主包的可见性（queries）与 WorkManager 的任务存储属于使用方。
 */
class AndroidUpgradeEvents(
    context: Context,
    packages: Set<String>,
    private val enqueue: (String, UpgradeCoordinator.Trigger) -> Unit,
) : Closeable {
    private val app = context.applicationContext
    private val hosts = packages.toSet()
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            if (pkg !in hosts) return
            when (intent.action) {
                Intent.ACTION_PACKAGE_REMOVED -> if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                Intent.ACTION_PACKAGE_ADDED -> if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                Intent.ACTION_PACKAGE_REPLACED -> Unit
                else -> return
            }
            enqueue(pkg, UpgradeCoordinator.Trigger.HOST_PACKAGE_CHANGED)
        }
    }

    @Synchronized
    @Suppress("DEPRECATION")
    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else app.registerReceiver(receiver, filter)
        registered = true
        hosts.forEach { enqueue(it, UpgradeCoordinator.Trigger.MODULE_STARTED) }
    }

    fun hostOpened(packageName: String) {
        require(packageName in hosts)
        enqueue(packageName, UpgradeCoordinator.Trigger.HOST_FIRST_OPEN)
    }

    @Synchronized override fun close() {
        if (registered) {
            app.unregisterReceiver(receiver)
            registered = false
        }
    }

    companion object {
        data class Installed(val versionCode: Long, val apks: List<File>)

        /** 在后台读：检查摘要计算期间安装记录有没有变化；找不到或换包时不给出可选表身份。 */
        @Suppress("DEPRECATION")
        fun installedIdentity(context: Context, packageName: String): HostCodeIdentity? {
            fun snapshot(): Pair<Installed, Long>? {
                val info = try {
                    context.packageManager.getPackageInfo(packageName, 0)
                } catch (_: PackageManager.NameNotFoundException) {
                    return null
                }
                val application = info.applicationInfo ?: return null
                val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                val paths = listOf(application.sourceDir) + application.splitSourceDirs.orEmpty()
                return Installed(version, paths.map(::File)) to info.lastUpdateTime
            }
            val before = snapshot() ?: return null
            val identity = try {
                HostCodeIdentity.of(packageName, before.first.versionCode, before.first.apks)
            } catch (_: java.io.IOException) {
                return null
            }
            return identity.takeIf { before == snapshot() }
        }
    }
}
