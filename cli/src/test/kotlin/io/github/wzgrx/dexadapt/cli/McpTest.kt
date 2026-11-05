package io.github.wzgrx.dexadapt.cli

import io.github.wzgrx.dexadapt.Catalog
import io.github.wzgrx.dexadapt.ClassQuery
import io.github.wzgrx.dexadapt.ClassSymbol
import io.github.wzgrx.dexadapt.spec.Spec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

class McpTest {
    @Test
    fun `speaks json rpc and runs a tool`() {
        val dir = java.nio.file.Files.createTempDirectory("dexadapt-mcp").toFile()
        try {
            val spec = File(dir, "a.yaml").apply { writeText(Spec(null, Catalog(listOf(ClassSymbol("main", ClassQuery(name = "a.Main"))))).toYaml()) }
            val out = File(dir, "a.json")
            val input = listOf(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""",
                """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
                """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"export","arguments":{"spec":"${spec.path}","out":"${out.path}"}}}""",
                """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"export","arguments":{}}}""",
                """{"jsonrpc":"2.0","id":5,"method":"nope"}""",
            ).joinToString("\n")
            val buffer = ByteArrayOutputStream()
            Mcp.serve(input.byteInputStream(), PrintStream(buffer, true, Charsets.UTF_8), drainOnEof = true)
            val replies = buffer.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }.sortedBy { it["id"]!!.jsonPrimitive.content.toInt() }
            assertEquals(listOf("1", "2", "3", "4", "5"), replies.map { it["id"]!!.jsonPrimitive.content })
            assertEquals("dexadapt", replies[0]["result"]!!.jsonObject["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            val tools = replies[1]["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
            assertEquals(Mcp.toolNames, tools)
            assertTrue("diff" in tools && "libs" in tools && "repair" in tools)
            val call = replies[2]["result"]!!.jsonObject
            assertEquals("false", call["isError"]!!.jsonPrimitive.content)
            assertTrue(call["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.contains("1 个符号"))
            assertTrue(out.readText().contains("a.Main"))
            assertTrue(replies[3].containsKey("error"))
            assertEquals("-32601", replies[4]["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        } finally {
            dir.deleteRecursively()
        }
    }
    @Test fun `all nonzero command results are machine readable failures`() {
        for (code in listOf(3, 137)) {
            val buffer = ByteArrayOutputStream()
            val input = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"inspect","arguments":{"apk":"/unused.apk"}}}"""
            Mcp.serveForTest(input.byteInputStream(), PrintStream(buffer)) { _, _, _ -> code to "no candidate" }
            val result = Json.parseToJsonElement(buffer.toString()).jsonObject["result"]!!.jsonObject
            assertEquals("true", result["isError"]!!.jsonPrimitive.content)
            val structured = result["structuredContent"]!!.jsonObject
            assertEquals(code.toString(), structured["exitCode"]!!.jsonPrimitive.content)
            assertEquals("failed", structured["status"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `cancel and ping are received while a long tool is running`() {
        val input = java.io.PipedInputStream()
        val writer = java.io.PipedOutputStream(input)
        val buffer = ByteArrayOutputStream()
        val started = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.CountDownLatch(1)
        val thread = Thread {
            Mcp.serveForTest(input, PrintStream(buffer)) { _, token, _ ->
                started.countDown()
                while (!token.isCancelled) Thread.sleep(5)
                cancelled.countDown()
                0 to "done"
            }
        }.apply { start() }
        fun send(s: String) { writer.write((s + "\n").toByteArray()); writer.flush() }
        try {
            send("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"inspect","arguments":{"apk":"/unused.apk"}}}""")
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            send("""{"jsonrpc":"2.0","id":2,"method":"ping"}""")
            send("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":1}}""")
            assertTrue(cancelled.await(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally { writer.close(); thread.join(5000) }
        assertTrue(!thread.isAlive)
        val replies = buffer.toString().lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }.associateBy { it["id"]!!.jsonPrimitive.content }
        assertEquals("-32800", replies.getValue("1")["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertTrue(replies.getValue("2").containsKey("result"))
    }

}
