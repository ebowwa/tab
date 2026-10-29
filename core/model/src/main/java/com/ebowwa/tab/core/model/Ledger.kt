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
        override val label: String get() = "Add ${expense.description} — ${expense.amountCents.money()}"
    }

    data class Update(val expense: Expense) : Change {
        override val label: String get() = "Update ${expense.description} — ${expense.amountCents.money()}"
    }

    data class Delete(val expense: Expense) : Change {
        override val label: String get() = "Delete ${expense.description}"
    }
}

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
        val parts = e.participants.ifEmpty { listOf(e.paidBy) }
        net[e.paidBy] = (net[e.paidBy] ?: 0L) + e.amountCents
        val base = e.amountCents / parts.size
        var remainder = e.amountCents - base * parts.size
        for (p in parts) {
            var share = base
            if (remainder > 0) {
                share += 1
                remainder -= 1
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
