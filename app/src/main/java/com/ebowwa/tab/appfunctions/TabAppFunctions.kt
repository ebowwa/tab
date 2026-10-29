package com.ebowwa.tab.appfunctions

import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import com.ebowwa.tab.core.data.LedgerStore
import com.ebowwa.tab.core.model.Change
import com.ebowwa.tab.core.model.Expense
import com.ebowwa.tab.core.model.balances
import com.ebowwa.tab.core.model.money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The AppFunctions entry point: how the *system* Gemini agent reaches Tab.
 *
 * Abstract on purpose. The AppFunctions KSP processor generates a concrete
 * `TabAppFunctionService` in this package that implements `onExecuteFunction` and routes
 * each function id to a method below. Never implement `onExecuteFunction` here — the
 * processor rejects that.
 *
 * Note what is deliberately absent: there is no function that commits. Every write here
 * only *stages* a change in the shared [LedgerStore]. Gemini can propose; the human still
 * has to open Tab and press Apply. That keeps the AppFunctions surface as untrusted as the
 * in-app model.
 */
@AppFunctionServiceEntryPoint(
    serviceName = "TabAppFunctionService",
    appFunctionXmlFileName = "tab_app_functions",
)
abstract class TabAppFunctions : AppFunctionService() {

    // No constructor injection: the generated subclass calls `: Base()`.
    private val store: LedgerStore by lazy { LedgerStore.get(applicationContext) }

    /**
     * Lists every expense in the shared Tab ledger together with each member's net balance.
     *
     * @return A newline-delimited summary of the expenses, the balances and the total.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun list_expenses(): String = withContext(Dispatchers.IO) {
        val ledger = store.ledger.value
        buildString {
            appendLine("Expenses (${ledger.expenses.size}):")
            if (ledger.expenses.isEmpty()) {
                appendLine("- none yet")
            } else {
                for (e in ledger.expenses) {
                    appendLine(
                        "- id=${e.id} | ${e.description}: ${e.amountCents.money()} paid by ${e.paidBy}, " +
                            "split with ${e.participants.joinToString(", ")}",
                    )
                }
            }
            appendLine("Balances:")
            for ((member, net) in ledger.balances()) {
                appendLine("- $member: ${net.money()}")
            }
            appendLine("Total: ${ledger.expenses.sumOf { it.amountCents }.money()}")
            val staged = store.staged.value
            if (staged.isNotEmpty()) {
                appendLine("Awaiting the user's approval in Tab (${staged.size}):")
                for (c in staged) appendLine("- ${c.label}")
            }
        }
    }

    /**
     * Reports who owes whom right now, without the expense list.
     *
     * @return One line per member showing their net position, then the running total.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun get_balances(): String = withContext(Dispatchers.IO) {
        val ledger = store.ledger.value
        buildString {
            appendLine("Balances (positive means owed money):")
            for ((member, net) in ledger.balances()) {
                appendLine("- $member: ${net.money()}")
            }
            appendLine("Total spent: ${ledger.expenses.sumOf { it.amountCents }.money()}")
        }
    }

    /**
     * Proposes a new expense. This does NOT write to the ledger — it stages the expense and
     * waits for the user to approve it in the Tab app.
     *
     * @param description What the expense was for, for example "Dinner".
     * @param amountCents The amount in integer cents, for example 4250 for $42.50.
     * @param paidBy The member who paid; must be one of the ledger's members.
     * @param participants The members the expense is split between; empty means just the payer.
     * @return What was staged and an instruction to approve it in Tab, or why it was refused.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun add_expense(
        description: String,
        amountCents: Long,
        paidBy: String,
        participants: List<String>,
    ): String = withContext(Dispatchers.IO) {
        val ledger = store.ledger.value
        if (description.isBlank()) return@withContext "Refused: description is empty."
        if (amountCents <= 0L) return@withContext "Refused: amountCents must be positive."

        val payer = ledger.members.firstOrNull { it.equals(paidBy.trim(), ignoreCase = true) }
            ?: return@withContext "Refused: \"$paidBy\" is not a member. Members are ${ledger.members.joinToString(", ")}."

        val split = participants
            .mapNotNull { name -> ledger.members.firstOrNull { it.equals(name.trim(), ignoreCase = true) } }
            .distinct()
            .ifEmpty { listOf(payer) }

        val expense = Expense(
            id = UUID.randomUUID().toString().take(8),
            description = description.trim(),
            amountCents = amountCents,
            paidBy = payer,
            participants = split,
            createdAt = System.currentTimeMillis(),
        )
        store.stage(listOf(Change.Add(expense)))

        "Staged (not committed): \"${expense.description}\" ${expense.amountCents.money()} paid by " +
            "${expense.paidBy}, split with ${expense.participants.joinToString(", ")}. " +
            "Open Tab and press Apply to commit it."
    }

    /**
     * Proposes removing an expense. This does NOT write to the ledger — it stages the
     * deletion and waits for the user to approve it in the Tab app.
     *
     * @param id The expense id shown by list_expenses.
     * @return What was staged and an instruction to approve it in Tab, or why it was refused.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun delete_expense(id: String): String = withContext(Dispatchers.IO) {
        val ledger = store.ledger.value
        val existing = ledger.expenses.firstOrNull { it.id == id.trim() }
            ?: return@withContext "Refused: no expense with id \"$id\". Call list_expenses for current ids."

        store.stage(listOf(Change.Delete(existing)))
        "Staged (not committed): delete \"${existing.description}\" (${existing.amountCents.money()}). " +
            "Open Tab and press Apply to commit it."
    }
}
