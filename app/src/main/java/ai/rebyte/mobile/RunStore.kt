package ai.rebyte.mobile

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.util.UUID

data class Run(val id: String, val task: String, val model: String, val sessionId: String?, val state: String,
    val createdAt: Long, val cloudUrl: String?) {
    val stateLabel: String get() = when (state) {
        "created" -> "Ready to start"
        "creating" -> "Creating cloud task"
        "running" -> "Running"
        "paused" -> "Connection paused"
        "needs_review" -> "Review the previous action"
        "completed" -> "Completed"
        "failed" -> "Failed"
        "cancelled" -> "Stopped"
        else -> state
    }
}
class CallLimitReached : Exception("Reached the 40-step limit")
class UncertainCall : Exception("The previous action was interrupted and its result is unknown. Stop this task and start a new one to avoid repeating an action.")

/** Durable call journal: mark before touching the phone; persist the result before HTTP submission. */
class RunStore(context: Context) : SQLiteOpenHelper(context, "runs.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE runs (id TEXT PRIMARY KEY, task TEXT NOT NULL, model TEXT NOT NULL, session TEXT, state TEXT NOT NULL, created INTEGER NOT NULL, cloud_url TEXT)")
        db.execSQL("CREATE TABLE calls (run TEXT NOT NULL, id TEXT NOT NULL, fingerprint TEXT NOT NULL, result TEXT, PRIMARY KEY(run,id))")
        db.execSQL("CREATE TABLE logs (id INTEGER PRIMARY KEY AUTOINCREMENT, run TEXT NOT NULL, text TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE runs ADD COLUMN cloud_url TEXT")
    }
    fun create(task: String, model: String): Run {
        val id = UUID.randomUUID().toString()
        writableDatabase.insertOrThrow("runs", null, ContentValues().apply {
            put("id", id); put("task", task); put("model", model); put("state", "created"); put("created", System.currentTimeMillis())
        })
        return get(id)
    }
    fun get(id: String): Run = readableDatabase.rawQuery("SELECT * FROM runs WHERE id=?", arrayOf(id)).use {
        check(it.moveToFirst()) { "Task not found." }
        Run(it.getString(0), it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getLong(5), it.getString(6))
    }
    fun latest(): Run? = readableDatabase.rawQuery("SELECT id FROM runs ORDER BY created DESC LIMIT 1", null).use {
        if (it.moveToFirst()) get(it.getString(0)) else null
    }
    fun history(): List<Run> = readableDatabase.rawQuery("SELECT * FROM runs ORDER BY created DESC, rowid DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(Run(c.getString(0), c.getString(1), c.getString(2),
            c.getString(3), c.getString(4), c.getLong(5), c.getString(6))) }
    }
    fun rememberSession(id: String, session: JSONObject) {
        val sessionId = session.getString("id")
        val previous = get(id).sessionId
        check(previous == null || previous == sessionId) { "The cloud returned a different session." }
        writableDatabase.update("runs", ContentValues().apply {
            put("session", sessionId)
            RebyteRunLink.fromSession(session)?.let { put("cloud_url", it) }
        }, "id=?", arrayOf(id))
    }
    fun update(id: String, state: String, session: String? = null) {
        writableDatabase.update("runs", ContentValues().apply {
            put("state", state)
            if (session != null) put("session", session)
        }, "id=?", arrayOf(id))
    }
    fun log(id: String, text: String) {
        writableDatabase.insertOrThrow("logs", null, ContentValues().apply {
            put("run", id); put("text", text.take(8000))
        })
    }
    fun logText(id: String): String = readableDatabase.rawQuery(
        "SELECT text FROM (SELECT id,text FROM logs WHERE run=? ORDER BY id DESC LIMIT 60) ORDER BY id",
        arrayOf(id)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) }.joinToString("\n\n") }

    /** null = new call. An unfinished call after interruption must never be repeated blindly. */
    fun claim(run: String, id: String, fingerprint: String): JSONObject? {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = db.rawQuery("SELECT fingerprint,result FROM calls WHERE run=? AND id=?", arrayOf(run, id)).use {
                if (!it.moveToFirst()) {
                    if (callCount(run) >= 40) throw CallLimitReached()
                    db.insertOrThrow("calls", null, ContentValues().apply {
                        put("run", run); put("id", id); put("fingerprint", fingerprint)
                    })
                    null
                } else {
                    check(it.getString(0) == fingerprint) { "The request changed for the same call_id." }
                    if (it.isNull(1)) throw UncertainCall()
                    JSONObject(it.getString(1))
                }
            }
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }
    fun finishCall(run: String, id: String, result: JSONObject) {
        check(writableDatabase.update("calls", ContentValues().apply { put("result", result.toString()) },
            "run=? AND id=?", arrayOf(run, id)) == 1)
    }
    fun callCount(run: String): Int = readableDatabase.rawQuery(
        "SELECT count(*) FROM calls WHERE run=?", arrayOf(run)).use { it.moveToFirst(); it.getInt(0) }
}
