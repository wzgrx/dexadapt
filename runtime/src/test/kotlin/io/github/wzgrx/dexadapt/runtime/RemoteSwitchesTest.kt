package io.github.wzgrx.dexadapt.runtime

import io.github.wzgrx.dexadapt.TableSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteSwitchesTest {
    private val keys = TableSignature.generate()
    private val config = RemoteSwitches.Config(
        issued = 100,
        rules = listOf(
            RemoteSwitches.Rule(listOf("download.note"), "9.51.0 上保存会崩溃", versionCodes = listOf(9510803)),
            RemoteSwitches.Rule(listOf("feed.ads"), "灰度包的广告接口变了", versionCodes = listOf(9510803), dexSha256 = "ABCD"),
            RemoteSwitches.Rule(listOf("old.thing"), "已过期", until = 50),
            RemoteSwitches.Rule(listOf("everywhere"), "所有版本"),
        ),
    )

    @Test
    fun `signed config disables matching features only`() {
        val body = RemoteSwitches.toJson(config).toByteArray()
        val sig = TableSignature.sign(body, keys.privateKey)
        val read = RemoteSwitches.read(body, sig, keys.publicKey)
        assertEquals(config, read)
        assertEquals(mapOf("download.note" to "9.51.0 上保存会崩溃", "feed.ads" to "灰度包的广告接口变了", "everywhere" to "所有版本"), RemoteSwitches.disabled(read, 9510803, "abcd", now = 200))
        assertEquals(setOf("download.note", "everywhere"), RemoteSwitches.disabled(read, 9510803, "other", now = 200).keys)
        assertEquals(setOf("everywhere"), RemoteSwitches.disabled(read, 9500803, null, now = 200).keys)
        assertEquals(setOf("old.thing", "everywhere"), RemoteSwitches.disabled(read, 1, null, now = 10).keys)
        assertNull(RemoteSwitches.read(body + ' '.code.toByte(), sig, keys.publicKey))
    }
}
