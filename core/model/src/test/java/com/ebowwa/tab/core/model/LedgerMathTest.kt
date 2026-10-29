package com.ebowwa.tab.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LedgerMathTest {

    @Test
    fun `balances net to zero`() {
        val ledger = Ledger(
            expenses = listOf(
                Expense("a1", "Dinner", 8400L, "You", listOf("You", "Ana", "Ben")),
                Expense("a2", "Taxi", 4600L, "Ana", listOf("You", "Ana", "Ben", "Cy")),
            ),
        )

        assertEquals(0L, ledger.balances().values.sum())
    }

    @Test
    fun `splits evenly and the payer is owed the rest`() {
        val ledger = Ledger(
            expenses = listOf(Expense("a1", "Dinner", 8400L, "You", listOf("You", "Ana", "Ben"))),
        )

        val balances = ledger.balances()
        assertEquals(5600L, balances["You"])
        assertEquals(-2800L, balances["Ana"])
        assertEquals(-2800L, balances["Ben"])
    }

    @Test
    fun `remainder cents go to the first participants and still net to zero`() {
        val ledger = Ledger(
            expenses = listOf(Expense("a1", "Coffee", 1000L, "You", listOf("You", "Ana", "Ben"))),
        )

        val balances = ledger.balances()
        // 1000 / 3 -> 334, 333, 333
        assertEquals(1000L - 334L, balances["You"])
        assertEquals(-333L, balances["Ana"])
        assertEquals(0L, balances.values.sum())
    }

    @Test
    fun `delete removes exactly one expense`() {
        val ledger = Ledger(
            expenses = listOf(Expense("a1", "Dinner", 8400L, "You", listOf("You", "Ana"))),
        )

        val after = ledger.applyingAll(listOf(Change.Delete(ledger.expenses.first())))

        assertEquals(0, after.expenses.size)
        assertEquals(1, ledger.expenses.size)
    }

    @Test
    fun `update replaces in place and preserves order`() {
        val ledger = Ledger(
            expenses = listOf(
                Expense("a1", "Dinner", 8400L, "You", listOf("You", "Ana")),
                Expense("a2", "Taxi", 1000L, "Ana", listOf("You", "Ana")),
            ),
        )

        val after = ledger.applyingAll(
            listOf(Change.Update(ledger.expenses[0].copy(amountCents = 4200L))),
        )

        assertEquals(4200L, after.expenses[0].amountCents)
        assertEquals("a2", after.expenses[1].id)
    }

    @Test
    fun `total sums every expense`() {
        val ledger = Ledger(
            expenses = listOf(
                Expense("a1", "Dinner", 8400L, "You", listOf("You")),
                Expense("a2", "Taxi", 1600L, "Ana", listOf("Ana")),
            ),
        )

        assertEquals(10000L, ledger.totalCents())
    }
}
