package com.binnder.app

import java.util.Locale

/** Shared by discovery, resume pruning, and the final review-action guard. */
object ScanPolicy {
    private val binNames = setOf(
        ".binnder-trash", ".trash", ".trashes", ".trashbin", ".trash-bin", ".trash_bin",
        ".recycle", ".recyclebin", ".recycle-bin", ".recycle_bin", ".recycler",
        ".samsungrecycle", ".globaltrash", "\$recycle.bin", "recycler", "recycled",
        "trash", "recycle bin", "recyclebin"
    )
    fun isBinPath(relativePath: String): Boolean = relativePath.split('/').any { segment ->
        val name = segment.lowercase(Locale.ROOT)
        name in binNames || name.startsWith(".trashed-") ||
            (name.startsWith(".trash-") && name.removePrefix(".trash-").let { it.isNotEmpty() && it.all(Char::isDigit) })
    }
    fun isReviewable(path: String, relativePath: String, trashedPaths: Set<String>, activePaths: Set<String>): Boolean =
        !isBinPath(relativePath) && path !in trashedPaths && path !in activePaths
}
