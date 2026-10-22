package com.binnder.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

data class ReadyUpdate(val version: String, val code: Long, val apk: File)

class UpdateRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("updates",Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var lastAttempt: Long? = null
    private var lastAttemptRepo = ""
    var repository: String
        get() = UpdatePolicy.repository(prefs.getString("repo",null),BuildConfig.UPDATE_REPOSITORY)
        set(value) {
            require(value.isEmpty() || ReviewPolicy.validRepository(value)) { "Enter a repository as owner/repository." }
            prefs.edit().putString("repo",value).remove("ready_code").remove("ready_version").apply()
        }
    var automatic: Boolean
        get() = prefs.getBoolean("automatic",true)
        set(value) { prefs.edit().putBoolean("automatic",value).apply() }
    val status: String get() = prefs.getString("status","No update check yet.") ?: ""
    @Suppress("DEPRECATION")
    private fun installedCode() = context.packageManager.getPackageInfo(context.packageName,0).longVersionCode
    fun ready(): ReadyUpdate? {
        val code = prefs.getLong("ready_code",0)
        val file = File(context.cacheDir,"updates/binnder-$code.apk")
        return if(code > installedCode() && file.isFile) ReadyUpdate(prefs.getString("ready_version","") ?: "",code,file) else null
    }
    suspend fun check(force: Boolean = true): ReadyUpdate? = withContext(Dispatchers.IO) { mutex.withLock {
        val repo = repository
        check(ReviewPolicy.validRepository(repo)) { "Save a public GitHub repository first." }
        val elapsed=android.os.SystemClock.elapsedRealtime()
        if(!force && !UpdatePolicy.shouldCheck(repo,lastAttemptRepo,lastAttempt,elapsed)) return@withLock ready()
        lastAttempt=elapsed;lastAttemptRepo=repo
        try {
            val release = JSONObject(get("https://api.github.com/repos/$repo/releases/latest", 2 * 1024 * 1024).decodeToString())
            check(!release.optBoolean("draft") && !release.optBoolean("prerelease")) { "No stable release is available." }
            val assets = release.getJSONArray("assets")
            val urls = buildMap<String,String> {
                for(i in 0 until assets.length()) { val a = assets.getJSONObject(i); put(a.getString("name"),a.getString("browser_download_url")) }
            }
            val manifestUrl = urls["update.json"] ?: error("This release has no update.json. Check release setup.")
            validateReleaseUrl(manifestUrl,repo)
            val manifest = JSONObject(get(manifestUrl,64 * 1024).decodeToString())
            val code = manifest.getLong("versionCode")
            if(code <= installedCode()) {
                prefs.edit().putString("status","You’re up to date.").apply(); return@withLock null
            }
            val apkUrl = urls["binnder.apk"] ?: error("The release APK is missing.")
            validateReleaseUrl(apkUrl,repo)
            val expected = manifest.getString("sha256").lowercase()
            check(Regex("[a-f0-9]{64}").matches(expected)) { "Invalid release checksum." }
            val dir = File(context.cacheDir,"updates").apply { mkdirs() }
            val target = File(dir,"binnder-$code.apk")
            if(!target.exists() || sha256(target) != expected) {
                val partial = File(dir,"download.part")
                try {
                    connection(apkUrl).let { connection ->
                        try {
                            check(connection.responseCode == 200) { "GitHub returned ${connection.responseCode}. Try again later." }
                            check(connection.contentLengthLong <= 300L * 1024 * 1024) { "Update is too large." }
                            connection.inputStream.use { input -> partial.outputStream().use { out ->
                                val buffer = ByteArray(64 * 1024); var total = 0L
                                while(true) { val count = input.read(buffer); if(count < 0) break
                                    total += count; check(total <= 300L * 1024 * 1024) { "Update exceeds download limit." }; out.write(buffer,0,count) }
                            } }
                        } finally { connection.disconnect() }
                    }
                    check(sha256(partial) == expected) { "Checksum did not match. The download was discarded." }
                    verifyApk(partial,code)
                    check(partial.renameTo(target)) { "Could not save the update." }
                } finally { partial.delete() }
            }
            verifyApk(target,code)
            // Ignore downloads from a repository changed while the request was running.
            check(repository == repo) { "Repository changed. Check for updates again." }
            val version = manifest.getString("versionName")
            val notify=prefs.getLong("ready_code",0) != code
            prefs.edit().putLong("ready_code",code).putString("ready_version",version).putString("status","Version $version is ready to install.").apply()
            dir.listFiles()?.filter { it != target }?.forEach { it.delete() }
            if(notify) notifyReady(version)
            ReadyUpdate(version,code,target)
        } catch(e: Exception) {
            prefs.edit().putString("status",e.message ?: "Update check failed. Try again.").apply(); throw e
        }
    } }
    @Suppress("DEPRECATION")
    private fun verifyApk(file: File, code: Long) {
        val pm = context.packageManager
        val apk = pm.getPackageArchiveInfo(file.path,PackageManager.GET_SIGNING_CERTIFICATES) ?: error("The update is not a valid APK.")
        val installed = pm.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        check(apk.packageName == context.packageName && apk.longVersionCode == code && code > installed.longVersionCode) { "Update identity or version did not match." }
        val current = installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        val incoming = apk.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        check(current.isNotEmpty() && current == incoming) { "The update uses a different signing key. It was not installed." }
    }
    fun installIntent(update: ReadyUpdate): Intent {
        verifyApk(update.apk,update.code)
        return Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(context,"${context.packageName}.files",update.apk),"application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    private fun validateReleaseUrl(url: String, repo: String) {
        check(UpdatePolicy.trustedAsset(url,repo)) { "Unexpected release download URL." }
    }
    private fun connection(url: String): HttpURLConnection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000; readTimeout = 30_000
        setRequestProperty("User-Agent","Binnder/${BuildConfig.VERSION_NAME}")
        setRequestProperty("Accept","application/vnd.github+json")
    }
    private fun get(url: String, limit: Int): ByteArray = connection(url).let { c ->
        try {
            check(c.responseCode == 200) { "GitHub returned ${c.responseCode}. Check the repository and try again." }
            c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while(true) {
                    val count=input.read(buffer)
                    if(count < 0) break
                    check(out.size()+count <= limit) { "GitHub response was too large." }
                    out.write(buffer,0,count)
                }
                out.toByteArray()
            }
        } finally { c.disconnect() }
    }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64*1024)
            while(true) { val count = input.read(buffer); if(count < 0) break; digest.update(buffer,0,count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun notifyReady(version: String) = runCatching {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("updates","App updates",NotificationManager.IMPORTANCE_DEFAULT))
        val intent = PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java).putExtra("open_settings",true),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(1,NotificationCompat.Builder(context,"updates").setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Binnder update ready").setContentText("Install version $version in Settings.").setContentIntent(intent).setAutoCancel(true).build())
    }
}
