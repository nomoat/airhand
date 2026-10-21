package ai.rebyte.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date

/** A local index of runs. No cloud listing, background sync, or separate AirHand server. */
class RunHistoryActivity : Activity() {
    private val ink = Color.rgb(24, 47, 39)
    private val green = Color.rgb(28, 83, 62)
    private val muted = Color.rgb(102, 113, 104)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: RunStore
    private lateinit var adapter: HistoryAdapter
    private var rows = emptyList<Run>()
    private var openingId: String? = null
    private val refresh = object : Runnable {
        override fun run() { reload(); handler.postDelayed(this, 2000) }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float, color: Int = ink) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RunStore(this)
        window.statusBarColor = Color.rgb(247, 248, 240)
        window.navigationBarColor = Color.rgb(247, 248, 240)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(247, 248, 240))
            setPadding(dp(24), dp(20), dp(24), dp(20))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(dp(24), dp(12) + bars.top, dp(24), dp(16) + bars.bottom)
                insets
            }
        }
        setContentView(body)
        body.addView(Button(this).apply {
            text = "Back to AirHand"; isAllCaps = false; setTextColor(green)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        body.addView(label("Run history", 28f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(12), 0, dp(8))
        })
        body.addView(label("Saved on this phone. Tap a run to view its steps and results in Rebyte.", 14f, muted))
        body.addView(label("Sign in to the Rebyte organization that owns the run.", 12f, muted).apply {
            setPadding(0, dp(8), 0, dp(16))
        })
        val frame = FrameLayout(this)
        body.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val list = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(Color.rgb(220, 227, 216))
            dividerHeight = dp(1)
        }
        val empty = label("No runs yet\nStart a task and it will appear here.", 16f, muted).apply { gravity = Gravity.CENTER }
        frame.addView(list, FrameLayout.LayoutParams(-1, -1))
        frame.addView(empty, FrameLayout.LayoutParams(-1, -1))
        list.emptyView = empty
        adapter = HistoryAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> openRun(rows[position]) }
    }
    private fun reload() {
        val latest = store.history()
        if (latest != rows) { rows = latest; adapter.notifyDataSetChanged() }
    }
    private fun openRun(run: Run) {
        if (openingId != null) return
        run.cloudUrl?.let { openWeb(it); return }
        if (run.sessionId == null) {
            Toast.makeText(this, "No Rebyte link yet. This task has no confirmed cloud session.", Toast.LENGTH_LONG).show()
            return
        }
        val secrets = Secrets(this)
        if (!secrets.hasKey()) {
            AlertDialog.Builder(this).setTitle("API key needed for this older run")
                .setMessage("Return to AirHand and set the key for this run's Rebyte organization. Its link will be saved on this phone after the first lookup.")
                .setPositiveButton("OK", null).show()
            return
        }
        openingId = run.id
        adapter.notifyDataSetChanged()
        scope.launch {
            try {
                // Older versions kept only session IDs. Resolve that one run when tapped, then cache its link.
                val session = RebyteApi(secrets.read()).retrieve(run.sessionId)
                store.rememberSession(run.id, session)
                val url = store.get(run.id).cloudUrl ?: error("The cloud response has no run link.")
                reload()
                openWeb(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = when ((e as? ApiFailure)?.status) {
                    401, 403 -> "Set a valid API key for this run's Rebyte organization, then try again."
                    404 -> "This run is unavailable to the current API key. It may belong to another organization or have been deleted."
                    else -> "Could not look up this older run's link. Check your connection and try again. The local record is still saved."
                }
                AlertDialog.Builder(this@RunHistoryActivity).setTitle("Could not open run")
                    .setMessage(message).setPositiveButton("OK", null).show()
            } finally {
                openingId = null
                adapter.notifyDataSetChanged()
            }
        }
    }
    private fun openWeb(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: ActivityNotFoundException) {
            AlertDialog.Builder(this).setTitle("Open this link in a browser")
                .setMessage(url).setPositiveButton("OK", null).show()
        }
    }
    private inner class HistoryAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? LinearLayout ?: LinearLayout(this@RunHistoryActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(16), dp(4), dp(16))
                addView(label("", 16f).apply { setTypeface(null, Typeface.BOLD) })
                addView(label("", 12f, muted).apply { setPadding(0, dp(6), 0, dp(6)) })
                addView(label("", 14f, green))
            }
            val run = rows[position]
            (row.getChildAt(0) as TextView).text = run.task
            (row.getChildAt(1) as TextView).text = "${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(run.createdAt))} · ${run.stateLabel}"
            (row.getChildAt(2) as TextView).text = when {
                openingId == run.id -> "Looking up run…"
                run.sessionId == null -> "No Rebyte link yet"
                else -> "View run in Rebyte ↗"
            }
            return row
        }
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() {
        handler.removeCallbacks(refresh)
        scope.coroutineContext.cancelChildren()
        super.onPause()
    }
    override fun onDestroy() {
        scope.cancel()
        store.close()
        super.onDestroy()
    }
}
