package ai.rebyte.mobile

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RebyteRunLinkTest {
    @Test fun inlineAgentSessionUsesItsOwnPlatformRoute() {
        val session = JSONObject("""{"id":"sess_example","agent":{"id":"agent_example"},"status":"idle"}""")
        assertEquals("https://app.rebyte.ai/platform/agents/agent_example/sessions/sess_example", RebyteRunLink.fromSession(session))
    }
    @Test fun missingOrNullIdentityDoesNotCreateABrokenLink() {
        listOf("{}", """{"id":"sess_example"}""", """{"id":null,"agent":{"id":"agent_example"}}""",
            """{"id":"sess_example","agent":{"id":null}}""").forEach {
            assertNull(RebyteRunLink.fromSession(JSONObject(it)))
        }
    }
    @Test fun resourceIdsCannotInjectAPathOrCredential() {
        for (bad in listOf("../api-keys", "x?key=secret", "x#fragment", "https://other.example", "", "x".repeat(201))) {
            assertNull(RebyteRunLink.fromSession(JSONObject().put("id", bad).put("agent", JSONObject().put("id", "agent_example"))))
            assertNull(RebyteRunLink.fromSession(JSONObject().put("id", "sess_example").put("agent", JSONObject().put("id", bad))))
        }
    }
}
