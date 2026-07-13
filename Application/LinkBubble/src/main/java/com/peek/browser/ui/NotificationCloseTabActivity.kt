/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.app.Activity
import android.os.Bundle
import com.peek.browser.BuildConfig
import com.peek.browser.MainController

class NotificationCloseTabActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mainController = MainController.get()

        val intent = intent

        if (mainController != null) {
            //Log.d("blerg", "*** handle clasTab:" + intent.getIntExtra(EXTRA_DISMISS_NOTIFICATION, -1));
            mainController.closeTab(intent.getIntExtra(EXTRA_DISMISS_NOTIFICATION, -1))
        }

        finish()
    }

    companion object {
        const val EXTRA_DISMISS_NOTIFICATION = BuildConfig.APPLICATION_ID + ".notification"
    }
}
