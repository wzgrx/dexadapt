package io.github.wzgrx.dexadapt.android

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import java.security.MessageDigest
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeoutException

/** 前台身份请求的只读绑定通道。只连接指定签名模块，不启动握手或界面。 */
object DigestChannel {
    const val METHOD = "identity.ticket.get.v1"
    const val NAME = "identityTicketName"
    private val calls = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, SynchronousQueue(),
        { task -> Thread(task, "adapt-digest-request").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())

    @Suppress("DEPRECATION")
    fun trustedUid(context: Context, module: String, certificate: String): Int? = runCatching {
        val info = context.packageManager.getPackageInfo(module, PackageManager.GET_SIGNING_CERTIFICATES)
        val signatures = info.signingInfo?.apkContentsSigners.orEmpty()
        val trusted = signatures.any { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) } == certificate
        }
        if (trusted) info.applicationInfo?.uid else null
    }.getOrNull()

    @Suppress("DEPRECATION")
    fun request(context: Context, uri: Uri, module: String, certificate: String, pkg: String, name: String, observe: (String) -> Unit = {}): String? {
        fun status(value: String) { runCatching { observe(value) } }
        if (!name.matches(Regex("identity-ticket-v1-[a-f0-9]{64}\\.json"))) { status("invalid_name"); return null }
        val provider = runCatching { context.packageManager.resolveContentProvider(uri.authority.orEmpty(), 0) }.getOrNull() ?: run { status("provider_unavailable"); return null }
        val uid = trustedUid(context, module, certificate) ?: run { status("module_untrusted_or_invisible"); return null }
        if (provider.packageName != module || provider.applicationInfo.uid != uid) { status("provider_mismatch"); return null }
        val task = FutureTask {
            context.contentResolver.call(uri, METHOD, pkg, Bundle().apply { putString(NAME, name) })?.getString("json")
        }
        try { calls.execute(task) } catch (_: RejectedExecutionException) { status("queue_full"); return null }
        status("submitted")
        return try { task.get(1800, TimeUnit.MILLISECONDS)?.takeIf { it.length <= 2048 }.also { status(if (it != null) "success" else "empty_reply") } }
        catch (_: TimeoutException) { status("timeout"); task.cancel(true); null }
        catch (_: InterruptedException) { status("interrupted"); task.cancel(true); Thread.currentThread().interrupt(); null }
        catch (error: Exception) { status("failed_" + error.javaClass.simpleName); null }
    }
}
