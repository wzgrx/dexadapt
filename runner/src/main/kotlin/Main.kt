import org.luckypray.dexkit.DexKitBridge
import java.io.File

/**
 * X-Clean 桌面验证台 —— 冒烟版（Phase B）
 *
 * 用法: ./gradlew run --args="<apk路径> [搜索字符串]"
 * 默认: 微信设备样本 + WeKit 同款检测点字符串（Phase D 预览）
 */
fun main(args: Array<String>) {
    val libPath = File("../third_party/DexKit/build/desktop/libdexkit.so").absolutePath
    println("[harness] loading native: $libPath")
    System.load(libPath)

    val apkPath = args.getOrNull(0) ?: "../apks/wechat-device-8079.apk"
    val needles = args.drop(1).ifEmpty { listOf("de.robv.android.xposed.XposedBridge") }
    val apk = File(apkPath)
    check(apk.exists()) { "APK not found: ${apk.absolutePath}" }
    println("[harness] apk: ${apk.name} (${apk.length() / 1024 / 1024} MB)")
    println("[harness] needles: $needles")

    val t0 = System.currentTimeMillis()
    DexKitBridge.create(apk.absolutePath).use { bridge ->
        println("[harness] bridge created in ${System.currentTimeMillis() - t0} ms, dex count = ${bridge.getDexNum()}")

        // ① 带包名范围（WeKit 同款搜索）
        val t1 = System.currentTimeMillis()
        val scoped = bridge.findClass {
            searchPackages("com.tencent.mm.app")
            matcher { usingEqStrings(*needles.toTypedArray()) }
        }
        println("[harness] findClass @com.tencent.mm.app -> ${scoped.size} hits (${System.currentTimeMillis() - t1} ms)")
        scoped.take(10).forEach { println("    class: ${it.name}") }

        // ② 全 dex 范围
        val t2 = System.currentTimeMillis()
        val all = bridge.findClass {
            matcher { usingEqStrings(*needles.toTypedArray()) }
        }
        println("[harness] findClass @ALL -> ${all.size} hits (${System.currentTimeMillis() - t2} ms)")
        all.take(10).forEach { println("    class: ${it.name}") }
    }
    println("[harness] done in ${System.currentTimeMillis() - t0} ms total")
}
