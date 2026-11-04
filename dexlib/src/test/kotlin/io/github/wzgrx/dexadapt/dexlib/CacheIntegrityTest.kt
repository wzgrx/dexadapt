package io.github.wzgrx.dexadapt.dexlib

import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CacheIntegrityTest {
    private lateinit var dir:File
    private val keys=listOf("dexadapt.cache.dir","dexadapt.cache.maxEntries","dexadapt.cache.maxBytes")
    private lateinit var old:Map<String,String?>
    @Before fun setup(){dir=kotlin.io.path.createTempDirectory("dex-cache-").toFile();old=keys.associateWith(System::getProperty);System.setProperty(keys[0],dir.path);System.clearProperty(keys[1]);System.clearProperty(keys[2])}
    @After fun cleanup(){old.forEach { (k,v)->if(v==null)System.clearProperty(k) else System.setProperty(k,v) };dir.deleteRecursively()}
    @Test fun `eleven package workset survives and explicit budgets evict oldest`() {
        repeat(12){i->IndexCache.write("key$i",DexIndex.assemble(emptyList(),"dex$i"));File(dir,"key$i.dxi").setLastModified(1000L+i)}
        assertEquals(12,dir.listFiles()!!.count { it.extension=="dxi" })
        System.setProperty(keys[1],"3");IndexCache.write("new",DexIndex.assemble(emptyList(),"new"))
        assertEquals(3,dir.listFiles()!!.count { it.extension=="dxi" });assertFalse(File(dir,"key0.dxi").exists());assertTrue(File(dir,"new.dxi").exists())
        System.setProperty(keys[2],"1");IndexCache.write("too-big",DexIndex.assemble(emptyList(),"new"));assertEquals(0,dir.listFiles()!!.count { it.extension=="dxi" })
    }
    @Test fun `corrupt payload and wrong code identity cannot be reused`() {
        IndexCache.write("good",DexIndex.assemble(emptyList(),"expected"));assertNotNull(IndexCache.read("good","expected"));assertNull(IndexCache.read("good","other"))
        IndexCache.write("corrupt",DexIndex.assemble(emptyList(),"expected"));val f=File(dir,"corrupt.dxi");val bytes=f.readBytes();bytes[8]=(bytes[8].toInt() xor 1).toByte();f.writeBytes(bytes)
        assertNull(IndexCache.read("corrupt"));assertFalse(f.exists())
    }
    @Test fun `code content identifies cache while repackaging does not`() {
        fun apk(name:String,dex:ByteArray,extra:String):File=File(dir,name).also { f->ZipOutputStream(f.outputStream()).use { z->
            z.putNextEntry(ZipEntry("classes.dex"));z.write(dex);z.closeEntry();z.putNextEntry(ZipEntry("META-INF/test"));z.write(extra.toByteArray());z.closeEntry()
        } }
        val a=apk("a.apk",byteArrayOf(1,2,3),"old");val b=apk("b.apk",byteArrayOf(1,2,3),"new");val c=apk("c.apk",byteArrayOf(1,2,4),"old")
        val regex=Regex("classes\\d*\\.dex");assertEquals(IndexCache.key(a,regex),IndexCache.key(b,regex));assertNotEquals(IndexCache.key(a,regex),IndexCache.key(c,regex))
    }
}
