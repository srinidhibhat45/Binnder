package com.binnder.app

import java.io.File
import java.nio.file.Files

/** No fallback copies or recursive deletions. A failed operation leaves its source intact. */
object SafeDisk {
    fun move(source: File, destination: File, matches: (File) -> Boolean) {
        check(matches(source)) { "The file changed. Operation cancelled." }
        check(!destination.exists()) { "A file already exists at the destination." }
        Files.move(source.toPath(),destination.toPath()) // Deliberately omit REPLACE_EXISTING.
    }
    fun delete(source: File, matches: (File) -> Boolean) {
        check(matches(source)) { "The file changed. Permanent deletion cancelled." }
        check(source.delete()) { "The file could not be deleted." }
    }
}
