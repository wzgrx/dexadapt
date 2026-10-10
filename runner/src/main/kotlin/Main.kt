import core.Engine
import core.Matrix
import core.Report
import core.SpecLoader
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import kotlin.system.exitProcess

/**
 * X-Clean 通用桌面验证台 —— CLI
 *
 * 用法:
 *   probe  --spec <spec.yaml> --apk <apk> [--label <name>] [--out <report.json>]
 *   matrix --spec <spec.yaml> --apks-dir <dir> [--out <dir>]
 */
fun main(args: Array<String>) {
    when (args.getOrNull(0)) {
        "probe" -> probe(parseOpts(args.drop(1)))
        "worker" -> probe(parseOpts(args.drop(1)))
        "matrix" -> {
            val o = parseOpts(args.drop(1))
            val spec = o["spec"] ?: error("--spec required")
            val dir = o["apks-dir"] ?: error("--apks-dir required")
            val out = o["out"] ?: "../results/run-${System.currentTimeMillis()}"
            exitProcess(Matrix.run(spec, dir, out))
        }
        else -> println(
            """
            |X-Clean 通用桌面验证台
            |  probe  --spec <spec.yaml> --apk <apk> [--label <name>] [--out <report.json>]
            |  matrix --spec <spec.yaml> --apks-dir <dir> [--out <dir>]
            """.trimMargin()
        )
    }
}

private fun parseOpts(args: List<String>): Map<String, String> {
    val map = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        if (args[i].startsWith("--")) {
            val k = args[i].substring(2)
            val v = args.getOrNull(i + 1) ?: error("missing value for --$k")
            map[k] = v
            i += 2
        } else {
            i++
        }
    }
    return map
}

private fun loadNative() {
    val lib = File("../third_party/DexKit/build/desktop/libdexkit.so")
    if (lib.exists()) {
        System.load(lib.absolutePath)
    } else {
        System.loadLibrary("dexkit")
    }
}

private fun probe(o: Map<String, String>) {
    val specPath = o["spec"] ?: error("--spec required")
    val apkPath = o["apk"] ?: error("--apk required")
    val label = o["label"] ?: File(apkPath).nameWithoutExtension
    val out = o["out"]

    loadNative()
    val spec = SpecLoader.load(File(specPath))
    val apk = File(apkPath)
    check(apk.exists()) { "apk not found: ${apk.absolutePath}" }

    val t0 = System.currentTimeMillis()
    val results = DexKitBridge.create(apk.absolutePath).use { bridge ->
        println("[probe] ${apk.name} (${apk.length() / 1024 / 1024} MB) dex=${bridge.getDexNum()}")
        Engine.runTargets(bridge, spec)
    }
    val duration = System.currentTimeMillis() - t0

    results.forEach { r ->
        val tail = if (r.resolved.isEmpty()) "" else "  → " + r.resolved.take(3).joinToString(" | ")
        println("  [${r.status}] ${r.key} hits=${r.hits} (${r.ms}ms)$tail")
    }

    val report = Report.build(spec, apk, label, results, duration)
    if (out != null) {
        Report.write(report, File(out))
        println("[probe] report -> $out")
    }
}
