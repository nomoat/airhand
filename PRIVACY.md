# Privacy and data flow

AirHand is a user-initiated Android automation app. This document describes the
Android code in this source distribution, not the separate Rebyte cloud service's
retention terms.

## Screen content and tasks

During a task you start, the app sends your task text, launchable app names,
visible UI text, compressed screenshots, tool results, and task metadata to
`https://api.rebyte.ai/v1/agents/sessions`. Rebyte processes that information
with the model selected for your organization. Screen content may contain
personal information from other apps. Use only apps and content you intend to
share with that cloud service.

The control app's own screen is excluded from observation and remote control.
The API-key dialog blocks screenshots. Password fields are not exposed as text
in the UI tree and cannot be targeted for text entry; this is not a promise that
all sensitive content in other apps is detected or redacted from screenshots.
Screens protected by Android may reject capture.

## API key

You enter your own key. The app stores it locally, encrypted with a key held by
Android Keystore, and sends it to the Rebyte API for authentication. The app does
not intentionally include it in task logs. App backup is disabled. You can
remove the saved key using **Set API key → Delete key** when no task is running.
No key is included in the APK or source archive.

## Local history and cloud history

The app keeps task text, session IDs, activity messages, and cached tool results
in its private SQLite database. Cached results can include screenshots and UI
text. Uninstalling the app or clearing its Android app data removes local data.
This does not delete cloud session history. Manage cloud data and API access in
the organization that owns your key; the cloud provider's terms govern its
processing and retention.

## Permissions and screen behavior

Tasks use text input. AirHand does not request microphone permission or launch
a speech-recognition service.

Accessibility is used for screen reading, screenshots, taps, swipes, text entry,
system navigation, and visible task controls. A foreground notification provides
another Stop control. The floating window keeps the screen on at its normal
brightness during a task, including while another app is open. A bounded wake
lock also keeps an already unlocked screen available. These requests are released
when the task stops or ends; the app does not change the system screen timeout
or brightness settings. The app has no boot receiver and does not
start tasks on its own. There is no analytics or advertising SDK in this app.

Select **Stop** in the floating control or notification to halt local actions.
The app also requests cloud cancellation; network failures can prevent that
request from being confirmed. The activity log reports this state.
