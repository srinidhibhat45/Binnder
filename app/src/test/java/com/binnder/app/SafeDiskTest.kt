package com.binnder.app

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SafeDiskTest {
    private fun inFolder(block: (File)->Unit) {
        val root=Files.createTempDirectory("binnder-test").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    @Test fun binAndRestorePreserveExactBytes() = inFolder { root ->
        val original=File(root,"photo.jpg").apply { writeBytes(ByteArray(4096) { (it%255).toByte() }) }
        val bytes=original.readBytes(); val trash=File(root,"trash-id")
        SafeDisk.move(original,trash) { it.readBytes().contentEquals(bytes) }
        assertFalse(original.exists()); assertArrayEquals(bytes,trash.readBytes())
        SafeDisk.move(trash,original) { it.readBytes().contentEquals(bytes) }
        assertArrayEquals(bytes,original.readBytes()); assertFalse(trash.exists())
    }
    @Test fun restoreNeverOverwritesAnExistingFile() = inFolder { root ->
        val original=File(root,"original").apply { writeText("new file") }
        val trash=File(root,"trash").apply { writeText("old file") }
        assertThrows(IllegalStateException::class.java) { SafeDisk.move(trash,original) { true } }
        assertEquals("new file",original.readText()); assertEquals("old file",trash.readText())
    }
    @Test fun changedFileCannotBeMoved() = inFolder { root ->
        val source=File(root,"source").apply { writeText("changed") }; val target=File(root,"target")
        assertThrows(IllegalStateException::class.java) { SafeDisk.move(source,target) { false } }
        assertEquals("changed",source.readText()); assertFalse(target.exists())
    }
    @Test fun changedFileCannotBePermanentlyDeleted() = inFolder { root ->
        val source=File(root,"source").apply { writeText("keep me") }
        assertThrows(IllegalStateException::class.java) { SafeDisk.delete(source) { false } }
        assertEquals("keep me",source.readText())
    }
    @Test fun failedMovePreservesOriginal() = inFolder { root ->
        val source=File(root,"source").apply { writeText("keep me") }
        assertThrows(java.io.IOException::class.java) { SafeDisk.move(source,File(root,"absent/target")) { true } }
        assertEquals("keep me",source.readText())
    }
    @Test fun matchingFileCanBeDeleted() = inFolder { root ->
        val source=File(root,"source").apply { writeText("delete me") }
        SafeDisk.delete(source) { it.readText()=="delete me" }
        assertFalse(source.exists())
    }
}
