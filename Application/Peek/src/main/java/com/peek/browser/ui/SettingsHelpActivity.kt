/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.preference.PreferenceFragmentCompat
import android.view.MenuItem
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.peek.browser.Constant
import com.peek.browser.MainApplication
import com.peek.browser.R
import com.peek.browser.util.Util

/*
 * This class exists solely because Android's PreferenceScreen implementation doesn't do anything
 * when the Up button is touched, and we need to go back in that case given our use of the Up button.
 */
class SettingsHelpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_settings_help)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.setTitle(R.string.preference_help_title)
        setSupportActionBar(toolbar)
        Util.padForStatusBarInset(toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back_white_24dp)
        toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.activity_close_enter, R.anim.activity_close_exit)
    }

    class SettingsHelpFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_help, rootKey)

            findPreference<androidx.preference.Preference>("preference_credits")!!.setOnPreferenceClickListener {
                showCreditsDialog()
                true
            }

            findPreference<androidx.preference.Preference>("preference_osl")!!.setOnPreferenceClickListener {
                showOpenSourceLicensesDialog()
                true
            }
        }

        private var mForceCrashCountdown = TAPS_TO_FORCE_A_CRASH
        var mForceCrashToast: Toast? = null

        fun showCreditsDialog() {
            val layout = View.inflate(requireActivity(), R.layout.view_credits, null)

            val builder = AlertDialog.Builder(requireActivity())
            builder.setNegativeButton(android.R.string.ok, null)
            builder.setView(layout)
            builder.setTitle(R.string.credits_title)

            val alertDialog = builder.create()
            alertDialog.setIcon(Util.getAlertIcon(requireActivity()))
            Util.showThemedDialog(alertDialog)
        }

        private fun showOpenSourceLicensesDialog() {
            val webView = WebView(requireActivity())
            webView.loadUrl("file:///android_asset/open_source_licenses.html")
            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    val i = Intent(Intent.ACTION_VIEW)
                    i.data = Uri.parse(url)
                    startActivity(i)
                    return true
                }
            }

            val builder = AlertDialog.Builder(requireActivity())
            builder.setIcon(Util.getAlertIcon(requireActivity()))
            builder.setNegativeButton(R.string.action_ok, null)
            builder.setView(webView)
            builder.setTitle(R.string.preference_osl_title)

            val alertDialog = builder.create()
            Util.showThemedDialog(alertDialog)
        }

        companion object {
            private const val TAPS_TO_FORCE_A_CRASH = 7
        }
    }
}
