package com.binnder.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppState(val files: List<PhoneFile> = emptyList(), val operations: List<Operation> = emptyList(),
    val scanning: Boolean = false, val actionBusy: Boolean = false, val access: Boolean = false,
    val scanned: Boolean = false, val unreadable: Int = 0, val freed: Long = 0,
    val now: Long = System.currentTimeMillis(), val message: String? = null,
    val checkingUpdate: Boolean = false, val updateStatus: String = "", val ready: ReadyUpdate? = null)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BinnderApplication
    val storage = app.storage
    val updates = app.updates
    private val mutable = MutableStateFlow(AppState(access=storage.hasAccess(),updateStatus=updates.status,ready=updates.ready()))
    val state = mutable.asStateFlow()
    private var resuming = false
    init {
        viewModelScope.launch {
            while(true) {
                val now = System.currentTimeMillis()
                if(state.value.operations.any { it.state in listOf("pending","purge_pending") } || now-state.value.now >= 60_000) {
                    mutable.update { it.copy(now=now) }
                }
                if(state.value.operations.any { it.state in listOf("pending","purge_pending") && it.undoRemainingMs() == 0L }) {
                    val errors = storage.process(); refresh()
                    if(errors.isNotEmpty()) message(errors.joinToString("\n"))
                }
                delay(200)
            }
        }
    }
    fun resume() {
        if(resuming) return
        resuming=true
        val hadAccess = state.value.access
        mutable.update { it.copy(access=storage.hasAccess(),ready=updates.ready(),updateStatus=updates.status) }
        viewModelScope.launch {
            try {
                val errors = storage.process(); refresh()
                if(errors.isNotEmpty()) message(errors.joinToString("\n"))
                if(storage.hasAccess() && (!state.value.scanned || !hadAccess)) scan()
                else if(storage.hasAccess() && !state.value.scanning) {
                    val snapshot=state.value.files
                    runCatching { storage.pruneReviewFiles(snapshot) }.onSuccess { reviewable ->
                        val rejected=(snapshot.map { it.key }.toSet()-reviewable.map { it.key }.toSet())
                        mutable.update { it.copy(files=it.files.filterNot { f -> f.key in rejected }) }
                    }.onFailure { message(it.message ?: "Could not refresh recycle-bin status. Scan again.") }
                }
            } finally { resuming=false }
        }
        if(updates.automatic && ReviewPolicy.validRepository(updates.repository)) startUpdateCheck(force=false,silent=true)
    }
    fun scan() {
        if(state.value.scanning) return
        mutable.update { it.copy(scanning=true,message=null) }
        viewModelScope.launch {
            runCatching { storage.scan() }.onSuccess { (files,unreadable) ->
                mutable.update { it.copy(files=files,unreadable=unreadable,scanned=true) }
            }.onFailure { message(it.message ?: "Could not scan files. Try again.") }
            mutable.update { it.copy(scanning=false) }; refresh()
        }
    }
    fun keep(file: PhoneFile) = act {
        storage.keep(file); mutable.update { it.copy(files=it.files.filterNot { f -> f.key == file.key }) }
    }
    fun delete(file: PhoneFile, permanent: Boolean) = act {
        val op = storage.queue(file,permanent)
        CleanupWorker.schedule(app,op.id)
        mutable.update { it.copy(files=it.files.filterNot { f -> f.key == file.key }) }
    }
    fun undo(op: Operation) = act {
        if(op.state == "purge_pending") storage.undoPurge(op.id) else {
            storage.undo(op.id)
            if(op.file.unchanged()) mutable.update { it.copy(files=(it.files+op.file).distinctBy { f -> f.key }.sortedByDescending { f -> f.size }) }
        }
    }
    fun restore(op: Operation) = act { storage.restore(op.id); message("${op.file.name} restored to its original folder.") }
    fun purge(op: Operation) = act { storage.queuePurge(op.id); CleanupWorker.schedule(app,"purge-${op.id}") }
    fun message(text: String?) { mutable.update { it.copy(message=text) } }
    fun saveRepository(value: String) {
        runCatching { updates.repository=value }.onSuccess {
            mutable.update { it.copy(ready=null,updateStatus="Repository saved. Check for updates when ready.") }
            if(updates.automatic && ReviewPolicy.validRepository(updates.repository)) startUpdateCheck(force=false,silent=true)
        }.onFailure { message(it.message) }
    }
    private fun act(block: suspend () -> Unit) {
        if(state.value.actionBusy || state.value.scanning) return
        mutable.update { it.copy(actionBusy=true) }
        viewModelScope.launch {
            runCatching { block() }.onFailure { message(it.message ?: "Could not finish this action. The file was kept.") }
            refresh(); mutable.update { it.copy(actionBusy=false) }
        }
    }
    private suspend fun refresh() {
        val ops = storage.operations(); val freed = storage.freed()
        mutable.update { it.copy(operations=ops,freed=freed) }
    }
    fun checkUpdates() = startUpdateCheck(force=true,silent=false)
    private fun startUpdateCheck(force:Boolean,silent:Boolean) {
        if(state.value.checkingUpdate) return
        mutable.update { it.copy(checkingUpdate=true) }
        viewModelScope.launch {
            runCatching { updates.check(force=force) }.onFailure { if(!silent) message(it.message ?: "Could not check for updates.") }
            mutable.update { it.copy(checkingUpdate=false,updateStatus=updates.status,ready=updates.ready()) }
        }
    }
}
