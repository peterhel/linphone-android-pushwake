# Google-free / self-hosted push-wake for linphone-android

**Receive incoming calls on a battery — against a plain Asterisk/Kamailio, with no Flexisip and
(optionally) no Google.** This fork lets linphone-android be woken on demand for an incoming
call by a *self-hosted* SIP server, via either **UnifiedPush** (de-Googled) or **FCM**, instead
of staying permanently registered with a battery-draining foreground service.

Proven end-to-end — **warm, cold, *and* dozing** — on real hardware:

| Device | OS | Transport | Status |
|---|---|---|---|
| Fairphone 5 | /e/OS (Murena) | UnifiedPush (built-in distributor) | ✅ warm + cold + dozing |
| Galaxy Tab A8 | Android 14 (Samsung) | FCM | ✅ warm + cold + dozing |
| Pixel | GrapheneOS | UnifiedPush (ntfy / sandboxed Play) | ⏳ wiring |
| iPad / iPhone | iOS | APNs | ⏳ (same approach, see notes) |

The server half is a tiny Asterisk AGI:
[**asterisk-unifiedpush-wake**](https://codeberg.org/exit0/asterisk-unifiedpush-wake) (MIT).

---

## Why

Stock linphone-android receives background calls only via **FCM + a Flexisip push gateway** —
i.e. Google services *and* Belledonne's push infrastructure (or your own Flexisip). For a
third-party server that can't send native RFC 8599 push — plain **Asterisk**, Kamailio — the
only option is `CoreKeepAliveThirdPartyAccountsService`: a permanent foreground service that
holds the registration alive 24/7 and drains the battery.

This fork adds **on-demand wake** for exactly those accounts. The app advertises a push target
in its REGISTER; the server pushes to it when a call comes; the app wakes, re-registers, and
rings — then goes back to sleep.

## How it works

```
REGISTER  → Contact: <sip:…;pn-provider=<transport>;pn-prid=<token-or-endpoint>>   (RFC 8599, self-advertised)
call in   → server reads pn-prid from the registered Contact
          → pushes:  UnifiedPush → HTTP POST to the endpoint
                     FCM         → high-priority data message
                     APNs        → VoIP push
          → device wakes:  wake lock + enterForeground() + refreshRegisters()      (re-announces a FRESH live port)
          → server's dialplan Waits a few seconds, then Dials the fresh Contact
          → device rings, with a real incoming-call screen
```

The client **advertises its own push target in the REGISTER Contact URI** (RFC 8599 `pn-prid`),
so the server learns each device's topic automatically — nothing is hardcoded server-side, and
it scales to a whole fleet.

## The two things that were actually hard

If you replicate this, these are the non-obvious bits that cost the most time:

### 1. The push isn't just a battery optimization — it unlocks the call *UI*

On Android 12+, a **backgrounded app cannot start a foreground service**
(`Background started FGS: Disallowed` for `CoreInCallService`). So when a call arrives, the
ringtone may play (audio focus is allowed) but **no incoming-call screen appears** — it lands
as a missed call. Granting `USE_FULL_SCREEN_INTENT` does *not* fix this; it's the FGS start
that's blocked.

A **high-priority push** (FCM/UnifiedPush) grants a ~20-second foreground-service-start
exemption (`reasonCode:PUSH_MESSAGING` in the `ActivityManager` log). *That* is what lets
`CoreInCallService` start → the `Notification.CallStyle` notification post → the swipe-to-answer
screen render. **Without the push there is no call UI at all** for a backgrounded app. The push
is load-bearing for the UI, not just for battery.

### 2. The wake must re-REGISTER — a dozing device's port is stale

A dozing device's last-registered UDP port dies: the router drops the NAT mapping after ~30-60s
of inactivity. So the Contact the server has on file points at a dead binding and the INVITE
black-holes — even though the device looks "registered."

So on push, the client must `enterForeground()` + `refreshRegisters()` to re-announce a **fresh,
live port**. The server-side dialplan is **wake-first** — push, then `Wait(6)` for that fresh
REGISTER to land, *then* `Dial` — and the AOR uses `max_contacts=1` + `remove_existing=yes` so
the fresh REGISTER evicts the stale Contact (otherwise the Dial forks to dead ports).

> liblinphone's *stock* FCM handler only acts on Flexisip pushes (which carry a call-id), so on
> our generic wake payload it does nothing. That's why this fork replaces it with
> `PushMessagingService`, which does the wake-refresh — the FCM analogue of the UnifiedPush
> `onMessage` handler.

## What changed (client side)

**UnifiedPush path** (de-Googled):
| File | Change |
|---|---|
| `core/UnifiedPushReceiver.kt` | New `MessagingReceiver`: `onNewEndpoint` advertises the endpoint as `pn-provider=unifiedpush;pn-prid=<url>` in each account's Contact; `onMessage` does wake-lock + `enterForeground` + `refreshRegisters`. Companion has distributor auto-pick + the FCM-token advertise helper. |
| `core/CorePreferences.kt` | `useUnifiedPush` (default false), `unifiedPushEndpoint`. |
| `LinphoneApplication.kt` | After `coreContext.start()`: UnifiedPush register **or** (FCM build) `advertiseFcmToken()`. |
| `AndroidManifest.xml` | UnifiedPush broadcast receiver. |
| `settings_advanced_fragment.xml`, `strings.xml`, `SettingsViewModel.kt` | Friendly "Receive calls in the background using push (saves battery)" toggle in **Advanced** settings, next to and **mutually exclusive** with the keep-alive service. Off by default — no-push accounts fall back to the keep-alive service automatically. |
| `gradle/libs.versions.toml`, `app/build.gradle.kts` | `org.unifiedpush.android:connector:3.3.3` + Tink/protobuf dedup. |

**FCM path** (Google Android, e.g. Samsung that kills UnifiedPush sockets):
| File | Change |
|---|---|
| `core/CoreContext.kt` | Keep liblinphone's **native push management OFF** for non-Flexisip domains (`pushNotificationAllowed=false`). If left on, liblinphone owns the Contact push params and **wipes** the token we advertise manually. |
| `core/UnifiedPushReceiver.advertiseFcmToken()` | Fetch the FCM token via `FirebaseMessaging` and set `pn-provider=firebase;pn-prid=<token>` in the Contact (liblinphone won't advertise it for a plain server). |
| `core/PushMessagingService.kt` | New. Replaces liblinphone's stock FCM service in the manifest; on message does the **wake-refresh** (wake-lock + `enterForeground` + `refreshRegisters`); re-advertises on token rotation. |

**Both:**
| File | Change |
|---|---|
| `MainViewModel.kt` | Auto-prompt for `USE_FULL_SCREEN_INTENT` so the lockscreen call screen is allowed. |

## Build

```bash
source ~/Android/env.sh            # JDK 21 + Android SDK
# UnifiedPush (de-Googled): remove app/google-services.json, then:
./gradlew :app:assembleRelease --no-daemon
# FCM: drop your google-services.json into app/, then assembleRelease.
```
Release builds turn liblinphone logging off, so diagnose **server-side**
(`pjsip show history`, the Contact's `pn-provider`) and via Android system logs
(`ActivityManager` FGS lines, `MediaFocusControl`), not app logcat.

## Enable

1. **De-Googled build:** works out of the box — with no push configured, a third-party account
   falls back to an always-on **keep-alive background service** (stays registered; costs battery).
   For **battery-friendly on-demand wake**, install a UnifiedPush distributor — the
   **[ntfy](https://f-droid.org/packages/io.heckel.ntfy/)** app (F-Droid or Play) or your OS's
   built-in one — then turn on **Settings → Advanced → "Receive calls in the background using
   push"**. Push and the keep-alive service are **mutually exclusive** (enabling one disables the
   other). **FCM build:** nothing to enable — the token is advertised automatically.
2. Set up the [server AGI](https://codeberg.org/exit0/asterisk-unifiedpush-wake): wake-first
   dialplan + `max_contacts=1`/`remove_existing` on the AOR.
3. Background/lock the device, place a call → it wakes and rings with a real call screen.

## Known gaps / honest caveats

- The manual advertise sets `contactUriParameters` on **all** accounts; a refined version would
  scope it to the self-hosted account(s).
- **True multi-device per extension** (one person, several devices) needs per-device contact
  replacement (SIP outbound / reg-id) so port-churn doesn't pile up stale contacts; raising
  `max_contacts` alone reintroduces the dead-port problem. Single-device is clean.
- No WebPush encryption (plaintext wake POSTs assumed).
- **Upstreaming:** this is a *system* (client + server), and Belledonne sells the Flexisip path,
  so a direct merge is unlikely. The UnifiedPush portion is the most FOSS-aligned candidate —
  gauge interest via an issue before a polished PR.
