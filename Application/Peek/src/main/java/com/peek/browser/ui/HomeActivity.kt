/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.Manifest
import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.text.Html
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.peek.browser.Constant
import com.peek.browser.MainApplication
import com.peek.browser.MainController
import com.peek.browser.R
import com.peek.browser.Settings
import com.peek.browser.util.SourceTag
import com.peek.browser.util.EventBus
import com.peek.browser.util.Util

class HomeActivity : AppCompatActivity() {

    lateinit var mActionButtonView: Button
    lateinit var mNewBubble: Button
    lateinit var mStatsFlipView: FlipView
    lateinit var mTimeSavedPerLinkContainerView: View
    lateinit var mTimeSavedPerLinkTextView: CondensedTextView
    lateinit var mTimeSavedTotalTextView: CondensedTextView
    var mGrantOverlayPermissionView: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_home)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        Util.padForStatusBarInset(toolbar)

        mActionButtonView = findViewById(R.id.big_white_button)
        mNewBubble = findViewById(R.id.new_bubble)
        mStatsFlipView = findViewById(R.id.stats_flip_view)
        mTimeSavedPerLinkContainerView = mStatsFlipView.getDefaultView()
        mTimeSavedPerLinkTextView = mTimeSavedPerLinkContainerView.findViewById(R.id.time_per_link)
        mTimeSavedPerLinkTextView.text = ""
        mTimeSavedTotalTextView = mStatsFlipView.getFlippedView().findViewById(R.id.time_total)
        mTimeSavedTotalTextView.text = ""

        if (!Settings.get().getTermsAccepted()) {
            val rootView = findViewById<FrameLayout>(android.R.id.content)

            val acceptTermsView = layoutInflater.inflate(R.layout.view_accept_terms, null)
            val acceptTermsTextView = acceptTermsView.findViewById<TextView>(R.id.accept_terms_and_privacy_text)
            acceptTermsTextView.text = Html.fromHtml(getString(R.string.accept_terms_and_privacy))
            acceptTermsTextView.movementMethod = LinkMovementMethod.getInstance()
            val acceptTermsButton = acceptTermsView.findViewById<Button>(R.id.accept_terms_and_privacy_button)
            acceptTermsButton.setOnClickListener {
                Settings.get().setTermsAccepted(true)
                if (rootView != null) {
                    rootView.removeView(acceptTermsView)
                }
            }
            acceptTermsView.setOnClickListener {
                // do nothing, but prevent clicks from flowing to item underneath
            }

            if (rootView != null) {
                rootView.addView(acceptTermsView)
            }
        }

        checkOverlayPermission()
        checkNotificationPermission()
        checkLegacyStoragePermission()

        if (Settings.get().debugAutoLoadUrl()) {
            MainApplication.openLink(this, "file:///android_asset/test.html", null)
        }

        mActionButtonView.setOnClickListener {
            startActivity(Intent(this@HomeActivity, HistoryActivity::class.java), it)
        }

        mNewBubble.setOnClickListener {
            MainApplication.openLink(this@HomeActivity, this@HomeActivity.getString(R.string.empty_bubble_page),
                    SourceTag.OPENED_URL_FROM_MAIN_NEW_TAB)
        }

        EventBus.subscribe(this, Settings.LinkLoadTimeStatsUpdatedEvent::class.java, ::onLinkLoadTimeStatsUpdatedEvent)

        Settings.get().getBrowsers()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.activity_home, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                return true
            }

            R.id.action_settings -> {
                startActivity(Intent(this@HomeActivity, SettingsActivity::class.java), item.actionView)
                return true
            }
        }

        return false
    }

    override fun onDestroy() {
        EventBus.unsubscribeAll(this)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()

        updateLinkLoadTimeStats()
        checkOverlayPermission()

        EventBus.post(MainApplication.CheckStateEvent())
    }

    private fun checkOverlayPermission() {
        val rootView = findViewById<FrameLayout>(android.R.id.content)
        val hasPermission = android.provider.Settings.canDrawOverlays(this)

        if (hasPermission) {
            if (mGrantOverlayPermissionView != null && rootView != null) {
                rootView.removeView(mGrantOverlayPermissionView)
                mGrantOverlayPermissionView = null
            }
            return
        }

        if (mGrantOverlayPermissionView != null || rootView == null) {
            return
        }

        mGrantOverlayPermissionView = layoutInflater.inflate(R.layout.view_grant_overlay_permission, null)
        val grantButton = mGrantOverlayPermissionView!!.findViewById<Button>(R.id.grant_overlay_permission_button)
        grantButton.setOnClickListener {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"))
            startActivity(intent)
        }
        mGrantOverlayPermissionView!!.setOnClickListener {
            // do nothing, but prevent clicks from flowing to item underneath
        }

        rootView.addView(mGrantOverlayPermissionView)
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
        }
    }

    private fun checkLegacyStoragePermission() {
        // Scoped storage (MediaStore) is used on API 29+ and needs no permission.
        // Below that, saving images still goes through the legacy public Downloads
        // directory, which requires this runtime permission.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_WRITE_EXTERNAL_STORAGE)
        }
    }

    override fun onStart() {
        super.onStart()

        MainApplication.checkRestoreCurrentTabs(this)
    }

    private fun updateLinkLoadTimeStats() {
        val timeSavedPerLink = Settings.get().getTimeSavedPerLink()
        if (timeSavedPerLink > -1) {
            val prettyTimeElapsed = Util.getPrettyTimeElapsed(resources, timeSavedPerLink, "\n")
            mTimeSavedPerLinkTextView.text = prettyTimeElapsed
            Log.d(Settings.LOAD_TIME_TAG, "*** " + prettyTimeElapsed.replace("\n", " "))
        } else {
            val prettyTimeElapsed = Util.getPrettyTimeElapsed(resources, 0, "\n")
            mTimeSavedPerLinkTextView.text = prettyTimeElapsed
            // The "time saved so far == 0" link is a better one to display when there's no data yet
            if (mStatsFlipView.getDefaultView() === mTimeSavedPerLinkContainerView) {
                mStatsFlipView.toggleFlip(false)
            }
        }

        val totalTimeSaved = Settings.get().getTotalTimeSaved()
        if (totalTimeSaved > -1) {
            val prettyTimeElapsed = Util.getPrettyTimeElapsed(resources, totalTimeSaved, "\n")
            mTimeSavedTotalTextView.text = prettyTimeElapsed
            Log.d(Settings.LOAD_TIME_TAG, "*** " + prettyTimeElapsed.replace("\n", " "))
        }
    }

    fun startActivity(intent: Intent, view: View?) {

        val useLaunchAnimation = (view != null) &&
                !intent.hasExtra(Constant.INTENT_EXTRA_IGNORE_LAUNCH_ANIMATION)

        if (useLaunchAnimation) {
            val opts = ActivityOptions.makeScaleUpAnimation(view, 0, 0,
                    view!!.measuredWidth, view.measuredHeight)

            startActivity(intent, opts.toBundle())
        } else {
            startActivity(intent)
        }
    }

    fun onLinkLoadTimeStatsUpdatedEvent(event: Settings.LinkLoadTimeStatsUpdatedEvent) {
        updateLinkLoadTimeStats()
    }

    companion object {
        private const val TAG = "HomeActivity"
        private const val REQUEST_POST_NOTIFICATIONS = 1001
        private const val REQUEST_WRITE_EXTERNAL_STORAGE = 1002
    }
}
