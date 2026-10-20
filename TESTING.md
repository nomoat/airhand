# Testing

## Automated checks

Run `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
Nine JVM tests cover tool schemas, argument types and integer overflow,
screenshot coordinate conversion, and avoiding the floating control during
physical gestures. An impossible collision is rejected, not hidden.

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

For debug builds, inspect the journal with:

```sh
python3 scripts/inspect_emulator.py --serial YOUR_DEVICE --output run.json
```

Do not publish this output by default: task text and observations may be private.
Avoid `uiautomator dump` during an active task because UI automation can displace
Android's accessibility service and invalidate the test.
