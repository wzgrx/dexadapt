package io.github.wzgrx.dexadapt.runtime

import io.github.wzgrx.dexadapt.Resolved
import io.github.wzgrx.dexadapt.SymbolTable
import io.github.wzgrx.dexadapt.TableSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SharedTablesTest {
    private val keys = TableSignature.generate()
    private val sha = "ab".repeat(32)
    private val table = SharedTables.stamp(SymbolTable(9500803, sha, mapOf("a.b" to Resolved.Class("x.Y"))), "cat1", "3.0.3", now = 1)

    /** 假的发布目录：文件名 → 内容。 */
    private fun served(files: Map<String, ByteArray>) = SharedTables.Fetcher { url -> files[url.substringAfterLast('/')] }

    private fun publish(t: SymbolTable, key: String = keys.privateKey): Map<String, ByteArray> {
        val body = t.toJson().toByteArray()
        val name = SharedTables.fileName(t.versionCode, t.dexSha256)
        return mapOf(name to body, "$name.sig" to TableSignature.sign(body, key).toByteArray())
    }

    @Test
    fun `signed table for this apk and catalog is accepted`() {
        val r = SharedTables.fetch("https://x/tables", 9500803, sha, "cat1", keys.publicKey, served(publish(table)))
        assertTrue(r is SharedTables.Result.Found)
        assertEquals(table, (r as SharedTables.Result.Found).table)
        assertFalse(AdaptPolicy.choose(r.table, null, "cat1", null).observe)
    }

    @Test
    fun `anything else is rejected or absent`() {
        assertEquals(SharedTables.Result.Absent, SharedTables.fetch("u", 9500803, sha, "cat1", keys.publicKey, served(emptyMap())))
        fun reason(files: Map<String, ByteArray>, catalog: String = "cat1") =
            (SharedTables.fetch("u", 9500803, sha, catalog, keys.publicKey, served(files)) as SharedTables.Result.Rejected).reason
        assertTrue(reason(publish(table, TableSignature.generate().privateKey)).contains("签名"))
        // 改过的内容：签名对不上
        val tampered = publish(table).toMutableMap().also { m -> m[SharedTables.fileName(9500803, sha)] = table.copy(entries = emptyMap()).toJson().toByteArray() }
        assertTrue(reason(tampered).contains("签名"))
        assertTrue(reason(publish(table), catalog = "cat2").contains("另一版模块"))
        assertTrue(reason(publish(table).filterKeys { !it.endsWith(".sig") }).contains("签名文件"))
        assertTrue(reason(publish(table.copy(generator = "dexkit"))).contains("CI"))
        val down = SharedTables.Fetcher { throw IOException("timeout") }
        assertTrue((SharedTables.fetch("u", 9500803, sha, "cat1", keys.publicKey, down) as SharedTables.Result.Rejected).reason.contains("timeout"))
    }

    @Test
    fun `signature round trip`() {
        val sig = TableSignature.sign("hello".toByteArray(), keys.privateKey)
        assertTrue(TableSignature.verify("hello".toByteArray(), sig, keys.publicKey))
        assertFalse(TableSignature.verify("hellO".toByteArray(), sig, keys.publicKey))
        assertFalse(TableSignature.verify("hello".toByteArray(), "not base64!", keys.publicKey))
    }
}
