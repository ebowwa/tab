package com.ebowwa.tab.core.agent

import com.ebowwa.tab.core.model.Change
import com.ebowwa.tab.core.model.Expense
import com.ebowwa.tab.core.model.Ledger
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Turns a model's raw answer into staged changes.
 *
 * This is the trust boundary: whatever the model says is validated here — members must
 * exist, ids must be real, amounts must be positive — and anything that fails becomes a
 * note instead of a mutation. Nothing reaches the ledger from here.
 */
object PlanParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String, ledger: Ledger): AgentOutcome {
        val payload = extractJson(raw)
            ?: return AgentOutcome(reply = raw, error = "The model did not return a plan.", raw = raw)

        val plan = runCatching { json.decodeFromString<PlanDto>(payload) }
            .getOrElse {
                return AgentOutcome(reply = raw, error = "Could not read the plan: ${it.message}", raw = raw)
            }

        val notes = mutableListOf<String>()
        val changes = mutableListOf<Change>()

        plan.actions.forEach { action ->
            when (action.op?.lowercase()) {
                "add" -> {
                    val description = action.description?.trim().orEmpty()
                    val amount = action.amountCents
                    if (description.isEmpty() || amount == null || amount <= 0L) {
                        notes.add("Skipped an add: it needs a description and a positive amount in cents.")
                        return@forEach
                    }
                    val payer = canonical(action.paidBy, ledger) ?: run {
                        notes.add("Skipped \"$description\": \"${action.paidBy}\" is not a member.")
                        return@forEach
                    }
                    val participants = action.participants.orEmpty()
                        .mapNotNull { canonical(it, ledger) }
                        .distinct()
                        .ifEmpty { listOf(payer) }
                    changes += Change.Add(
                        Expense(
                            id = UUID.randomUUID().toString().take(8),
                            description = description,
                            amountCents = amount,
                            paidBy = payer,
                            participants = participants,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }

                "update" -> {
                    val existing = ledger.expenses.firstOrNull { it.id == action.id } ?: run {
                        notes.add("Skipped an update: no expense with id ${action.id}.")
                        return@forEach
                    }
                    val payer = action.paidBy?.let { canonical(it, ledger) } ?: existing.paidBy
                    val participants = action.participants?.mapNotNull { canonical(it, ledger) }
                        ?.distinct()?.ifEmpty { null } ?: existing.participants
                    changes += Change.Update(
                        existing.copy(
                            description = action.description?.trim()?.ifEmpty { null } ?: existing.description,
                            amountCents = action.amountCents?.takeIf { it > 0L } ?: existing.amountCents,
                            paidBy = payer,
                            participants = participants,
                        ),
                    )
                }

                "delete" -> {
                    val existing = ledger.expenses.firstOrNull { it.id == action.id } ?: run {
                        notes.add("Skipped a delete: no expense with id ${action.id}.")
                        return@forEach
                    }
                    changes += Change.Delete(existing)
                }

                else -> notes.add("Ignored an unknown action \"${action.op}\".")
            }
        }

        return AgentOutcome(
            reply = plan.reply.ifBlank {
                if (changes.isEmpty()) "I did not find anything to change." else "Here is what I would do."
            },
            changes = changes,
            notes = notes,
            raw = raw,
        )
    }

    private fun canonical(name: String?, ledger: Ledger): String? {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return ledger.members.firstOrNull { it.equals(trimmed, ignoreCase = true) }
    }

    private fun extractJson(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return raw.substring(start, end + 1)
    }
}

@Serializable
private data class PlanDto(
    val reply: String = "",
    val actions: List<ActionDto> = emptyList(),
)

@Serializable
private data class ActionDto(
    val op: String? = null,
    val id: String? = null,
    val description: String? = null,
    val amountCents: Long? = null,
    val paidBy: String? = null,
    val participants: List<String>? = null,
)
