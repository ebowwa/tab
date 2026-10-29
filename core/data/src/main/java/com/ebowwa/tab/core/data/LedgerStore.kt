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
class LedgerStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "ledger.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _ledger = MutableStateFlow(read())
    val ledger: StateFlow<Ledger> = _ledger.asStateFlow()

    private val undoStack = ArrayDeque<Ledger>()

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
}
