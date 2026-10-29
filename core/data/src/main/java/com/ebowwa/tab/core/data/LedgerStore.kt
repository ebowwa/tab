package com.ebowwa.tab.core.data

import android.content.Context
import com.ebowwa.tab.core.model.Change
import com.ebowwa.tab.core.model.Ledger
import com.ebowwa.tab.core.model.applyingAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Single source of truth for the ledger.
 *
 * Every mutation goes through [commit], which snapshots the previous ledger onto an
 * undo stack first. That is the whole safety story: an agent can propose a batch of
 * changes, the user approves it, and one tap puts the ledger back exactly as it was.
 */
class LedgerStore private constructor(context: Context) {

    private val file = File(context.applicationContext.filesDir, "ledger.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _ledger = MutableStateFlow(read())
    val ledger: StateFlow<Ledger> = _ledger.asStateFlow()

    private val undoStack = ArrayDeque<Ledger>()

    /**
     * Proposed-but-uncommitted changes. Shared by every surface: the in-app agent and the
     * AppFunctions service both stage here, and only the UI's Apply commits them.
     */
    private val _staged = MutableStateFlow<List<Change>>(emptyList())
    val staged: StateFlow<List<Change>> = _staged.asStateFlow()

    val canUndo: Boolean
        @Synchronized get() = undoStack.isNotEmpty()

    @Synchronized
    fun commit(changes: List<Change>): Ledger {
        if (changes.isEmpty()) return _ledger.value
        val before = _ledger.value
        val after = before.applyingAll(changes)
        undoStack.addLast(before)
        while (undoStack.size > 20) undoStack.removeFirst()
        write(after)
        _ledger.value = after
        return after
    }

    @Synchronized
    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        write(previous)
        _ledger.value = previous
        return true
    }

    @Synchronized
    fun stage(changes: List<Change>) {
        if (changes.isEmpty()) return
        _staged.value = _staged.value + changes
    }

    @Synchronized
    fun clearStaged() {
        _staged.value = emptyList()
    }

    @Synchronized
    fun commitStaged(): Boolean {
        val pending = _staged.value
        if (pending.isEmpty()) return false
        commit(pending)
        _staged.value = emptyList()
        return true
    }

    @Synchronized
    fun reset(ledger: Ledger) {
        undoStack.clear()
        write(ledger)
        _ledger.value = ledger
    }

    private fun read(): Ledger = runCatching {
        if (file.exists()) json.decodeFromString<Ledger>(file.readText()) else Ledger()
    }.getOrElse { Ledger() }

    private fun write(ledger: Ledger) {
        runCatching { file.writeText(json.encodeToString(ledger)) }
    }

    companion object {
        @Volatile
        private var instance: LedgerStore? = null

        /**
         * One store per process. The AppFunctions service and the Activity must see the
         * same ledger and the same staging queue, or Gemini's proposals vanish.
         */
        fun get(context: Context): LedgerStore =
            instance ?: synchronized(this) {
                instance ?: LedgerStore(context.applicationContext).also { instance = it }
            }
    }
}
