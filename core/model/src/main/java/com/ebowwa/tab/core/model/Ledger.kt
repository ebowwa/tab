package com.ebowwa.tab.core.model

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class Expense(
    val id: String,
    val description: String,
    val amountCents: Long,
    val paidBy: String,
    val participants: List<String>,
    val createdAt: Long = 0L,
)

@Serializable
data class Ledger(
    val members: List<String> = DEFAULT_MEMBERS,
    val expenses: List<Expense> = emptyList(),
) {
    companion object {
        val DEFAULT_MEMBERS = listOf("You", "Ana", "Ben", "Cy")
    }
}

/** A single proposed mutation. Nothing is written until a batch is committed. */
sealed interface Change {
    val label: String

    data class Add(val expense: Expense) : Change {
        override val label: String get() = describe("Add", expense)
    }

    data class Update(val expense: Expense) : Change {
        override val label: String get() = describe("Update", expense)
    }

    data class Delete(val expense: Expense) : Change {
        override val label: String get() = describe("Delete", expense)
    }


}

/**
 * The approval preview has to show what will actually change. An earlier version showed only
 * "Add Dinner — $84.00", which hid the payer and the split — the two fields most likely to
 * be wrong.
 */
private fun describe(verb: String, e: Expense): String =
    "$verb ${e.description} — ${e.amountCents.money()} · ${e.paidBy} paid · " +
        "split ${e.participants.joinToString(", ")}"

/** Apply one change to a ledger, returning the new ledger. */
fun Ledger.applying(change: Change): Ledger = when (change) {
    is Change.Add -> copy(expenses = expenses + change.expense)
    is Change.Update -> copy(expenses = expenses.map { if (it.id == change.expense.id) change.expense else it })
    is Change.Delete -> copy(expenses = expenses.filterNot { it.id == change.expense.id })
}

fun Ledger.applyingAll(changes: List<Change>): Ledger = changes.fold(this) { acc, c -> acc.applying(c) }

/**
 * Net position per member in cents. Positive = is owed money, negative = owes money.
 * Shares are split as evenly as possible; leftover cents go to the first participants.
 */
fun Ledger.balances(): Map<String, Long> {
    val net = members.associateWith { 0L }.toMutableMap()
    for (e in expenses) {
        // Dedupe here, not at the call sites: a duplicated participant must not be charged twice.
        val parts = e.participants.ifEmpty { listOf(e.paidBy) }.distinct()
        net[e.paidBy] = (net[e.paidBy] ?: 0L) + e.amountCents
        val base = e.amountCents / parts.size
        var remainder = e.amountCents - base * parts.size
        // Distribute by sign. With a negative amount, truncating division leaves a negative
        // remainder, and a `> 0` test would silently drop it and break the zero-sum invariant.
        val step = if (remainder >= 0) 1L else -1L
        for (p in parts) {
            var share = base
            if (remainder != 0L) {
                share += step
                remainder -= step
            }
            net[p] = (net[p] ?: 0L) - share
        }
    }
    return net
}

fun Ledger.totalCents(): Long = expenses.sumOf { it.amountCents }

fun Long.money(): String {
    val sign = if (this < 0) "-" else ""
    val abs = kotlin.math.abs(this)
    return String.format(Locale.US, "%s$%d.%02d", sign, abs / 100, abs % 100)
}
