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
import android.os.PowerManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.tools.Log

/**
 * Handles the high-priority FCM push our plain (non-Flexisip) Asterisk sends — via the
 * asterisk-unifiedpush-wake AGI — to wake the app for an incoming call.
 *
 * liblinphone's stock FCM handler only acts on Flexisip pushes (which carry a call-id), so on our
 * generic wake payload it does nothing: the device wakes but never re-REGISTERs, leaving the server
 * with a stale NAT/UDP binding the INVITE can't reach (the doze'd router has dropped the mapping).
 *
 * So we mirror [UnifiedPushReceiver.onMessage]: hold the CPU briefly, mark the Core foreground, and
 * refresh the registration so the binding is live when the INVITE arrives a few seconds later
 * (after the dialplan's Wait). The high-priority push also grants the foreground-service-start
 * exemption that lets the in-call UI show.
 */
class PushMessagingService : FirebaseMessagingService() {
    companion object {
        private const val TAG = "[FCM Messaging Service]"
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Log.i("$TAG Push received, waking up to receive the incoming call")
        // A partial wake lock CAN be taken from a background FCM message (unlike a foreground
        // service start, which Android blocks here). Hold the CPU ~30s so the device doesn't doze
        // again before the INVITE arrives.
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "linphone:fcm-call-wake")
                .apply { setReferenceCounted(false); acquire(30_000L) }
        } catch (e: Exception) {
            Log.w("$TAG Could not acquire wake lock for incoming call: $e")
        }
        coreContext.startKeepAliveService()
        if (coreContext.isReady()) {
            coreContext.postOnCoreThread { core ->
                // Warm path: Core is up but the device dozed, so its UDP registration binding is
                // stale. Mark the Core foreground and refresh the registration so the binding is
                // live (and a fresh Contact replaces the stale one) when the INVITE arrives.
                Log.i("$TAG Push wake: entering foreground + refreshing registration for the call")
                core.enterForeground()
                core.refreshRegisters()
            }
        } else {
            // Cold start: the Application is bringing the Core up; it will register on start.
            Log.w("$TAG Core not ready yet; it is being started and will handle the call on start")
        }
    }

    override fun onNewToken(token: String) {
        Log.i("$TAG FCM token refreshed, re-advertising it in REGISTER")
        if (token.isEmpty() || !coreContext.isReady()) return
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
