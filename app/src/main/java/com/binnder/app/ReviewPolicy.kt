package com.binnder.app

object ReviewPolicy {
    const val UNDO_MS = 5_000L
    const val KEEP_MS = 7L * 24 * 60 * 60 * 1_000
    const val TRASH_MS = 30L * 24 * 60 * 60 * 1_000
    fun isEligible(keptAt: Long?, now: Long) = keptAt == null || now - keptAt >= KEEP_MS
    fun isExpired(trashedAt: Long, now: Long) = now >= trashedAt + TRASH_MS
    fun canUndo(due: Long, now: Long) = now < due
    fun undoRemaining(elapsedDue: Long, queuedBoot: Int, currentBoot: Int, elapsedNow: Long): Long =
        if(queuedBoot == currentBoot) (elapsedDue-elapsedNow).coerceAtLeast(0)
        else (UNDO_MS-elapsedNow).coerceAtLeast(0)
    fun validRepository(value: String) = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(value) &&
        value.split('/').none { it == "." || it == ".." }
}
