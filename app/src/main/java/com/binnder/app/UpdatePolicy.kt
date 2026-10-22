package com.binnder.app

import java.net.URI

object UpdatePolicy {
    const val CHECK_INTERVAL_MS = 5L * 60 * 1000
    fun repository(saved: String?, builtIn: String): String =
        saved?.trim()?.takeIf { it.isNotEmpty() } ?: builtIn.trim()
    fun shouldCheck(repo: String, previousRepo: String, previousAttempt: Long?, elapsedNow: Long): Boolean =
        previousAttempt == null || repo != previousRepo || elapsedNow < previousAttempt ||
            elapsedNow-previousAttempt >= CHECK_INTERVAL_MS
    fun trustedAsset(url: String, repo: String): Boolean = runCatching {
        val uri=URI(url)
        ReviewPolicy.validRepository(repo) && uri.scheme == "https" && uri.host.equals("github.com",ignoreCase=true) &&
            uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
            uri.rawPath.startsWith("/$repo/releases/download/",ignoreCase=true)
    }.getOrDefault(false)
}
