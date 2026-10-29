package com.ebowwa.tab.core.data

import android.content.Context
import com.ebowwa.tab.core.model.Change
import com.ebowwa.tab.core.model.Ledger
import com.ebowwa.tab.core.model.applyingAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Ledger + undo history as one persisted document.
 *
 * The undo stack is stored *with* the ledger on purpose. Keeping it in memory only meant a
 * commit survived process death while its undo snapshot did not, so "one tap of Undo"
 * quietly became false after the app was killed.
 */
@Serializable
private data class PersistedState(
    val ledger: Ledger = Ledger(),
    val undo: List<Ledger> = emptyList(),
)

private data class Loaded(val ledger: Ledger, val undo: List<Ledger>, val error: String?)

/**
 * Single source of truth for the ledger.
 *
 * Every mutation goes through [commit], which snapshots the previous ledger onto the undo
 * stack first. Nothing a model produces reaches [commit] directly: the agent and the
 * AppFunctions service both only [stage], and a human has to [applyRendered].
 */
class LedgerStore private constructor(context: Context) {

    private val dir = context.applicationContext.filesDir
    private val file = File(dir, "ledger.json")
    private val tmp = File(dir, "ledger.json.tmp")
    private val backup = File(dir, "ledger.json.bak")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val loaded = read()

    private val _ledger = MutableStateFlow(loaded.ledger)
    val ledger: StateFlow<Ledger> = _ledger.asStateFlow()

    private val undoStack = ArrayDeque<Ledger>().apply { addAll(loaded.undo) }

    /** Non-null when the last read or write failed; the UI surfaces this instead of lying. */
    private val _storageError = MutableStateFlow(loaded.error)
    val storageError: StateFlow<String?> = _storageError.asStateFlow()

    /**
     * Proposed-but-uncommitted changes. Shared by every surface: the in-app agent and the
     * AppFunctions service both stage here, and only the UI's Apply commits them.
     */
    private val _staged = MutableStateFlow<List<Change>>(emptyList())
    val staged: StateFlow<List<Change>> = _staged.asStateFlow()

    val canUndo: Boolean
        @Synchronized get() = undoStack.isNotEmpty()

    // ---------------------------------------------------------------- staging

    @Synchronized
    fun stage(changes: List<Change>) {
        if (changes.isEmpty()) return
        _staged.value = _staged.value + changes
    }

    @Synchronized
    fun clearStaged() {
        _staged.value = emptyList()
    }

    /**
     * Commits exactly [rendered] — the changes the user actually saw — and leaves anything
     * staged afterwards in the queue for a later review.
     *
     * Deliberately not "commit the queue": the AppFunctions service can stage from another
     * thread at any moment, and committing the whole queue would apply changes that appeared
     * after the screen was drawn and were never displayed.
     */
    @Synchronized
    fun applyRendered(rendered: List<Change>): Boolean {
        if (rendered.isEmpty()) return false
        val remaining = _staged.value.toMutableList()
        rendered.forEach { remaining.remove(it) }
        _staged.value = remaining
        commit(rendered)
        return true
    }

    /** Convenience for the case where the caller really does mean "everything staged". */
    @Synchronized
    fun commitStaged(): Boolean = applyRendered(_staged.value)

    // ---------------------------------------------------------------- mutation

    @Synchronized
    fun commit(changes: List<Change>): Ledger {
        if (changes.isEmpty()) return _ledger.value
        val before = _ledger.value

        // Re-validate against the ledger as it is *now*, not as it was when the model saw it.
        // A staged delete for an expense that is already gone is a no-op that would otherwise
        // push an undo point and make Undo appear to work when it does nothing.
        val valid = changes.filter { change ->
            when (change) {
                is Change.Add -> true
                is Change.Update -> before.expenses.any { it.id == change.expense.id }
                is Change.Delete -> before.expenses.any { it.id == change.expense.id }
            }
        }
        if (valid.isEmpty()) return before

        val after = before.applyingAll(valid)
        if (after == before) return before

        undoStack.addLast(before)
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        persist(after)
        _ledger.value = after
        return after
    }

    @Synchronized
    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        persist(previous)
        _ledger.value = previous
        return true
    }

    @Synchronized
    fun reset(ledger: Ledger) {
        val before = _ledger.value
        if (before != ledger) {
            undoStack.addLast(before)
            while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        }
        _staged.value = emptyList()
        persist(ledger)
        _ledger.value = ledger
    }

    // ---------------------------------------------------------------- persistence

    private fun read(): Loaded {
        for (candidate in listOf(file, backup)) {
            if (!candidate.exists()) continue
            val text = runCatching { candidate.readText() }.getOrNull() ?: continue
            val parsed = runCatching { json.decodeFromString<PersistedState>(text) }.getOrNull()
            if (parsed != null) {
                return Loaded(parsed.ledger, parsed.undo.takeLast(MAX_UNDO), null)
            }
        }
        return Loaded(
            ledger = Ledger(),
            undo = emptyList(),
            error = if (file.exists() || backup.exists()) {
                "The saved ledger could not be read. The file has been left on disk untouched."
            } else {
                null
            },
        )
    }

    /**
     * Atomic-ish write: write a temp file, keep the previous document as a backup, then
     * rename over the original. A kill mid-write can no longer truncate the ledger, and a
     * failure is reported instead of silently discarding every expense.
     */
    private fun persist(ledger: Ledger) {
        val payload = json.encodeToString(PersistedState(ledger, undoStack.toList()))
        val result = runCatching {
            tmp.writeText(payload)
            if (file.exists()) file.copyTo(backup, overwrite = true)
            if (!tmp.renameTo(file)) {
                file.writeText(payload)
                tmp.delete()
            }
        }
        _storageError.value = result.exceptionOrNull()?.let { "Could not save the ledger: ${it.message}" }
    }

    companion object {
        private const val MAX_UNDO = 20

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
