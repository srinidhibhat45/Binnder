package com.binnder.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.storage.StorageManager
import android.os.Bundle
import android.content.ContentResolver
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

enum class FileKind(val label: String) { ALL("All files"), IMAGE("Images"), VIDEO("Videos"), AUDIO("Audio"), PDF("PDFs"), OTHER("Other") }

data class PhoneFile(val path: String, val name: String, val size: Long, val modified: Long,
    val device: Long, val inode: Long, val volume: String, val mime: String) {
    val kind: FileKind get() = when {
        mime.startsWith("image/") -> FileKind.IMAGE
        mime.startsWith("video/") -> FileKind.VIDEO
        mime.startsWith("audio/") -> FileKind.AUDIO
        mime == "application/pdf" -> FileKind.PDF
        else -> FileKind.OTHER
    }
    val key: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        MessageDigest.getInstance("SHA-256").digest(
            "$path\u0000$size\u0000$modified\u0000$device\u0000$inode".toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun unchanged(at: String = path): Boolean = runCatching {
        val f = File(at)
        val stat = Os.lstat(at)
        OsConstants.S_ISREG(stat.st_mode) && f.length() == size && f.lastModified() == modified &&
            stat.st_dev == device && stat.st_ino == inode
    }.getOrDefault(false)
    companion object {
        fun read(file: File, volume: File): PhoneFile {
            val stat = Os.lstat(file.path)
            check(OsConstants.S_ISREG(stat.st_mode))
            return PhoneFile(file.path, file.name, file.length(), file.lastModified(), stat.st_dev, stat.st_ino,
                volume.path, MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream")
        }
    }
}

data class Operation(val id: String, val file: PhoneFile, val permanent: Boolean, val state: String,
    val due: Long, val trashPath: String, val trashedAt: Long, val elapsedDue: Long = 0, val boot: Int = -1) {
    fun undoRemainingMs() = ReviewPolicy.undoRemaining(elapsedDue,boot,UndoClock.boot,UndoClock.elapsed())
}

class FileDatabase(context: Context) : SQLiteOpenHelper(context, "binnder.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE kept (identity TEXT PRIMARY KEY, kept_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE operations (id TEXT PRIMARY KEY, payload TEXT NOT NULL, permanent INTEGER NOT NULL, state TEXT NOT NULL, due INTEGER NOT NULL, trash_path TEXT NOT NULL, trashed_at INTEGER NOT NULL, elapsed_due INTEGER NOT NULL, boot INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE stats (id INTEGER PRIMARY KEY CHECK(id=1), freed INTEGER NOT NULL)")
        db.execSQL("INSERT INTO stats VALUES (1,0)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if(oldVersion < 2) {
            db.execSQL("ALTER TABLE operations ADD COLUMN elapsed_due INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE operations ADD COLUMN boot INTEGER NOT NULL DEFAULT -1")
        }
    }
    fun kept(): Map<String, Long> = readableDatabase.rawQuery("SELECT identity,kept_at FROM kept", null).use { c ->
        buildMap { while(c.moveToNext()) put(c.getString(0), c.getLong(1)) }
    }
    fun keep(file: PhoneFile, now: Long) { writableDatabase.execSQL("INSERT OR REPLACE INTO kept VALUES (?,?)", arrayOf<Any>(file.key, now)) }
    fun operations(): List<Operation> = readableDatabase.rawQuery("SELECT * FROM operations ORDER BY due DESC", null).use { c ->
        buildList {
            while(c.moveToNext()) {
                val p = org.json.JSONObject(c.getString(1))
                val f = PhoneFile(p.getString("path"),p.getString("name"),p.getLong("size"),p.getLong("modified"),p.getLong("device"),p.getLong("inode"),p.getString("volume"),p.getString("mime"))
                add(Operation(c.getString(0), f, c.getInt(2) == 1, c.getString(3), c.getLong(4), c.getString(5), c.getLong(6),c.getLong(7),c.getInt(8)))
            }
        }
    }
    fun insert(op: Operation) {
        val f = op.file
        val payload = org.json.JSONObject().put("path", f.path).put("name",f.name).put("size",f.size).put("modified",f.modified)
            .put("device",f.device).put("inode",f.inode).put("volume",f.volume).put("mime",f.mime).toString()
        writableDatabase.execSQL("INSERT INTO operations VALUES (?,?,?,?,?,?,?,?,?)", arrayOf<Any>(op.id,payload,if(op.permanent) 1 else 0,op.state,op.due,op.trashPath,op.trashedAt,op.elapsedDue,op.boot))
    }
    fun state(id: String, state: String, trashedAt: Long = 0L) { writableDatabase.execSQL("UPDATE operations SET state=?,trashed_at=? WHERE id=?", arrayOf<Any>(state,trashedAt,id)) }
    fun remove(id: String) { writableDatabase.execSQL("DELETE FROM operations WHERE id=?",arrayOf(id)) }
    fun queuePurge(id: String, due: Long) {
        writableDatabase.execSQL("UPDATE operations SET permanent=1,state='purge_pending',due=?,elapsed_due=?,boot=? WHERE id=?",arrayOf<Any>(due,UndoClock.elapsed()+ReviewPolicy.UNDO_MS,UndoClock.boot,id))
    }
    fun deleted(op: Operation, freed: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (freed) db.execSQL("UPDATE stats SET freed=freed+? WHERE id=1", arrayOf(op.file.size))
            remove(op.id)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun freed(): Long = readableDatabase.rawQuery("SELECT freed FROM stats WHERE id=1",null).use { it.moveToFirst(); it.getLong(0) }
}

class StorageRepository(private val context: Context) {
    private val db = FileDatabase(context)
    // All foreground and WorkManager mutations use this singleton's same mutex.
    private val mutex = Mutex()
    suspend fun operations() = withContext(Dispatchers.IO) { mutex.withLock { db.operations() } }
    suspend fun freed() = withContext(Dispatchers.IO) { db.freed() }
    fun hasAccess() = Environment.isExternalStorageManager()

    @Suppress("DEPRECATION")
    private fun readTrashedPaths(): Set<String> = buildSet {
        for(volume in MediaStore.getExternalVolumeNames(context)) {
            val args = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED,MediaStore.MATCH_ONLY)
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION,"${MediaStore.MediaColumns.IS_TRASHED}=1")
            }
            val cursor = context.contentResolver.query(MediaStore.Files.getContentUri(volume),
                arrayOf(MediaStore.MediaColumns.DATA),args,null)
                ?: error("Could not check Android’s recycle bin. Scan again before reviewing files.")
            cursor.use {
                while(it.moveToNext()) {
                    val path = it.getString(0) ?: continue
                    add(File(path).canonicalPath)
                }
            }
        }
    }
    private fun relativePath(file: PhoneFile) = File(file.path).relativeTo(File(file.volume)).invariantSeparatorsPath
    private fun inBin(file: PhoneFile, trashed: Set<String>) = ScanPolicy.isBinPath(relativePath(file)) || file.path in trashed

    suspend fun pruneReviewFiles(files: List<PhoneFile>): List<PhoneFile> = withContext(Dispatchers.IO) {
        if(!hasAccess()) return@withContext emptyList()
        val trashed = readTrashedPaths()
        val active = mutex.withLock { db.operations().flatMap { listOf(it.file.path,it.trashPath) }.toSet() }
        files.filter { ScanPolicy.isReviewable(it.path,relativePath(it),trashed,active) && it.unchanged() }
    }

    suspend fun scan(): Pair<List<PhoneFile>, Int> = withContext(Dispatchers.IO) {
        check(hasAccess()) { "Allow file access in Settings, then scan again." }
        val trashed = readTrashedPaths()
        val (kept, active) = mutex.withLock { db.kept() to db.operations().flatMap { listOf(it.file.path,it.trashPath) }.toSet() }
        val now = System.currentTimeMillis()
        val roots = context.getSystemService(StorageManager::class.java).storageVolumes.mapNotNull { it.directory }.distinctBy { it.canonicalPath }
        val found = mutableListOf<PhoneFile>()
        var unreadable = 0
        for(root in roots) {
            val stack = ArrayDeque<File>(); stack.add(root)
            while(stack.isNotEmpty()) {
                val dir = stack.removeLast()
                val entries = dir.listFiles()
                if(entries == null) { unreadable++; continue }
                for(file in entries) {
                    // Never follow symbolic links or scan Binnder's trash and Android's protected areas.
                    if (runCatching { file.canonicalPath != file.absolutePath }.getOrDefault(true)) continue
                    val relative = file.relativeTo(root).invariantSeparatorsPath
                    if(ScanPolicy.isBinPath(relative) || relative == "Android/data" || relative == "Android/obb") continue
                    if(file.isDirectory) { stack.add(file); continue }
                    if(file.name == ".nomedia" || !ScanPolicy.isReviewable(file.path,relative,trashed,active)) continue
                    val item = runCatching { PhoneFile.read(file,root) }.getOrNull() ?: continue
                    if(ReviewPolicy.isEligible(kept[item.key],now)) found.add(item)
                }
            }
        }
        found.sortedWith(compareByDescending<PhoneFile> { it.size }.thenBy { it.path }) to unreadable
    }
    suspend fun keep(file: PhoneFile) = withContext(Dispatchers.IO) { mutex.withLock {
        check(!inBin(file,readTrashedPaths())) { "This file is already in a recycle bin. Scan again to refresh your files." }
        check(file.unchanged()) { "This file changed. Scan again before reviewing it." }
        db.keep(file,System.currentTimeMillis())
    } }
    suspend fun queue(file: PhoneFile, permanent: Boolean): Operation = withContext(Dispatchers.IO) { mutex.withLock {
        check(hasAccess()) { "File access was revoked. Allow access and try again." }
        check(!inBin(file,readTrashedPaths())) { "This file is already in a recycle bin. Scan again to refresh your files." }
        check(file.unchanged()) { "This file changed. Scan again before deleting it." }
        check(db.operations().none { it.file.path == file.path }) { "This file already has a pending action." }
        val id = UUID.randomUUID().toString()
        val op = Operation(id,file,permanent,"pending",System.currentTimeMillis()+ReviewPolicy.UNDO_MS,File(file.volume,".Binnder-trash/$id").path,0,UndoClock.elapsed()+ReviewPolicy.UNDO_MS,UndoClock.boot)
        db.insert(op); op
    } }
    suspend fun undo(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val op = db.operations().find { it.id == id } ?: error("This action has already finished.")
        check(op.state == "pending" && op.undoRemainingMs() > 0) { "The undo window has ended." }
        db.remove(id)
    } }
    suspend fun restore(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val op = db.operations().find { it.id == id } ?: error("This file is no longer in the bin.")
        check(op.state == "trashed") { "This file cannot be restored yet." }
        val original = File(op.file.path)
        check(!original.exists()) { "A file already exists at the original location. Move or rename it first." }
        check(op.file.unchanged(op.trashPath)) { "The binned file changed or its storage is unavailable. It has been kept in the bin." }
        check(original.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "The original folder is unavailable." }
        db.state(id,"restoring",op.trashedAt)
        if(runCatching { SafeDisk.move(File(op.trashPath),original) { op.file.unchanged(it.path) } }.isFailure) {
            db.state(id,"trashed",op.trashedAt); error("Could not restore this file. It is still in the bin.")
        }
        // Avoid showing a newly restored file as the very next review card.
        db.keep(op.file,System.currentTimeMillis()); db.remove(id)
        notifyMedia(original.path)
    } }
    suspend fun queuePurge(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val op = db.operations().find { it.id == id } ?: error("This file is no longer in the bin.")
        check(op.state == "trashed")
        // Purge gets the same five-second undo guarantee, persisted across process death.
        db.queuePurge(id,System.currentTimeMillis()+ReviewPolicy.UNDO_MS)
    } }
    suspend fun undoPurge(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val op = db.operations().find { it.id == id } ?: error("This action has already finished.")
        check(op.state == "purge_pending" && op.undoRemainingMs() > 0) { "The undo window has ended." }
        db.state(id,"trashed",op.trashedAt)
    } }
    suspend fun process(): List<String> = withContext(Dispatchers.IO) { mutex.withLock {
        if(!hasAccess()) return@withLock emptyList()
        val now = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        val operations = db.operations()
        val trashed = if(operations.any { it.state == "pending" && it.undoRemainingMs() == 0L }) {
            runCatching { readTrashedPaths() }.getOrElse {
                errors.add("Could not verify recycle-bin status. Pending deletions have been held. Scan again to retry.")
                return@withLock errors
            }
        } else emptySet()
        for(op in operations) runCatching {
            // Storage may be removed. Never interpret an unmounted volume as successful deletion.
            if(!File(op.file.volume).isDirectory) return@runCatching
            when(op.state) {
                "pending" -> if(op.undoRemainingMs() == 0L) {
                    if(inBin(op.file,trashed)) { db.remove(op.id);error("${op.file.name} is already in a recycle bin; deletion cancelled.") }
                    if(!op.file.unchanged()) { db.remove(op.id); error("${op.file.name} changed or moved; deletion cancelled.") }
                    if(op.permanent) {
                        db.state(op.id,"deleting")
                        deleteChecked(op,op.file.path)
                    } else {
                        val target = File(op.trashPath)
                        check(target.parentFile!!.let { it.isDirectory || it.mkdirs() }) { "Could not create the recycle bin." }
                        check(target.parentFile!!.canonicalPath == target.parentFile!!.absolutePath) { "Recycle bin is not a regular folder. Original file kept." }
                        File(target.parentFile,".nomedia").createNewFile()
                        check(!target.exists())
                        db.state(op.id,"moving",now)
                        if(runCatching { SafeDisk.move(File(op.file.path),target) { op.file.unchanged(it.path) } }.isFailure) {
                            db.remove(op.id); error("Could not bin ${op.file.name}; the original file was kept.")
                        }
                        db.state(op.id,"trashed",now); notifyMedia(op.file.path)
                    }
                }
                "moving" -> {
                    if(op.file.unchanged(op.trashPath)) db.state(op.id,"trashed",op.trashedAt)
                    else if(op.file.unchanged()) db.remove(op.id)
                    else error("Could not reconcile ${op.file.name}; its record has been preserved.")
                }
                "restoring" -> {
                    if(op.file.unchanged()) { db.keep(op.file,now); db.remove(op.id); notifyMedia(op.file.path) }
                    else if(op.file.unchanged(op.trashPath)) db.state(op.id,"trashed",op.trashedAt)
                }
                "trashed" -> if(ReviewPolicy.isExpired(op.trashedAt,now)) {
                    db.state(op.id,"purging",op.trashedAt); deleteChecked(op,op.trashPath)
                }
                "purge_pending" -> if(op.undoRemainingMs() == 0L) {
                    db.state(op.id,"purging",op.trashedAt); deleteChecked(op,op.trashPath)
                }
                "deleting" -> if(File(op.file.path).exists()) deleteChecked(op,op.file.path) else db.deleted(op,false)
                "purging" -> if(File(op.trashPath).exists()) deleteChecked(op,op.trashPath) else db.deleted(op,false)
            }
        }.onFailure { errors.add(it.message ?: "File operation failed. Your remaining files were kept.") }
        errors
    } }
    private fun deleteChecked(op: Operation, path: String) {
        if(!op.file.unchanged(path)) {
            if(path == op.file.path) db.remove(op.id) else db.state(op.id,"trashed",op.trashedAt)
            error("${op.file.name} changed; permanent deletion was cancelled.")
        }
        if(runCatching { SafeDisk.delete(File(path)) { op.file.unchanged(it.path) } }.isFailure) {
            if(path == op.file.path) db.remove(op.id) else db.state(op.id,"trashed",op.trashedAt)
            throw IOException("Could not delete ${op.file.name}. The file was kept.")
        }
        db.deleted(op,true); notifyMedia(path)
    }
    private fun notifyMedia(path: String) { MediaScannerConnection.scanFile(context,arrayOf(path),null,null) }
}
