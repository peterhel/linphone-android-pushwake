/*
 * Copyright (c) 2010-2026 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.core.tools.Log
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Receives UnifiedPush events so the app can be woken by a Google-free push to (re-)REGISTER
 * to a SIP server that cannot send native (Flexisip/RFC 8599) push — e.g. a plain
 * Asterisk or Kamailio server. This is an alternative to [CoreKeepAliveThirdPartyAccountsService]
 * for third-party accounts: instead of staying permanently alive (battery), the app registers
 * a UnifiedPush endpoint and is woken on demand.
 *
 * Flow: the app registers with a distributor (ntfy, /e/OS's built-in distributor, …) and gets
 * an endpoint URL in [onNewEndpoint]; that URL is given to the SIP server (or a proxy). When a
 * call comes in, the server POSTs to the endpoint, the distributor wakes us in [onMessage], and
 * we bring the Core up and refresh registrations so the incoming INVITE can be received.
 */
class UnifiedPushReceiver : MessagingReceiver() {
    companion object {
        private const val TAG = "[UnifiedPush Receiver]"

        // The recommended fallback push helper app when the OS doesn't provide its own.
        const val NTFY_PACKAGE = "io.heckel.ntfy"

        /** All installed UnifiedPush distributors (push helper apps), by package name. */
        fun availableDistributors(context: Context): List<String> {
            return UnifiedPush.getDistributors(context)
        }

        fun hasDistributor(context: Context): Boolean = availableDistributors(context).isNotEmpty()

        /**
         * The OS's own distributor if there is one — a **system app** (e.g. /e/OS's built-in
         * push, or an embedded FCM-based one). When present we use it silently; otherwise the
         * user is asked to pick which installed app should handle push.
         */
        fun systemDistributor(context: Context): String? {
            val pm = context.packageManager
            return availableDistributors(context).firstOrNull { isSystemApp(pm, it) }
        }

        /** Register for push with a specific distributor; endpoint arrives in [onNewEndpoint]. */
        fun registerWith(context: Context, distributor: String) {
            Log.i("$TAG Registering for push via distributor [$distributor]")
            UnifiedPush.saveDistributor(context, distributor)
            UnifiedPush.register(context)
        }

        /**
         * Auto-register without any UI (used on app start): keep the user's previously-picked
         * distributor if it is still installed, else the OS's own, else ntfy, else whatever is
         * available. Returns false if nothing is installed. The interactive toggle instead uses
         * [systemDistributor] + a picker so the choice is explicit when it isn't obvious.
         */
        fun register(context: Context): Boolean {
            val distributors = availableDistributors(context)
            if (distributors.isEmpty()) {
                Log.w("$TAG No push helper app (distributor) installed, can't register")
                return false
            }
            val saved = UnifiedPush.getSavedDistributor(context)?.takeIf { it in distributors }
            val chosen = saved
                ?: systemDistributor(context)
                ?: distributors.firstOrNull { it == NTFY_PACKAGE }
                ?: distributors.first()
            registerWith(context, chosen)
            return true
        }

        fun unregister(context: Context) {
            Log.i("$TAG Unregistering from UnifiedPush")
            UnifiedPush.unregister(context)
        }

        /**
         * FCM build: fetch the FCM registration token and advertise it as
         * `pn-provider=firebase;pn-prid=<token>` in the REGISTER Contact, so a plain Asterisk +
         * the asterisk-unifiedpush-wake AGI can push it. liblinphone doesn't advertise it for a
         * non-Flexisip server. No-op when Firebase isn't configured (i.e. the UnifiedPush build).
         */
        fun advertiseFcmToken(context: Context) {
            if (com.google.firebase.FirebaseApp.getApps(context).isEmpty()) {
                Log.i("$TAG No Firebase app configured; skipping FCM token advertise")
                return
            }
            try {
                com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                    .addOnSuccessListener { token ->
                        if (token.isNullOrEmpty()) {
                            Log.w("$TAG FCM token is empty")
                        } else if (coreContext.isReady()) {
                            Log.i("$TAG Advertising FCM token in REGISTER Contact")
                            coreContext.postOnCoreThread { core ->
                                for (account in core.accountList) {
                                    val accountParams = account.params.clone()
                                    accountParams.contactUriParameters = "pn-provider=firebase;pn-prid=$token"
                                    account.params = accountParams
                                }
                                core.refreshRegisters()
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("$TAG Failed to fetch FCM token: $e")
                    }
            } catch (e: Exception) {
                Log.w("$TAG Could not advertise FCM token: $e")
            }
        }

        private fun isSystemApp(pm: PackageManager, packageName: String): Boolean {
            return try {
                (pm.getApplicationInfo(packageName, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        Log.i("$TAG Received new endpoint for instance [$instance]: [${endpoint.url}]")
        // Store it (for display) and advertise it in the SIP REGISTER so the server learns it.
        corePreferences.unifiedPushEndpoint = endpoint.url
        advertiseEndpointInRegister(endpoint.url)
    }

    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        Log.i("$TAG Push message received for instance [$instance], handling as a call wake-up")
        // Hold the CPU awake for ~30s so the device doesn't doze again before the incoming
        // INVITE arrives (a few seconds after this push). A partial wake lock CAN be taken
        // from a background broadcast — unlike a foreground service, which Android blocks here
        // (BackgroundServiceStartNotAllowedException) and is why "warm but dozing" missed calls.
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "linphone:unifiedpush-call-wake")
                .apply { setReferenceCounted(false); acquire(30_000L) }
        } catch (e: Exception) {
            Log.w("$TAG Could not acquire wake lock for incoming call: $e")
        }
        coreContext.startKeepAliveService()
        if (coreContext.isReady()) {
            coreContext.postOnCoreThread { core ->
                // Warm path: the Core is already running but the device is dozing, so its UDP
                // registration binding is stale and the server's INVITE can't reach it. Mark
                // the Core foreground (keeps it active, out of battery-saving background mode)
                // and refresh the registration so the binding is live when the INVITE arrives.
                // (processPushNotification(callId) is for Flexisip pushes that carry a call-id;
                // with our generic wake there's no call-id, so it's a no-op — don't use it.)
                Log.i("$TAG Push wake: entering foreground + refreshing registration for the call")
                core.enterForeground()
                core.refreshRegisters()

                // Race guard: on the edge (Flexisip fork-late) path the held INVITE can reach
                // us and hit IncomingReceived BEFORE this wake ran, while the app was still
                // backgrounded — so the in-call foreground service start was denied and the
                // call is ringing with no UI. Now that we've entered the foreground, re-surface
                // any already-arrived incoming call so it becomes visible/answerable (the
                // NotificationsManager fullScreenIntent fallback covers the same case at the
                // notification layer). No-op in the normal wake-then-INVITE order.
                if (core.callsNb > 0) {
                    Log.w("$TAG A call already arrived before the wake completed; (re)surfacing its incoming UI")
                    coreContext.notificationsManager.showIncomingCallNotificationIfNeeded()
                }
            }
        } else {
            // Cold start: the Application is bringing the Core up; it will handle the call on start.
            Log.w("$TAG Core not ready yet; it is being started and will handle the call on start")
        }
    }

    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {
        Log.e("$TAG UnifiedPush registration failed for instance [$instance]: [$reason]")
    }

    override fun onUnregistered(context: Context, instance: String) {
        Log.w("$TAG UnifiedPush unregistered for instance [$instance]")
        corePreferences.unifiedPushEndpoint = ""
        advertiseEndpointInRegister(null)
    }

    /**
     * Advertise (or, with a null url, clear) the UnifiedPush endpoint in every account's
     * REGISTER, as the RFC 8599 Contact-URI parameters
     * `pn-provider=unifiedpush;pn-prid=<url-encoded endpoint>`, then refresh registrations so
     * the server learns each device's push topic automatically — no manual server config.
     */
    private fun advertiseEndpointInRegister(url: String?) {
        if (!coreContext.isReady()) return
        coreContext.postOnCoreThread { core ->
            val params = if (url.isNullOrEmpty()) {
                ""
            } else {
                "pn-provider=unifiedpush;pn-prid=${Uri.encode(url)}"
            }
            for (account in core.accountList) {
                val accountParams = account.params.clone()
                accountParams.contactUriParameters = params
                account.params = accountParams
            }
            Log.i("$TAG Updated Contact URI push params on ${core.accountList.size} account(s)")
            core.refreshRegisters()
        }
    }
}
