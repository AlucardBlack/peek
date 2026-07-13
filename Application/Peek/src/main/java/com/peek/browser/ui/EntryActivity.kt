/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import androidx.preference.PreferenceManager
import android.widget.Toast
import com.peek.browser.Config
import com.peek.browser.Constant
import com.peek.browser.MainApplication
import com.peek.browser.MainController
import com.peek.browser.R
import com.peek.browser.Settings
import java.net.MalformedURLException
import java.net.URL

class EntryActivity : Activity() {

    private val mHandler = Handler()

    override fun onCreate(savedInstanceState: Bundle?) {

        sCurrentInstance = this

        val intent = intent
        var isActionView = false
        var isActionSend = false
        if (intent != null && intent.action != null) {
            isActionView = intent.action == Intent.ACTION_VIEW
            isActionSend = intent.action == Intent.ACTION_SEND
        }

        super.onCreate(savedInstanceState)

        PreferenceManager.setDefaultValues(this, R.xml.preferences, true)

        if (isActionView || isActionSend) {
            var openLink = false

            var url = intent.dataString

            if (isActionSend) {
                val type = intent.type
                val extras = intent.extras
                if (type != null && type == "text/plain" && extras!!.containsKey(Intent.EXTRA_TEXT)) {
                    val text = extras.getString(Intent.EXTRA_TEXT)
                    val splitText = text!!.split(" ")
                    for (s in splitText) {
                        try {
                            val _url = URL(s)
                            url = _url.toString()
                            openLink = true
                            break
                        } catch (ex: MalformedURLException) {
                        }
                    }

                    if (openLink == false) {
                        Toast.makeText(this, R.string.invalid_send_action, Toast.LENGTH_SHORT).show()
                        finish()
                        return
                    }
                }
            }

            if (null == url) {
                url = ""
            }
            // Special case code for the setting the default browser. If this URL is received, do nothing.
            if (url == Config.SET_DEFAULT_BROWSER_URL) {
                Toast.makeText(this, R.string.default_browser_set, Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            val openedFromAppName: String? = null
            val canLoadFromThisApp = true
            if (Settings.get().isEnabled()) {
                openLink = true
            }

            if (canLoadFromThisApp == false) {
                MainApplication.openInBrowser(this, intent, true)
            } else if (openLink) {
                MainApplication.checkRestoreCurrentTabs(this)

                MainApplication.openLink(this, url, true, openedFromAppName)
            } else {
                MainApplication.openInBrowser(this, intent, true)
            }
        } else {
            startActivityForResult(Intent(this, HomeActivity::class.java), 0)
        }

        finish()
    }

    override fun onDestroy() {
        if (sCurrentInstance === this) {
            sCurrentInstance = null
        }

        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()

        sCurrentInstance = this
    }

    override fun onStop() {
        super.onStop()

        delayedFinishIfCurrent()
    }

    override fun onBackPressed() {
        delayedFinishIfCurrent()
    }

    fun delayedFinishIfCurrent() {
        // Kill the activity to ensure it is not alive in the event a link is intercepted,
        // thus displaying the ugly UI for a few frames

        mHandler.postDelayed({
            if (sCurrentInstance === this) {
                finish()
            }
        }, 500)
    }

    companion object {
        var sCurrentInstance: EntryActivity? = null
    }
}
