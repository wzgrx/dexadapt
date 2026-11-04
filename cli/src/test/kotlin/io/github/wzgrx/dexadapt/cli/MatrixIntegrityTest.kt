package io.github.wzgrx.dexadapt.cli

import io.github.wzgrx.dexadapt.*
import io.github.wzgrx.dexadapt.resolve.SymbolMargin
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class MatrixIntegrityTest {
    @Test fun `same version different code is rejected before overwrite`() {
        val guard=MatrixIdentityGuard();guard.accept(1,"pkg|CN_1|hash-a","first.apk");guard.accept(1,"pkg|CN_1|hash-a","copy.apk")
        try{guard.accept(1,"pkg|CN_1|hash-b","different.apk");fail()}catch(e:IllegalArgumentException){assertTrue(e.message!!.contains("拒绝覆盖"))}
    }
    @Test fun `cache has complete margins and corruption is a miss`() {
        val dir=kotlin.io.path.createTempDirectory("matrix-cache").toFile()
        try {
            val table=SymbolTable(1,"dex",mapOf("class" to Resolved.Class("A")))
            val rows=listOf(SymbolMargin("class",listOf(SymbolMargin.Drop("strings[x]",1),SymbolMargin.Drop("methods[0]",4))))
            val key=MatrixCache.hash("spec|engine|pkg|1|dex");MatrixCache.write(dir,key,table,rows)
            assertEquals(table to rows,MatrixCache.read(dir,key));assertNull(MatrixCache.read(dir,MatrixCache.hash("other-spec")))
            val f=File(dir,"$key.json");f.writeText(f.readText().replace("methods[0]","methods[1]"));assertNull(MatrixCache.read(dir,key))
        } finally { dir.deleteRecursively() }
    }
}
