package com.soulreturns.devbridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The watchdog: cost per client tick, and shedding the most expensive packet type once the average passes the budget. */
class CostTest {
    private val ms = 1_000_000L

    @Test
    fun shedsTheMostExpensiveTypeOverBudget() {
        val shed = ArrayList<Triple<String, Double, Double>>()
        val cost = Cost(2.0) { type, avg, typeMs -> shed += Triple(type, avg, typeMs) }
        cost.endTick()
        repeat(Cost.WINDOW * 2) {
            cost.add(5 * ms, "block_entity_data")
            cost.add(ms / 10, "move_entity_pos")
            cost.endTick()
        }
        assertEquals("block_entity_data", shed.first().first)
        assertTrue(shed.first().second > 4.0, "${shed.first()}")
        assertTrue(cost.maxMs >= 5.1 && cost.overBudgetTicks >= Cost.WINDOW, cost.describe().toString())
    }

    @Test
    fun staysQuietUnderBudget() {
        val shed = ArrayList<String>()
        val cost = Cost(2.0) { type, _, _ -> shed += type }
        cost.endTick()
        repeat(Cost.WINDOW * 5) {
            cost.add(ms / 2, "set_entity_data")
            cost.endTick()
        }
        assertEquals(emptyList(), shed)
        assertEquals(0L, cost.overBudgetTicks)
        assertTrue(cost.avgMs in 0.3..0.55, "${cost.avgMs}")
    }

    @Test
    fun otherThreadsCountAsNetworkTime() {
        val cost = Cost(2.0)
        cost.endTick()
        cost.add(ms)
        val t = Thread { cost.add(3 * ms) }
        t.start()
        t.join()
        cost.endTick()
        assertEquals(4.0, cost.maxMs, 0.01)
        assertTrue(cost.describe()["network_thread_avg_ms"] as Double > 0.0)
    }
}
