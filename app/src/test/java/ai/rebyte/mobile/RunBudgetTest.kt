package ai.rebyte.mobile

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RunBudgetTest {
    private fun run(budget: Int?) = Run("local-example", "Open Settings", "gpt-6-luna", null,
        "created", 1L, null, budget, null)

    @Test fun defaultAndInvalidPreferencesUseTwentyCents() {
        assertEquals(20, RunBudget.DEFAULT_CREDITS)
        assertEquals(20, RunBudget.selected(0))
        assertEquals(20, RunBudget.selected(-1))
        assertEquals(20, RunBudget.selected(50000))
        assertEquals(50, RunBudget.selected(50))
    }
    @Test fun requestUsesTheBudgetSavedWithThatRun() {
        val twentyCentRun = run(20)
        val fiftyCentRun = run(50)
        for (entry in listOf(twentyCentRun, fiftyCentRun, twentyCentRun)) {
            val body = RebyteApi.sessionRequest(entry)
            assertEquals(entry.maxCredits, body.getJSONObject("agent").getJSONObject("budget").getInt("max_credits"))
            assertEquals("none", body.getJSONObject("environment").getString("type"))
        }
    }
    @Test fun noRequestCanSilentlyCreateAnUnlimitedSession() {
        for (credits in listOf(null, 0, -1, 100)) {
            assertThrows(IllegalArgumentException::class.java) { RebyteApi.sessionRequest(run(credits)) }
        }
    }
    @Test fun cloudMustConfirmTheExactNumericBudget() {
        for (value in listOf<Any>(20, 50, "20", 20.5, JSONObject.NULL)) {
            val session = JSONObject().put("agent", JSONObject().put("budget", JSONObject().put("max_credits", value)))
            assertEquals(value == 20, RunBudget.confirmed(session, 20))
        }
        assertFalse(RunBudget.confirmed(JSONObject(), 20))
    }
    @Test fun unknownUsageIsNotDisplayedAsZero() {
        assertNull(RunBudget.creditsUsed(JSONObject()))
        assertNull(RunBudget.creditsUsed(JSONObject().put("credits_used", JSONObject.NULL)))
        assertNull(RunBudget.creditsUsed(JSONObject().put("credits_used", -1)))
        assertNull(RunBudget.creditsUsed(JSONObject().put("credits_used", 1.5)))
        assertEquals(0L, RunBudget.creditsUsed(JSONObject().put("credits_used", 0)))
        assertEquals(21L, RunBudget.creditsUsed(JSONObject().put("credits_used", 21)))
        assertEquals("Model budget: $0.20 · Reported use: $0.21", RunBudget.summary(20, 21))
    }
    @Test fun onlyTheBudgetErrorIsShownAsBudgetReached() {
        assertTrue(RunBudget.exceeded(JSONObject("""{"error":{"code":"session_budget_exceeded"}}""")))
        assertFalse(RunBudget.exceeded(JSONObject("""{"error":{"code":"usage_limit_exceeded"}}""")))
        assertFalse(RunBudget.exceeded(JSONObject("""{"error":null}""")))
    }
    @Test fun budgetReachedIsTerminalAndDoesNotOfferReconnect() {
        val stopped = run(20).copy(state = "budget_reached")
        assertTrue(stopped.isFinished)
        assertEquals("Budget reached", stopped.stateLabel)
        assertFalse(run(20).copy(state = "paused").isFinished)
        assertNull(run(null).maxCredits)
    }
}
