# PR: UnifiedPush support for Google-free incoming-call wake-up

**Branch:** `feat/unifiedpush`
**Status:** builds (`:app:assembleDebug` → APK), ktlint-clean, and **wake verified on real /e/OS hardware** (Fairphone 5): POST → `onMessage` → re-REGISTER seen on the server. See gaps for what's left to confirm.

## Motivation

Linphone receives background calls via FCM push, which requires Google services **and**
a Flexisip push gateway. For third-party SIP servers that can't send native (RFC 8599)
push — plain **Asterisk**, Kamailio, etc. — the only option today is
`CoreKeepAliveThirdPartyAccountsService`, a permanent foreground service that drains
battery (the app must hold the registration alive 24/7).

This PR adds **UnifiedPush** as a Google-free, battery-friendly alternative for exactly
those accounts: the app registers a UnifiedPush endpoint with a distributor (ntfy, or the
distributor built into /e/OS / Murena, …) and is **woken on demand** to re-REGISTER, so it
doesn't have to stay alive.

## How it works

```
incoming call → SIP server (or a small proxy/dialplan hook) POSTs to the endpoint URL
              → the device's UnifiedPush distributor delivers a push
              → UnifiedPushReceiver.onMessage wakes the Core + refreshRegisters()
              → the server re-sends/holds the INVITE → the now-registered app rings
```

Concretely, with Asterisk the dialplan already does the server half (POST to a URL, wait,
re-`Dial`); this PR is the missing client half.

## Changes

| File | Change |
|---|---|
| `app/src/main/java/org/linphone/core/UnifiedPushReceiver.kt` | **New.** `MessagingReceiver` (connector 3.3.3). `onNewEndpoint` stores the endpoint URL; `onMessage` starts the keep-alive service + `postOnCoreThread { core.refreshRegisters() }`; companion `register()`/`unregister()` helpers. |
| `app/src/main/java/org/linphone/core/CorePreferences.kt` | `useUnifiedPush` (Bool, default **false**) gates registration; `unifiedPushEndpoint` (String) stores the URL to hand to the server. Config keys: `[app] use_unified_push`, `[app] unified_push_endpoint`. |
| `app/src/main/java/org/linphone/LinphoneApplication.kt` | After `coreContext.start()`, if `useUnifiedPush` → `UnifiedPushReceiver.register(context)`. |
| `app/src/main/AndroidManifest.xml` | Exported receiver with the UnifiedPush broadcast actions (`MESSAGE`, `NEW_ENDPOINT`, `REGISTRATION_FAILED`, `UNREGISTERED`, `TEMP_UNAVAILABLE`). |
| `SettingsViewModel.kt`, `settings_developer_fragment.xml`, `strings.xml` | **"Use UnifiedPush" toggle** in Developer settings: `toggleUnifiedPush()` flips `useUnifiedPush` and registers/unregisters immediately. |
| `gradle/libs.versions.toml`, `app/build.gradle.kts` | Add `org.unifiedpush.android:connector:3.3.3`. Exclude the connector's JVM Tink and align `tink-android` to 1.21.0 (avoids duplicate Tink/protobuf classes vs the app's `tink-android`/`protobuf-javalite`). |

## How to enable / test

1. Install a UnifiedPush **distributor** (the **ntfy** app, or use /e/OS's built-in one).
2. Enable the feature: **Settings → Developer → "Use UnifiedPush"** (or set
   `use_unified_push=1` under `[app]` in `.linphonerc`).
3. The app registers; grep logcat for `[UnifiedPush Receiver] Received new
   endpoint` to get the **endpoint URL**, also saved in `unifiedPushEndpoint`.
4. Point your SIP server at that URL. With Asterisk, `CURL(<endpoint>,...)` in the
   `1981` dialplan before the `Wait`+re-`Dial`.
5. Lock the phone, let it idle, place a call → it should wake and ring.

## Build

```
source ~/Android/env.sh            # JDK 21 + Android SDK under ~
./gradlew :app:assembleDebug --no-daemon
# → app/build/outputs/apk/debug/linphone-android-debug-*.apk  (≈129 MB)
```

## Known gaps / caveats (honest)

- **Device-tested on /e/OS (Fairphone 5).** Registration works (endpoint issued via the
  built-in Murena distributor); a POST to the endpoint while the app is running fires
  `onMessage` → `refreshRegisters()` → a fresh REGISTER reaches Asterisk (verified in
  `pjsip show history`). **Still to confirm:** the fully-cold case (process killed → woken
  by the broadcast) and the keep-the-process-alive-across-the-INVITE window, which are
  Doze/OEM-specific.
- **Endpoint not surfaced in UI.** It's stored (`unifiedPushEndpoint`) + logged, but there's
  no screen showing it / copy button yet — the user can't easily hand it to the server.
  (A throwaway test branch adds a notification for this; the real fix is a settings field.)
- **No WebPush encryption.** We assume plaintext pushes (Asterisk POSTs a wake signal). The
  connector's optional Web Push encryption (Tink) isn't exercised; encrypted-push setups
  would need that path verified (we exclude JVM Tink and rely on `tink-android`).
- **No Flexisip/RFC 8599 parity.** Intentionally out of scope — this is a generic wake, not
  a replacement for the FCM/`pushNotificationConfig` path.
- **Distributor selection** is automatic and tuned for non-technical users: it prefers the
  OS's own distributor (a system app — e.g. /e/OS's built-in, always running) then ntfy then
  anything installed. If **none** is installed, enabling the toggle pops a plain-language
  dialog offering to install ntfy (no "UnifiedPush" jargon shown to the user). An explicit
  manual picker (`connector-ui`) is still a possible follow-up.
- **Upstream:** Belledonne requires a CLA, and may prefer this wired into their push-settings
  UI and gated per-account rather than a global flag.

## Suggested next steps
1. Confirm the fully-cold wake (process killed) on a couple of OEMs (Doze).
2. Surface the endpoint in the UI (a read-only field + copy button) so it can be handed
   to the server; the **toggle** itself is already in Developer settings.
3. Consider per-account opt-in (only third-party/non-push accounts) instead of a global flag.
4. Optional: distributor picker via `connector-ui`.
