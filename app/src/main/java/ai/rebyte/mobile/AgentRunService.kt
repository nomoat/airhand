package ai.rebyte.mobile

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.*
import org.json.JSONObject

class AgentRunService : Service() {
    companion object {
        const val RUN_ID = "run_id"
        const val STOP = "ai.rebyte.mobile.STOP"
        @Volatile var runningId: String? = null
            private set
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private lateinit var store: RunStore
    private var wakeLock: PowerManager.WakeLock? = null
    private var taskOverlay: AgentOverlay? = null
    private var currentStep = 0
    private var currentAction = "Connecting to Rebyte"
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        store = RunStore(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("agent", "Phone tasks", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            if (job?.isActive == true) job?.cancel()
            else {
                val id = intent.getStringExtra(RUN_ID)
                if (id == null) stopSelf()
                else {
                    startForeground(1, notification("Stopping cloud task"))
                    runningId = id
                    job = scope.launch {
                        try {
                            cancelCloud(RebyteApi(Secrets(this@AgentRunService).read()), id, "Stopped")
                        } catch (e: Exception) {
                            store.log(id, "Could not stop: ${e.message}")
                        } finally {
                            runningId = null
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
            }
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra(RUN_ID) ?: return START_NOT_STICKY
        if (job?.isActive == true) return START_NOT_STICKY
        runningId = id
        startForeground(1, notification("Connecting to Rebyte"))
        // Keeps an already unlocked screen usable only during the bounded task; never unlocks it.
        @Suppress("DEPRECATION")
        val taskLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "rebyte:task")
        wakeLock = taskLock.apply { acquire(12 * 60_000L) }
        job = scope.launch {
            var api: RebyteApi? = null
            try {
                val phone = requireNotNull(PhoneAccessibilityService.connected) { "Enable AirHand control in accessibility settings first." }
                taskOverlay = phone.overlay
                taskOverlay!!.show(id) {
                    startService(Intent(this@AgentRunService, AgentRunService::class.java).setAction(STOP).putExtra(RUN_ID, id))
                }
                delay(250) // Attach and draw the controls before any cloud-requested action.
                phone.requireVisibleControls(id)
                api = RebyteApi(Secrets(this@AgentRunService).read())
                run(api, store.get(id))
            } catch (e: CancellationException) {
                withContext(NonCancellable) { cancelCloud(api, id, "Stopped") }
            } catch (e: CallLimitReached) {
                cancelCloud(api, id, "Reached the 40-step limit")
            } catch (e: UncertainCall) {
                store.update(id, "needs_review")
                store.log(id, e.message!!)
            } catch (e: Exception) {
                val rejectedCreation = store.get(id).sessionId == null && e is ApiFailure &&
                    e.status in 400..499 && e.status !in listOf(408, 409, 429)
                store.update(id, if (rejectedCreation) "failed" else "paused")
                store.log(id, if (rejectedCreation) "Cloud task rejected: ${e.message}" else
                    "Task paused: ${e.message}\nReconnect to continue. Completed actions will not be repeated automatically.")
            } finally {
                val ending = when (store.get(id).state) {
                    "completed" -> "Task complete"
                    "cancelled" -> "Task stopped"
                    "failed" -> "Task failed. See activity in AirHand."
                    else -> "Task paused. See activity in AirHand."
                }
                taskOverlay?.finish(id, ending)
                wakeLock?.let { if (it.isHeld) it.release() }
                runningId = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, AgentRunService::class.java).setAction(STOP).putExtra(RUN_ID, runningId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "agent").setSmallIcon(R.drawable.ic_airhand)
            .setContentTitle("AirHand is operating your phone").setContentText(text).setContentIntent(open)
            .setOngoing(true).addAction(Notification.Action.Builder(null, "Stop task", stop).build()).build()
    }
    private fun status(id: String, message: String) {
        store.log(id, message)
        getSystemService(NotificationManager::class.java).notify(1, notification(message))
    }
    private fun overlayStatus(id: String, phase: String) {
        taskOverlay?.update(id, currentStep, currentAction, phase)
        getSystemService(NotificationManager::class.java).notify(1, notification("$currentAction · $phase"))
    }
    private suspend fun cancelCloud(api: RebyteApi?, id: String, reason: String) {
        val run = store.get(id)
        if (api == null) {
            store.update(id, "paused"); store.log(id, "$reason; cloud cancellation could not be verified"); return
        }
        try {
            val session = run.sessionId ?: if (run.state != "created") api.findSession(run) else null
            if (session != null) api.cancel(session, run.id)
            store.update(id, "cancelled", session)
            store.log(id, "$reason; cloud cancellation accepted")
        } catch (e: Exception) {
            store.update(id, "paused")
            store.log(id, "$reason; cloud cancellation unconfirmed: ${e.message}")
        }
    }
    private suspend fun run(api: RebyteApi, initial: Run) {
        requireNotNull(PhoneAccessibilityService.connected) { "Enable AirHand control in accessibility settings first." }
        val session = initial.sessionId ?: if (initial.state == "created") {
            store.update(initial.id, "creating")
            api.create(initial).getString("id").also { store.update(initial.id, "running", it) }
        } else api.recover(initial).also { store.update(initial.id, "running", it) }
        store.update(initial.id, "running", session)
        store.log(initial.id, "Connected to Rebyte · ${initial.model}\nSession: $session")
        currentAction = "Preparing the next step"
        overlayStatus(initial.id, "Waiting for cloud")
        val deadline = android.os.SystemClock.elapsedRealtime() + 10 * 60_000
        var calls = store.callCount(initial.id)
        while (currentCoroutineContext().isActive) {
            requireNotNull(PhoneAccessibilityService.connected) { "Accessibility service disconnected." }.requireVisibleControls(initial.id)
            if (android.os.SystemClock.elapsedRealtime() > deadline) {
                cancelCloud(api, initial.id, "Reached the task time limit")
                return
            }
            val state = api.retrieve(session)
            when (state.getString("status")) {
                "requires_action" -> {
                    val actions = state.getJSONArray("required_actions")
                    for (i in 0 until actions.length()) {
                        currentCoroutineContext().ensureActive()
                        val action = actions.getJSONObject(i)
                        check(action.getString("type") == "function_call") { "Unexpected required action" }
                        val name = action.getString("name")
                        val args = action.getJSONObject("arguments")
                        PhoneTools.validate(name, args)
                        val callId = action.getString("call_id")
                        val fingerprint = PhoneTools.fingerprint(name, action.getString("turn_id"), args)
                        var result = store.claim(initial.id, callId, fingerprint)
                        if (result == null) {
                            val phone = requireNotNull(PhoneAccessibilityService.connected) { "Accessibility service disconnected." }
                            currentStep = ++calls
                            currentAction = phone.describe(name, args)
                            store.log(initial.id, "Step $currentStep · $currentAction")
                            overlayStatus(initial.id, "Working · Stop at any time")
                            result = JSONObject().put("type", "agent.session.input.tool_result")
                                .put("turn_id", action.getString("turn_id")).put("call_id", callId)
                            try {
                                delay(250) // Show the concrete action before performing it.
                                val output = withTimeout(15_000) { phone.execute(name, args) }
                                result.put("success", true).put("output", output)
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) {
                                result.put("success", false).put("error", e.message ?: e.javaClass.simpleName)
                                store.log(initial.id, "Action failed: ${e.message}")
                            }
                            store.finishCall(initial.id, callId, result)
                        }
                        overlayStatus(initial.id, if (result.optBoolean("success")) "Done · Waiting for next step" else "Action failed · Waiting for cloud")
                        api.result(session, callId, result)
                    }
                }
                "idle" -> {
                    val turn = api.latestTurn(session)
                    val outcome = turn.getString("status")
                    store.update(initial.id, outcome)
                    if (outcome == "completed") status(initial.id, api.answer(session))
                    else status(initial.id, "Cloud task $outcome: ${turn.opt("error")}")
                    return
                }
                "failed" -> error("Cloud session failed: ${state.opt("error")}")
                "in_progress" -> Unit
                else -> error("Unknown session status: ${state.getString("status")}")
            }
            delay(1200)
        }
    }
    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
