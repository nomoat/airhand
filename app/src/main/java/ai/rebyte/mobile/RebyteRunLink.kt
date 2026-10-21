package ai.rebyte.mobile

import org.json.JSONObject

/** Rebyte Platform's session page includes turns, tool activity, and execution traces. */
object RebyteRunLink {
    private val resourceId = Regex("[A-Za-z0-9_-]{1,200}")
    fun fromSession(session: JSONObject): String? {
        val sessionId = session.optString("id", "")
        val agentId = session.optJSONObject("agent")?.optString("id", "") ?: return null
        if (!resourceId.matches(sessionId) || !resourceId.matches(agentId)) return null
        return "https://app.rebyte.ai/platform/agents/$agentId/sessions/$sessionId"
    }
}
