package io.github.wzgrx.dexadapt.cli

import io.github.wzgrx.dexadapt.*
import io.github.wzgrx.dexadapt.resolve.SymbolMargin
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class MatrixIntegrityTest {
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
    @Test fun `same numeric version keeps independent package and variant columns`() {
        val a=io.github.wzgrx.dexadapt.matrix.Build("CN_9161100","cn",9161100,"9.16.1","tv.danmaku.bili","hash-a")
        val b=io.github.wzgrx.dexadapt.matrix.Build("PLAY_9161100","play",9161100,"6.7.1","com.bilibili.app.in","hash-b")
        val c=a.copy(codeIdentity="hash-c")
        val tables=listOf(a,b,c).associateWith { SymbolTable(it.versionCode,it.codeIdentity,mapOf("x" to Resolved.Class(it.codeIdentity))) }
        val rows=listOf(a,b,c).associateWith { listOf(SymbolMargin("x",listOf(SymbolMargin.Drop("strings[${it.codeIdentity}]",2)))) }
        val markdown=io.github.wzgrx.dexadapt.matrix.Matrix.renderByBuild(tables,rows)
        val html=io.github.wzgrx.dexadapt.matrix.Matrix.htmlByBuild(tables,rows)
        assertEquals(3,tables.size)
        for(build in tables.keys) { assertTrue(markdown.contains(build.packageName));assertTrue(markdown.contains(build.codeIdentity));assertTrue(html.contains(build.codeIdentity)) }
    }

}
