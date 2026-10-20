package ai.rebyte.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiFailure(val status: Int, message: String) : Exception(message)

/** Native HTTPS implementation of Rebyte's Agents API; no model calls or agent loop on the phone. */
class RebyteApi(private val key: String) {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(75, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).build()
    suspend fun request(method: String, path: String, body: JSONObject? = null, idempotency: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            require(path.startsWith("/agents/sessions"))
            val req = Request.Builder().url("https://api.rebyte.ai/v1$path")
                .header("Authorization", "Bearer $key").header("OpenAI-Beta", "agents=v1")
                .header("Accept", "application/json")
            if (idempotency != null) req.header("Idempotency-Key", idempotency)
            req.method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
            http.newCall(req.build()).execute().use { response ->
                val text = requireNotNull(response.body).string()
                if (!response.isSuccessful) {
                    // Never echo request headers or raw credentials in errors.
                    val safe = text.replace(key, "[redacted]").take(1000)
                    throw ApiFailure(response.code, "Rebyte HTTP ${response.code}: $safe")
                }
                if (text.isBlank()) JSONObject() else JSONObject(text)
            }
        }
    suspend fun create(run: Run): JSONObject = request("POST", "/agents/sessions", JSONObject()
        .put("agent", JSONObject().put("model", run.model)
            .put("instructions", PhoneTools.instructions).put("tools", PhoneTools.definitions))
        .put("environment", JSONObject().put("type", "none"))
        .put("metadata", JSONObject().put("app", "airhand").put("mobile_run_id", run.id))
        .put("input", run.task))
    suspend fun retrieve(id: String) = request("GET", "/agents/sessions/$id")
    suspend fun findSession(run: Run): String? {
        val page = request("GET", "/agents/sessions?metadata%5Bmobile_run_id%5D=${run.id}&limit=10")
        val data = page.getJSONArray("data")
        check(data.length() <= 1) { "Multiple cloud tasks match this run. Review them before continuing." }
        return if (data.length() == 0) null else data.getJSONObject(0).getString("id")
    }
    suspend fun recover(run: Run): String = checkNotNull(findSession(run)) { "Cloud task not found. Stop this task and start a new one." }
    suspend fun result(session: String, call: String, event: JSONObject) =
        request("POST", "/agents/sessions/$session/events", JSONObject().put("events", JSONArray().put(event)), "mobile-result-$call")
    suspend fun cancel(session: String, runId: String) =
        request("POST", "/agents/sessions/$session/events",
            JSONObject().put("events", JSONArray().put(JSONObject().put("type", "agent.session.input.cancel"))), "mobile-cancel-$runId")
    suspend fun latestTurn(session: String): JSONObject {
        val data = request("GET", "/agents/sessions/$session/turns?limit=1").getJSONArray("data")
        check(data.length() == 1) { "The cloud did not return a task result." }
        return data.getJSONObject(0)
    }
    suspend fun answer(session: String): String {
        val history = request("GET", "/agents/sessions/$session/history?limit=1&order=desc").getJSONArray("data")
        check(history.length() == 1) { "The cloud did not return conversation history." }
        val text = history.getJSONObject(0).optString("output_text", "")
        return text.ifBlank { "Task ended. The cloud did not return a text result." }
    }
}
