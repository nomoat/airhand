package ai.rebyte.mobile

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.ComponentName
import android.net.Uri
import android.graphics.Paint
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*

class MainActivity : Activity() {
    private val ink = Color.rgb(24, 47, 39)
    private val green = Color.rgb(28, 83, 62)
    private val muted = Color.rgb(102, 113, 104)
    private lateinit var store: RunStore
    private lateinit var secrets: Secrets
    private lateinit var task: EditText
    private lateinit var status: TextView
    private lateinit var logs: TextView
    private lateinit var start: Button
    private lateinit var resume: Button
    private lateinit var stop: Button
    private lateinit var keyButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { update(); handler.postDelayed(this, 1000) }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float, color: Int = ink) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun shape(color: Int, radius: Int = 20) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun button(value: String, primary: Boolean = false, click: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; textSize = 15f
        setTextColor(if (primary) Color.WHITE else green)
        background = shape(if (primary) green else Color.rgb(228, 236, 226), 14)
        minHeight = dp(48); setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { click() }
    }
    private fun link(value: String, click: () -> Unit) = label(value, 14f, green).apply {
        paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(48)
        isFocusable = true
        setOnClickListener { click() }
    }
    private fun openWeb(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: ActivityNotFoundException) {
            AlertDialog.Builder(this).setTitle("Open this link in a browser")
                .setMessage(url).setPositiveButton("OK", null).show()
        }
    }
    private fun openControlSettings() {
        val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(this, PhoneAccessibilityService::class.java))
        try { startActivity(details) }
        catch (_: ActivityNotFoundException) { openAccessibilityList() }
        catch (_: SecurityException) { openAccessibilityList() }
    }
    private fun openAccessibilityList() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Open Downloaded apps or Downloaded services, then AirHand control.", Toast.LENGTH_LONG).show()
        } catch (_: ActivityNotFoundException) {
            AlertDialog.Builder(this).setTitle("Enable AirHand control")
                .setMessage("Open Android Settings > Accessibility > Downloaded apps or Downloaded services > AirHand control. Turn on Use AirHand control, then return here.")
                .setPositiveButton("OK", null).show()
        }
    }
    private fun openAppInfo() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            Toast.makeText(this, "If Android shows it, use the menu to Allow restricted settings.", Toast.LENGTH_LONG).show()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Open Android Settings > Apps > AirHand > App info.", Toast.LENGTH_LONG).show()
        }
    }
    private fun LinearLayout.addSpace(n: Int) { addView(View(this@MainActivity), LinearLayout.LayoutParams(1, dp(n))) }
    private fun LinearLayout.addFull(view: View, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT) {
        addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, if (height > 0) dp(height) else height))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RunStore(this); secrets = Secrets(this)
        try { secrets.importDebugKey() } catch (e: Exception) { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }
        window.statusBarColor = Color.rgb(247, 248, 240)
        window.navigationBarColor = Color.rgb(247, 248, 240)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(247, 248, 240)); isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(36))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(dp(24), dp(20) + bars.top, dp(24), dp(28) + bars.bottom)
                insets
            }
        }
        scroll.addView(body); setContentView(scroll)
        body.addFull(label("AIRHAND", 12f, muted).apply { letterSpacing = .14f })
        body.addSpace(16)
        body.addFull(label("Your phone, on task.", 28f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        body.addSpace(8)
        body.addFull(label("Describe a task. AirHand operates your phone.\nNo root or connected computer required.", 15f, muted))
        body.addSpace(20)
        status = label("", 13f, green).apply { setPadding(dp(14), dp(12), dp(14), dp(12)); background = shape(Color.rgb(232, 239, 226), 12) }
        body.addFull(status)
        body.addSpace(18)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18)); background = shape(Color.WHITE)
        }
        body.addFull(card)
        card.addFull(label("What would you like to do?", 17f).apply { setTypeface(null, Typeface.BOLD) })
        card.addSpace(10)
        task = EditText(this).apply {
            hint = "Open YouTube, search NASA, and play a video"
            textSize = 16f; setTextColor(ink); setHintTextColor(muted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP; minLines = 3; maxLines = 5
            setPaddingRelative(0, dp(8), dp(48), dp(8))
            contentDescription = "Task description"
        }
        val taskField = FrameLayout(this)
        taskField.addView(task, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        val clearTask = ImageButton(this).apply {
            setImageResource(R.drawable.ic_clear)
            contentDescription = "Clear task"
            val selectable = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, selectable, true)
            setBackgroundResource(selectable.resourceId)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            visibility = View.GONE
            setOnClickListener {
                task.text.clear()
                task.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(task, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        taskField.addView(clearTask, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END))
        task.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clearTask.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        card.addFull(taskField)
        card.addSpace(12)
        start = button("Start task", true) { begin(false) }
        card.addFull(start, 50)
        card.addSpace(10)
        card.addFull(label("During a task, screen text and screenshots are sent to Rebyte. Keep your phone unlocked. The floating control shows each step and lets you stop at any time.", 12f, muted))
        body.addSpace(16)
        body.addFull(button("Run history") { startActivity(Intent(this, RunHistoryActivity::class.java)) })
        body.addSpace(12)
        val config = LinearLayout(this)
        val access = button("AirHand control") {
            AlertDialog.Builder(this).setTitle("Enable AirHand control")
                .setMessage("On the next screen, turn on Use AirHand control and confirm, then return here.\n\nIf your phone shows a list, open Downloaded apps or Downloaded services > AirHand control. This is the only service you need to enable.\n\nDuring tasks you start, AirHand reads screen text and screenshots, taps, swipes, and types. Screen content goes to your Rebyte cloud agent. Use the floating Stop control at any time.\n\nIf Android blocks access with Restricted settings, open App info and look for Allow restricted settings in its menu.")
                .setPositiveButton("Open AirHand settings") { _, _ -> openControlSettings() }
                .setNeutralButton("App info") { _, _ -> openAppInfo() }
                .setNegativeButton("Cancel", null).show()
        }
        keyButton = button("Set API key") { keyDialog() }
        config.addView(access, LinearLayout.LayoutParams(0, dp(48), 1f))
        config.addView(Space(this), LinearLayout.LayoutParams(dp(10), 1))
        config.addView(keyButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        body.addFull(config)
        body.addSpace(6)
        body.addFull(label("In Android settings, enable only AirHand control.", 12f, muted))
        body.addFull(link("Get a Rebyte API key") { openWeb("https://app.rebyte.ai/platform/api-keys") })
        body.addSpace(8)
        body.addFull(button("Choose model") { modelDialog() })
        body.addSpace(22)
        body.addFull(label("Activity", 18f).apply { setTypeface(null, Typeface.BOLD) })
        body.addSpace(10)
        val controls = LinearLayout(this)
        resume = button("Reconnect") { begin(true) }
        stop = button("Stop task") {
            startService(Intent(this, AgentRunService::class.java).setAction(AgentRunService.STOP)
                .putExtra(AgentRunService.RUN_ID, store.latest()?.id))
        }
        controls.addView(resume, LinearLayout.LayoutParams(0, dp(46), 1f))
        controls.addView(Space(this), LinearLayout.LayoutParams(dp(10), 1))
        controls.addView(stop, LinearLayout.LayoutParams(0, dp(46), 1f))
        body.addFull(controls)
        body.addSpace(10)
        logs = label("Your task steps and results will appear here.", 13f, muted).apply { setTextIsSelectable(true) }
        body.addFull(logs)
    }
    private fun model(): String = getPreferences(MODE_PRIVATE).getString("model", "gpt-6-luna")!!
    private fun modelDialog() {
        val input = EditText(this).apply { setText(model()); setSingleLine() }
        AlertDialog.Builder(this).setTitle("Rebyte model ID").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString().trim()
                if (value.matches(Regex("[a-zA-Z0-9._/-]{1,100}"))) getPreferences(MODE_PRIVATE).edit().putString("model", value).apply()
                else Toast.makeText(this, "Invalid model ID", Toast.LENGTH_LONG).show()
            }.setNegativeButton("Cancel", null).show()
    }
    private fun keyDialog() {
        val input = EditText(this).apply {
            hint = "rbk_…"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setSingleLine()
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addFull(input)
            addFull(link("Get a Rebyte API key") { openWeb("https://app.rebyte.ai/platform/api-keys") })
            addFull(link("Read the setup guide") { openWeb("https://github.com/nomoat/airhand#get-a-rebyte-api-key") })
        }
        val dialog = AlertDialog.Builder(this).setTitle("Your Rebyte API key")
            .setMessage("Create a key in Rebyte Platform, then paste it here. Cloud usage is billed separately. Your key is stored on this device, encrypted with Android Keystore.")
            .setView(form).setPositiveButton("Save", null).setNegativeButton("Cancel", null)
            .setNeutralButton("Delete key") { _, _ ->
                if (AgentRunService.runningId == null) { secrets.clear(); update() }
                else Toast.makeText(this, "Stop the current task first.", Toast.LENGTH_LONG).show()
            }.create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    check(AgentRunService.runningId == null) { "Stop the current task first." }
                    secrets.save(input.text.toString().trim()); input.text.clear(); dialog.dismiss(); update()
                } catch (e: Exception) { input.error = e.message }
            }
        }
        dialog.show()
    }
    private fun begin(retry: Boolean) {
        try {
            check(secrets.hasKey()) { "Set your API key first." }
            check(PhoneAccessibilityService.connected != null) { "Enable AirHand control in accessibility settings first." }
            check(AgentRunService.runningId == null) { "A task is already running." }
            val run = if (retry) requireNotNull(store.latest()) else {
                check(task.text.isNotBlank()) { "Enter a task." }
                val previous = store.latest()
                check(previous == null || previous.state in listOf("completed", "failed", "cancelled")) { "Stop or resume the previous task first." }
                store.create(task.text.toString().trim(), model())
            }
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(task.windowToken, 0)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4)
            startForegroundService(Intent(this, AgentRunService::class.java).putExtra(AgentRunService.RUN_ID, run.id))
            update()
        } catch (e: Exception) { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }
    }
    private fun update() {
        if (!::status.isInitialized) return
        val enabled = PhoneAccessibilityService.connected != null
        status.text = if (enabled && secrets.hasKey()) "● Ready · No root required" else "○ ${if (!enabled) "Enable AirHand control to continue" else "Set an API key to continue"}"
        keyButton.text = if (secrets.hasKey()) "API key saved" else "Set API key"
        val run = store.latest()
        val active = AgentRunService.runningId != null
        start.isEnabled = !active
        val unfinished = run != null && run.state !in listOf("completed", "failed", "cancelled")
        resume.visibility = if (!active && unfinished && run?.state != "needs_review") View.VISIBLE else View.GONE
        stop.visibility = if (active || unfinished) View.VISIBLE else View.GONE
        if (run != null) {
            logs.text = "Status: ${run.stateLabel}\n\n${store.logText(run.id)}"
        }
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
