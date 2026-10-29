package com.ebowwa.tab.core.agent

import com.ebowwa.tab.core.model.Change
import com.ebowwa.tab.core.model.Expense
import com.ebowwa.tab.core.model.Ledger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trust boundary. These tests exist because the model is untrusted input: everything
 * it emits must be checked against real state before it can become a mutation.
 */
class PlanParserTest {

    private val ledger = Ledger(
        expenses = listOf(
            Expense("a1", "Coffee run", 1840L, "You", listOf("You", "Ana", "Ben")),
        ),
    )

    @Test
    fun `parses an add action into a staged change`() {
        val raw = """
            {"reply":"Adding dinner.","actions":[
              {"op":"add","description":"Dinner","amountCents":8400,"paidBy":"You","participants":["You","Ana","Ben"]}
            ]}
        """.trimIndent()

        val outcome = PlanParser.parse(raw, ledger)

        assertEquals("Adding dinner.", outcome.reply)
        assertNull(outcome.error)
        assertEquals(1, outcome.changes.size)
        val add = outcome.changes.first() as Change.Add
        assertEquals(8400L, add.expense.amountCents)
        assertEquals("You", add.expense.paidBy)
        assertEquals(listOf("You", "Ana", "Ben"), add.expense.participants)
    }

    @Test
    fun `rejects a payer who is not a member`() {
        val raw =
            """{"reply":"x","actions":[{"op":"add","description":"Dinner","amountCents":100,"paidBy":"Zed","participants":["Zed"]}]}"""

        val outcome = PlanParser.parse(raw, ledger)

        assertEquals(0, outcome.changes.size)
        assertTrue(outcome.notes.first().contains("not a member"))
    }

    @Test
    fun `rejects a non-positive amount`() {
        val raw =
            """{"reply":"x","actions":[{"op":"add","description":"Free lunch","amountCents":0,"paidBy":"You","participants":["You"]}]}"""

        val outcome = PlanParser.parse(raw, ledger)

        assertEquals(0, outcome.changes.size)
        assertTrue(outcome.notes.first().contains("positive amount"))
    }

    @Test
    fun `deletes only an id that exists`() {
        val raw = """{"reply":"x","actions":[{"op":"delete","id":"nope"}]}"""

        val outcome = PlanParser.parse(raw, ledger)

        assertEquals(0, outcome.changes.size)
        assertTrue(outcome.notes.first().contains("no expense with id"))
    }

    @Test
    fun `updates only an id that exists`() {
        val raw = """{"reply":"x","actions":[{"op":"update","id":"nope","amountCents":500}]}"""

        val outcome = PlanParser.parse(raw, ledger)

        assertEquals(0, outcome.changes.size)
        assertTrue(outcome.notes.first().contains("no expense with id"))
    }

    @Test
    fun `tolerates prose and code fences around the json`() {
        val raw = "Sure! Here is the plan:\n```json\n{\"reply\":\"ok\",\"actions\":[]}\n```\nDone."

        val outcome = PlanParser.parse(raw, ledger)

        assertNull(outcome.error)
        assertEquals("ok", outcome.reply)
    }

    @Test
    fun `reports an error when the model returns no json at all`() {
        val outcome = PlanParser.parse("I could not do that.", ledger)

        assertEquals(0, outcome.changes.size)
        assertTrue(outcome.error!!.contains("did not return a plan"))
    }

    @Test
    fun `member matching is case insensitive and normalized`() {
        val raw =
            """{"reply":"x","actions":[{"op":"add","description":"Snack","amountCents":300,"paidBy":"you","participants":["YOU","ana"]}]}"""

        val outcome = PlanParser.parse(raw, ledger)

        val add = outcome.changes.first() as Change.Add
        assertEquals("You", add.expense.paidBy)
        assertEquals(listOf("You", "Ana"), add.expense.participants)
    }

    @Test
    fun `an unknown op is ignored but reported`() {
        val raw = """{"reply":"x","actions":[{"op":"archive","id":"a1"}]}"""

        val outcome = PlanParser.parse(raw, ledger)

        assertEquals(0, outcome.changes.size)
        assertTrue(outcome.notes.first().contains("unknown action"))
    }
}
