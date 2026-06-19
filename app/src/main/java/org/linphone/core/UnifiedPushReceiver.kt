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

        /** True if any UnifiedPush distributor (push helper app) is installed. */
        fun hasDistributor(context: Context): Boolean {
            return UnifiedPush.getDistributors(context).isNotEmpty()
        }

        /**
         * Register for push with the best available distributor. The endpoint is delivered
         * asynchronously to [onNewEndpoint]. Returns false (without doing anything) if no
         * distributor is installed, so the caller can guide the user to install one.
         * Safe to call on every app start.
         */
        fun register(context: Context): Boolean {
            val distributor = pickDistributor(context)
            if (distributor == null) {
                Log.w("$TAG No push helper app (distributor) installed, can't register")
                return false
            }
            Log.i("$TAG Registering for push via distributor [$distributor]")
            UnifiedPush.saveDistributor(context, distributor)
            UnifiedPush.register(context)
            return true
        }

        fun unregister(context: Context) {
            Log.i("$TAG Unregistering from UnifiedPush")
            UnifiedPush.unregister(context)
        }

        /**
         * Pick a distributor with the least surprising preference for a non-technical user:
         * the OS's own (a system app — e.g. /e/OS's built-in, which is always running) first,
         * then ntfy, then whatever else is installed.
         */
        private fun pickDistributor(context: Context): String? {
            val distributors = UnifiedPush.getDistributors(context)
            if (distributors.isEmpty()) return null
            val pm = context.packageManager
            return distributors.firstOrNull { isSystemApp(pm, it) }
                ?: distributors.firstOrNull { it == NTFY_PACKAGE }
                ?: distributors.first()
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
        // Store it so it can be shown to the user / handed to the SIP server configuration.
        corePreferences.unifiedPushEndpoint = endpoint.url
    }

    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        Log.i(
            "$TAG Push message received for instance [$instance], waking Core to refresh REGISTER(s)"
        )
        // Keep the process alive long enough to (re-)register and receive the pending call.
        // (Safe no-op if it can't be started; it try/catches internally.)
        coreContext.startKeepAliveService()
        if (coreContext.isReady()) {
            coreContext.postOnCoreThread { core ->
                Log.i("$TAG Refreshing registrations after push wake-up")
                core.refreshRegisters()
            }
        } else {
            // Cold start: the Application is bringing the Core up, and core.start() registers
            // all accounts on its own, so the pending call will be reachable once it's started.
            Log.w("$TAG Core not ready yet; it is being started and will register on start")
        }
    }

    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {
        Log.e("$TAG UnifiedPush registration failed for instance [$instance]: [$reason]")
    }

    override fun onUnregistered(context: Context, instance: String) {
        Log.w("$TAG UnifiedPush unregistered for instance [$instance]")
        corePreferences.unifiedPushEndpoint = ""
    }
}
