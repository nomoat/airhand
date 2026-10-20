package ai.rebyte.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

class PhoneAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var connected: PhoneAccessibilityService? = null
            private set
    }
    private data class Element(val node: AccessibilityNodeInfo, val signature: String)
    private val elements = mutableListOf<Element>()
    private var snapshotId = ""
    private var imageWidth = 0
    private var imageHeight = 0
    private var screenWidth = 0
    private var screenHeight = 0
    internal val overlay by lazy { AgentOverlay(this) }
    override fun onServiceConnected() { connected = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() {
        overlay.remove()
        connected = null
        if (AgentRunService.runningId != null) startService(Intent(this, AgentRunService::class.java).setAction(AgentRunService.STOP))
        clearSnapshot(); super.onDestroy()
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay.onConfigurationChanged(newConfig)
        clearSnapshot()
    }

    private fun clearSnapshot() { elements.clear(); snapshotId = ""; imageWidth = 0; imageHeight = 0 }
    private fun dimensions(): Pair<Int, Int> {
        val b = getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
        return b.width() to b.height()
    }
    private fun signature(n: AccessibilityNodeInfo): String {
        val b = Rect().also(n::getBoundsInScreen)
        return "${n.windowId}|${n.packageName}|${n.viewIdResourceName}|${n.className}|${n.text}|${n.contentDescription}|$b"
    }
    private fun foregroundRoot(): AccessibilityNodeInfo? {
        // Touching our non-focusable overlay must not make it the agent's target app.
        val candidates = windows.filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        return (candidates.firstOrNull { it.isActive } ?: candidates.firstOrNull { it.isFocused }
            ?: candidates.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION })?.root ?: rootInActiveWindow
    }
    private fun privateScreen() = foregroundRoot()?.packageName?.toString() == packageName
    private fun requirePublicScreen() { check(!privateScreen()) { "AirHand settings and key screens cannot be controlled remotely." } }

    internal fun requireVisibleControls(runId: String) {
        overlay.requireVisible(runId)
        check(!getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked) { "Phone is locked. Unlock it and reconnect." }
    }

    internal fun describe(name: String, args: JSONObject): String {
        fun short(value: String) = value.replace(Regex("\\s+"), " ").take(48)
        fun target(): String {
            val node = elements.getOrNull(args.optInt("element_id", -1))?.node
            if (node == null || node.isPassword) return "screen element"
            return (node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                ?: node.text?.toString()?.takeIf { it.isNotBlank() })?.let(::short) ?: "screen element"
        }
        return when (name) {
            "phone_apps" -> "Find installed apps"
            "phone_open_app" -> {
                val pkg = args.getString("package_name")
                val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
                "Open ${short(label)}"
            }
            "phone_observe" -> "Check the current screen"
            "phone_click" -> "Tap '${target()}'"
            "phone_type" -> "Type '${short(args.getString("text"))}'"
            "phone_tap" -> "Tap the target on screen"
            "phone_swipe" -> if (args.getInt("y2") < args.getInt("y1")) "Swipe up" else "Swipe the page"
            "phone_press" -> when (args.getString("button")) {
                "back" -> "Go back"
                "home" -> "Go to home screen"
                "recents" -> "Open recent apps"
                "enter" -> "Submit text or search"
                else -> "Press a system button"
            }
            "phone_wait" -> "Wait for loading or playback"
            else -> "Perform the current action"
        }
    }

    private fun resolve(args: JSONObject): AccessibilityNodeInfo {
        require(args.getString("snapshot_id") == snapshotId && snapshotId.isNotEmpty()) { "Screen snapshot expired. Observe again." }
        val id = args.getInt("element_id")
        require(id in elements.indices) { "Invalid screen element ID." }
        val e = elements[id]
        check(e.node.refresh() && e.node.isVisibleToUser && signature(e.node) == e.signature) { "The screen element changed. Observe again." }
        require(e.node.packageName?.toString() != packageName && !e.node.isPassword) { "This element cannot be controlled." }
        return e.node
    }
    private fun imagePoint(args: JSONObject, xName: String, yName: String): Pair<Int, Int> {
        require(args.getString("snapshot_id") == snapshotId && snapshotId.isNotEmpty()) { "Screen snapshot expired. Observe again." }
        require(dimensions() == (screenWidth to screenHeight)) { "Screen dimensions changed. Observe again." }
        val x = args.getInt(xName); val y = args.getInt(yName)
        return CoordinateSpace.toScreen(x, y, imageWidth, imageHeight, screenWidth, screenHeight)
    }

    suspend fun execute(name: String, args: JSONObject): JSONArray = withContext(Dispatchers.Main) {
        PhoneTools.validate(name, args)
        requireVisibleControls(requireNotNull(AgentRunService.runningId))
        if (name == "phone_observe") return@withContext observe()
        if (name == "phone_apps") {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = packageManager.queryIntentActivities(intent, 0)
                .filter { it.activityInfo.packageName != packageName }
                .distinctBy { it.activityInfo.packageName }
                .map { JSONObject().put("name", it.loadLabel(packageManager).toString()).put("package", it.activityInfo.packageName) }
            return@withContext textContent(JSONObject().put("apps", JSONArray(apps)).toString())
        }
        when (name) {
            "phone_open_app" -> {
                val pkg = args.getString("package_name")
                require(pkg != packageName) { "Cannot open the private control app" }
                val intent = requireNotNull(packageManager.getLaunchIntentForPackage(pkg)) { "App is not installed or cannot be opened: $pkg" }
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            "phone_wait" -> {
                val ms = args.getLong("milliseconds")
                require(ms in 200..5000)
                delay(ms)
            }
            else -> {
                requirePublicScreen()
                when (name) {
                    "phone_click" -> {
                        val n = resolve(args)
                        val b = Rect().also(n::getBoundsInScreen)
                        avoidControls(b.centerX(), b.centerY(), b.centerX(), b.centerY())
                        if (!n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            gesture(b.centerX(), b.centerY(), b.centerX(), b.centerY(), 80)
                        }
                    }
                    "phone_type" -> {
                        val n = resolve(args)
                        require(n.isEditable) { "The target is not an editable field." }
                        val text = args.getString("text")
                        require(text.length <= 4000) { "Input text is too long." }
                        val bundle = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
                        check(n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)) { "The app does not support accessibility text input." }
                    }
                    "phone_tap" -> {
                        val p = imagePoint(args, "x", "y")
                        gesture(p.first, p.second, p.first, p.second, 80)
                    }
                    "phone_swipe" -> {
                        val duration = args.getLong("duration_ms")
                        require(duration in 100..2000)
                        val p1 = imagePoint(args, "x1", "y1")
                        val p2 = imagePoint(args, "x2", "y2")
                        gesture(p1.first, p1.second, p2.first, p2.second, duration)
                    }
                    "phone_press" -> {
                        val ok = when (args.getString("button")) {
                            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                            "enter" -> {
                                val n = requireNotNull(foregroundRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)) { "No text field is focused." }
                                n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                            }
                            else -> error("Unsupported system button")
                        }
                        check(ok) { "System action failed. Observe and tap a visible button." }
                    }
                    else -> error("Unknown tool")
                }
            }
        }
        clearSnapshot()
        delay(450)
        textContent("Action performed. Call phone_observe to verify the result.")
    }

    private suspend fun gesture(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long) {
        val (width, height) = dimensions()
        require(x1 in 0 until width && x2 in 0 until width && y1 in 0 until height && y2 in 0 until height) { "Coordinates are outside the screen." }
        avoidControls(x1, y1, x2, y2)
        suspendCancellableCoroutine { continuation ->
            val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("The system or user interrupted the gesture."))
                }
            }, null)
            if (!accepted && continuation.isActive) continuation.resumeWithException(IllegalStateException("The system rejected the gesture."))
        }
    }

    private suspend fun avoidControls(x1: Int, y1: Int, x2: Int, y2: Int) {
        if (overlay.avoid(Rect(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2) + 1, maxOf(y1, y2) + 1))) delay(120)
        requireVisibleControls(requireNotNull(AgentRunService.runningId))
    }

    private suspend fun screenshot(): Bitmap = suspendCancellableCoroutine { continuation ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hardware = result.hardwareBuffer
                try {
                    val wrapped = requireNotNull(Bitmap.wrapHardwareBuffer(hardware, result.colorSpace))
                    val bitmap = requireNotNull(wrapped.copy(Bitmap.Config.ARGB_8888, false))
                    wrapped.recycle()
                    if (continuation.isActive) continuation.resume(bitmap) else bitmap.recycle()
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                } finally { hardware.close() }
            }
            override fun onFailure(errorCode: Int) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Screenshot unavailable (error $errorCode)."))
            }
        })
    }

    private suspend fun observe(): JSONArray {
        clearSnapshot()
        if (privateScreen()) return textContent("This is the private AirHand control screen. Use phone_apps or phone_open_app to open the target app first.")
        val (width, height) = dimensions()
        screenWidth = width; screenHeight = height
        imageWidth = width; imageHeight = height
        val list = JSONArray()
        var visited = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 35 || visited++ > 900 || n.packageName?.toString() == packageName) return
            if (n.isVisibleToUser && (n.text != null || n.contentDescription != null || n.isClickable || n.isEditable || n.isScrollable)) {
                val id = elements.size
                val b = Rect().also(n::getBoundsInScreen)
                elements.add(Element(n, signature(n)))
                list.put(JSONObject().put("id", id).put("text", if (n.isPassword) "[password]" else n.text?.toString()?.take(400))
                    .put("description", n.contentDescription?.toString()?.take(400))
                    .put("view_id", n.viewIdResourceName).put("class", n.className?.toString())
                    .put("bounds", JSONArray(listOf(b.left, b.top, b.right, b.bottom)))
                    .put("clickable", n.isClickable).put("editable", n.isEditable && !n.isPassword).put("scrollable", n.isScrollable))
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        // Includes the IME and its search key as well as the active app.
        val roots = windows.filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }.mapNotNull { it.root }
        if (roots.isEmpty()) rootInActiveWindow?.let { walk(it, 0) } else roots.forEach { walk(it, 0) }
        snapshotId = UUID.randomUUID().toString()
        val data = JSONObject().put("snapshot_id", snapshotId)
            .put("package", foregroundRoot()?.packageName?.toString())
            .put("screen_width", width).put("screen_height", height)
            .put("coordinates", "image_pixels").put("elements", list)
            .put("app_uid", android.os.Process.myUid()).put("root_used", false)
        val image: String?
        try {
            val original = screenshot()
            val scale = minOf(1.0, 1024.0 / maxOf(original.width, original.height))
            val resized = Bitmap.createScaledBitmap(original, (original.width * scale).roundToInt(), (original.height * scale).roundToInt(), true)
            imageWidth = resized.width; imageHeight = resized.height
            data.put("image_width", resized.width).put("image_height", resized.height)
            overlay.bounds()?.let { b ->
                data.put("agent_overlay", JSONObject()
                    .put("instruction", "Local task controls, not part of the target app. Ignore this region; never interact with it. The overlay automatically moves away from target taps.")
                    .put("bounds", JSONArray(listOf(b.left * imageWidth / width, b.top * imageHeight / height, b.right * imageWidth / width, b.bottom * imageHeight / height))))
            }
            image = withContext(Dispatchers.Default) {
                val bytes = ByteArrayOutputStream()
                resized.compress(Bitmap.CompressFormat.JPEG, 70, bytes)
                check(bytes.size() < 700_000) { "Screenshot exceeds the API size limit." }
                "data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
            }
            if (resized !== original) resized.recycle()
            original.recycle()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            // The unavailable image is explicit; the UI tree can still be usable.
            data.put("screenshot_error", e.message)
            data.put("image_width", imageWidth).put("image_height", imageHeight)
            scaleBounds(list)
            return textContent(data.toString())
        }
        scaleBounds(list)
        return textContent(data.toString()).put(JSONObject().put("type", "input_image").put("image_url", image))
    }
    private fun scaleBounds(list: JSONArray) {
        for (i in 0 until list.length()) {
            val node = list.getJSONObject(i)
            val b = node.getJSONArray("bounds")
            node.put("bounds", JSONArray(listOf(
                (b.getInt(0).toDouble() * imageWidth / screenWidth).roundToInt(),
                (b.getInt(1).toDouble() * imageHeight / screenHeight).roundToInt(),
                (b.getInt(2).toDouble() * imageWidth / screenWidth).roundToInt(),
                (b.getInt(3).toDouble() * imageHeight / screenHeight).roundToInt()
            )))
        }
    }
}

fun textContent(text: String): JSONArray = JSONArray().put(JSONObject().put("type", "input_text").put("text", text))
