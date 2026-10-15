package core

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

object Matrix {

    fun run(specPath: String, apksDir: String, outDir: String): Int {
        val apks = File(apksDir).listFiles { f -> f.isFile && f.name.endsWith(".apk") }
            ?.sortedBy { it.name } ?: emptyList()
        check(apks.isNotEmpty()) { "no *.apk found in $apksDir" }
        val out = File(outDir)
        out.mkdirs()

        val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classpath = System.getProperty("java.class.path")

        var failed = 0
        val done = mutableListOf<String>()
        for (apk in apks) {
            val label = apk.nameWithoutExtension
            val reportFile = File(out, "$label.json")
            println("[matrix] ===== ${apk.name} (${apk.length() / 1024 / 1024} MB) =====")
            val pb = ProcessBuilder(
                javaBin, "--enable-native-access=ALL-UNNAMED",
                "-cp", classpath,
                "MainKt", "worker",
                "--spec", specPath,
                "--apk", apk.absolutePath,
                "--label", label,
                "--out", reportFile.absolutePath,
            )
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val log = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            println(log.trim().lines().takeLast(8).joinToString("\n"))
            if (code != 0) {
                failed++
                println("[matrix] !! worker exit=$code for ${apk.name}")
            } else {
                done += label
            }
        }

        val summary = buildJsonObject {
            put("schema", 1)
            put("spec", specPath)
            put("apksDir", apksDir)
            put("apkCount", apks.size)
            put("failedWorkers", failed)
            putJsonArray("reports") {
                done.forEach { label ->
                    addJsonObject {
                        put("label", label)
                        put("file", "$label.json")
                    }
                }
            }
        }
        Report.write(summary, File(out, "summary.json"))
        println("[matrix] done: ${done.size}/${apks.size} ok, $failed failed -> ${out.absolutePath}")
        return if (failed > 0) 1 else 0
    }
}
