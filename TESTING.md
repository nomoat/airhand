# Testing

## Automated checks

Run `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
Nineteen JVM tests cover tool schemas, argument types and integer overflow,
screenshot coordinate conversion, and avoiding the floating control during
physical gestures. An impossible collision is rejected, not hidden. Run-link
tests cover inline Agent IDs, absent identities, and invalid path components.
Budget tests cover the default, per-run request serialization, rejecting unlimited
requests, exact server confirmation, unknown usage, budget errors, and terminal state.

Lint is required to finish without errors. Localization, deprecated Android
UI API, and style warnings are still present in this prototype. The application
currently has English copy only; translations have not been added.

## Device verification

The accessibility execution path has been exercised without root on an Android
15 Google Play emulator and on a Pixel 10 running Android 17. The app opened
YouTube, searched Google, and played a regular video from Google's channel.
Successive screenshots showed changing frames and the playback clock advancing.
The control stayed visible and moved out of the Search target's way.

On the emulator, dragging the control and tapping Stop cancelled the run,
released the task wake lock, and stopped the foreground task service. The
completion control disappeared after six seconds. An earlier process-loss test
verified that an interrupted call with an unknown result was not repeated.

Version 0.3.2 was also checked on the Android 15 emulator with a 15-second screen
timeout and stay-awake-while-charging disabled. During a real task waiting in
Android Settings, the screen stayed bright after 72 seconds without user input.
The window manager attributed the screen-on request to AirHand's floating control.
Tapping Stop released both screen-on requests, and the screen then slept normally.
The original test-device settings were restored. The clear-input button was also
checked with empty and nonempty text; it clears the field, hides when empty, and
allows typing again. The voice button is no longer present.

Version 0.3.3 was upgraded over 0.3.2 on the Android 15 emulator. The database
migration preserved the existing run and encrypted key. Tapping that older run
retrieved and saved its Platform link and launched the browser. A new real Rebyte
task completed and saved its link automatically. Both entries remained visible
in newest-first order after a process restart with Wi-Fi and mobile data disabled.
The empty-history screen was also checked with an isolated database, then the
original test history and network settings were restored. The Platform URL was
checked against Rebyte's session route; viewing the cloud details requires a
browser login with access to the owning organization.

Version 0.3.4 was upgraded over 0.3.3 on the same emulator. Existing rows and the
encrypted key survived the schema upgrade; older runs kept a null budget. The
default $0.20 option and a saved $0.50 selection were checked across a process
restart. In a real Rebyte test, a task requested thirty successive `phone_wait`
calls with a $0.10 budget. Rebyte confirmed the limit and ended the task after ten
calls with 10 reported credits. AirHand showed **Budget reached**, offered no
Reconnect, and released its task service and screen-on requests. Changing the
selection afterward did not change that run's saved budget or usage. This test
does not establish a strict dollar ceiling: the final call can exceed a budget.

These checks do not establish compatibility with every app or Android vendor.
Xiaomi/Redmi/POCO hardware and release installation
on the intended Xiaomi test phone remain to be verified. English UI changes and
release packaging receive a separate build and artifact check.

## Manual checks for a new device

1. Install the APK, enter a key, and enable AirHand control in accessibility.
2. Check that the home screen shows **Ready** and that app labels are English.
3. Run a small task in an installed app. Confirm the floating control remains
   visible, updates each step, and does not cover the action target.
4. Drag the control. Confirm it stays within the screen bounds.
5. Stop a running task. Confirm no further agent actions occur. The target app
   can keep doing its own work, such as playing a video.
6. Try a task with text entry and confirm the keyboard remains usable.
   Check that **Clear task** empties the task field and the × button hides when empty.
7. Lock the phone during a task. Confirm AirHand pauses instead of trying to
   unlock it. Unlock before reconnecting.
8. Temporarily set a short screen timeout on a test device. While a task waits
   in another app, verify the screen remains bright beyond that timeout. Stop
   the task and verify the normal screen timeout resumes. Restore the test setting.
9. Open **Run history**. Confirm tasks are newest first, with a time and status.
   Tap a completed run and check that the browser opens its Rebyte session page.
   Return to AirHand and verify the history survives restarting the app.
10. Check history offline and with an empty database. On an upgrade from 0.3.2,
    confirm existing tasks remain and that an older run's link is saved after
    its first lookup. Tasks without a cloud session should not open a broken URL.
11. Check the default $0.20 model budget. Select $0.50, restart, and confirm the
    selection remains. Start a task and verify the Session reports the matching
    `agent.budget.max_credits`. A paused task must retain its original budget even
    after changing the selection for new tasks.
12. On a test account, run a bounded sequence with a small model budget. Confirm
    `session_budget_exceeded` ends the task, releases its screen-on requests, shows
    **Budget reached**, and offers no Reconnect. The history should retain the
    budget and reported usage. The last model call can exceed the selected budget.

For debug builds, inspect the journal with:

```sh
python3 scripts/inspect_emulator.py --serial YOUR_DEVICE --output run.json
```

Do not publish this output by default: task text and observations may be private.
Avoid `uiautomator dump` during an active task because UI automation can displace
Android's accessibility service and invalidate the test.
