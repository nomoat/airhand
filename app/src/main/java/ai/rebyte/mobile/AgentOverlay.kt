package ai.rebyte.mobile

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** A small, touchable accessibility window. The rest of the screen remains interactive. */
internal class AgentOverlay(private val service: PhoneAccessibilityService) {
    private val manager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var card: LinearLayout? = null
    private lateinit var title: TextView
    private lateinit var action: TextView
    private lateinit var detail: TextView
    private lateinit var stop: Button
    private lateinit var params: WindowManager.LayoutParams
    private var owner: String? = null
    private var stopping = false
    private var closeAction: (() -> Unit)? = null
    private val dismiss = Runnable { remove() }
    private val screenTimeout = Runnable { releaseScreenOn() }
    private val ink = Color.rgb(241, 247, 239)
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
    // Reserve room for two action lines, including CJK font metrics and the user's font size.
    private fun panelHeight() = dp(40) + android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP, 80f, service.resources.displayMetrics).toInt()
    private fun label(size: Float, color: Int = ink) = TextView(service).apply {
        textSize = size; setTextColor(color); includeFontPadding = false
        ellipsize = TextUtils.TruncateAt.END; maxLines = 1
    }
    private fun area(): OverlayPlacement.Box {
        val metrics = manager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val b = metrics.bounds
        return OverlayPlacement.Box(insets.left + dp(10), insets.top + dp(10), b.width() - insets.right - dp(10), b.height() - insets.bottom - dp(10))
    }
    @SuppressLint("ClickableViewAccessibility")
    fun show(runId: String, onStop: () -> Unit) {
        remove()
        owner = runId; stopping = false; closeAction = onStop
        val safe = area()
        params = WindowManager.LayoutParams(
            minOf(dp(360), safe.width), panelHeight(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = safe.right - width; y = safe.top
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            title = "AirHand task controls"
        }
        val view = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(10), dp(12))
            background = GradientDrawable().apply { setColor(Color.rgb(24, 55, 44)); cornerRadius = dp(22).toFloat(); setStroke(dp(1), Color.rgb(74, 108, 86)) }
            elevation = dp(8).toFloat()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val labels = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        title = label(11f, Color.rgb(175, 207, 171)).apply { letterSpacing = .05f }
        action = label(16f).apply { maxLines = 2; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        detail = label(11f, Color.rgb(191, 207, 194))
        labels.addView(title)
        labels.addView(action, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5); bottomMargin = dp(5) })
        labels.addView(detail)
        view.addView(labels, LinearLayout.LayoutParams(0, -1, 1f))
        stop = Button(service).apply {
            text = "Stop"; textSize = 14f; isAllCaps = false; contentDescription = "Stop the current task"
            setTextColor(Color.rgb(55, 44, 36)); minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply { setColor(Color.rgb(244, 223, 196)); cornerRadius = dp(14).toFloat() }
            setOnClickListener {
                if (stopping) return@setOnClickListener
                stopping = true; isEnabled = false
                title.text = "AIRHAND · Stopping"
                detail.text = "Cancelling cloud task"
                closeAction?.invoke()
            }
        }
        view.addView(stop, LinearLayout.LayoutParams(dp(60), dp(48)).apply { leftMargin = dp(12) })
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragged = false
        labels.contentDescription = "Drag task controls"
        labels.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; dragged = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (abs(dx) + abs(dy) > ViewConfiguration.get(service).scaledTouchSlop) dragged = true
                    if (dragged) move(OverlayPlacement.clamp(OverlayPlacement.Box(startX + dx.toInt(), startY + dy.toInt(), startX + dx.toInt() + params.width, startY + dy.toInt() + params.height), area()))
                    true
                }
                MotionEvent.ACTION_UP -> { if (!dragged) labels.performClick(); true }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        card = view
        try { manager.addView(view, params) } catch (e: Exception) { card = null; owner = null; throw e }
        handler.postDelayed(screenTimeout, 12 * 60_000L)
        update(runId, 0, "Connecting to Rebyte", "Connect to begin · Drag to move")
    }
    fun update(runId: String, step: Int, message: String, phase: String) {
        if (owner != runId || stopping) return
        title.text = if (step == 0) "AIRHAND · GETTING READY" else "AIRHAND · STEP $step"
        action.text = message
        detail.text = phase
    }
    fun requireVisible(runId: String) {
        check(owner == runId && card?.isAttachedToWindow == true && card?.isShown == true) { "Task controls are not visible. Actions are paused." }
    }
    fun bounds(): Rect? = card?.takeIf { it.isAttachedToWindow }?.let {
        val xy = IntArray(2); it.getLocationOnScreen(xy)
        Rect(xy[0], xy[1], xy[0] + it.width, xy[1] + it.height)
    }
    /** Moves the control out of a physical gesture's bounding box without ever hiding it. */
    fun avoid(target: Rect): Boolean {
        val current = bounds() ?: error("Task controls are closed.")
        val padding = dp(10)
        val danger = OverlayPlacement.Box(target.left - padding, target.top - padding, target.right + padding, target.bottom + padding)
        val next = OverlayPlacement.avoid(OverlayPlacement.Box(current.left, current.top, current.right, current.bottom), area(), danger)
            ?: error("The gesture overlaps the task controls. Try a shorter gesture.")
        if (next.left == current.left && next.top == current.top) return false
        move(next)
        return true
    }
    private fun move(box: OverlayPlacement.Box) {
        val view = card ?: return
        params.x = box.left; params.y = box.top
        params.width = box.width; params.height = box.height
        manager.updateViewLayout(view, params)
    }
    fun onConfigurationChanged(@Suppress("UNUSED_PARAMETER") config: Configuration) {
        if (card == null) return
        val safe = area()
        val width = minOf(dp(360), safe.width)
        move(OverlayPlacement.clamp(OverlayPlacement.Box(params.x, params.y, params.x + width, params.y + panelHeight()), safe))
    }
    fun finish(runId: String, message: String) {
        if (owner != runId || card == null) return
        releaseScreenOn()
        stopping = false
        title.text = "AIRHAND · TASK ENDED"
        action.text = message
        detail.text = "Full activity is available in AirHand"
        stop.text = "Close"; stop.contentDescription = "Close task controls"; stop.isEnabled = true
        closeAction = { remove() }
        handler.removeCallbacks(dismiss); handler.postDelayed(dismiss, 6000)
    }
    fun releaseScreenOn() {
        handler.removeCallbacks(screenTimeout)
        val view = card ?: return
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        if (view.isAttachedToWindow) manager.updateViewLayout(view, params)
    }
    fun remove() {
        handler.removeCallbacks(dismiss)
        handler.removeCallbacks(screenTimeout)
        card?.let { if (it.isAttachedToWindow) manager.removeViewImmediate(it) }
        card = null; owner = null; closeAction = null
    }
}
