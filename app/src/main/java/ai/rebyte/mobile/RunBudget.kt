package ai.rebyte.mobile

import org.json.JSONObject
import java.util.Locale

/** Rebyte counts model charges in whole credits; one credit is USD 0.01. */
object RunBudget {
    const val DEFAULT_CREDITS = 20
    val choices = listOf(10, 20, 50)
    fun selected(credits: Int) = credits.takeIf { it in choices } ?: DEFAULT_CREDITS
    fun dollars(credits: Long) = String.format(Locale.US, "$%.2f", credits / 100.0)
    fun specification(credits: Int?): JSONObject {
        require(credits in choices) { "Choose a model budget before starting this task." }
        return JSONObject().put("max_credits", credits)
    }
    fun confirmed(session: JSONObject, credits: Int): Boolean {
        val returned = session.optJSONObject("agent")?.optJSONObject("budget")?.opt("max_credits") as? Number
        return returned?.toDouble() == credits.toDouble()
    }
    fun creditsUsed(session: JSONObject): Long? {
        val value = (session.opt("credits_used") as? Number)?.toDouble() ?: return null
        return if (value.isFinite() && value >= 0 && value == value.toLong().toDouble()) value.toLong() else null
    }
    fun exceeded(resource: JSONObject) = resource.optJSONObject("error")?.optString("code") == "session_budget_exceeded"
    fun summary(maxCredits: Int?, used: Long?): String {
        val budget = maxCredits?.let { "Model budget: ${dollars(it.toLong())}" } ?: "No model budget (older run)"
        return if (used == null) budget else "$budget · Reported use: ${dollars(used)}"
    }
}

class BudgetNotConfirmed : Exception("Rebyte did not confirm this task's model budget.")
