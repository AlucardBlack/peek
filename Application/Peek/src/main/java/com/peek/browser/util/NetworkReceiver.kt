/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import com.peek.browser.webrender.WebRenderer

class NetworkReceiver(private val webRenderer: WebRenderer) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val conn = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkInfo = conn.activeNetworkInfo

        // If there is a connection reload the webivew.
        if (networkInfo != null) {
            webRenderer.reload()
            try {
                // We had a crash here "Receiver not registered"
                context.unregisterReceiver(this)
            } catch (exc: IllegalArgumentException) {
                exc.printStackTrace()
            }
        }
    }
}
