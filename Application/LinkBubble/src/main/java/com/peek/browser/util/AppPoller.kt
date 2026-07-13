/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.util

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Message
import android.util.Log
import com.peek.browser.BuildConfig
import com.peek.browser.MainController
import com.peek.browser.ui.EntryActivity
import com.peek.browser.ui.ExpandedActivity
import com.peek.browser.ui.NotificationCloseAllActivity
import com.peek.browser.ui.NotificationCloseTabActivity
import com.peek.browser.ui.NotificationHideActivity
import com.peek.browser.ui.NotificationOpenTabActivity
import com.peek.browser.ui.NotificationUnhideActivity

class AppPoller(private val mContext: Context) {

    interface AppPollerListener {
        fun onAppChanged()
    }

    private var mAppPollingListener: AppPollerListener? = null
    private var mCurrentAppFlatComponentName: String? = null
    private var mNextAppFlatComponentName: String? = null
    private var mNextAppFirstRunningTime: Long = -1
    private var mPolling = false

    fun setListener(listener: AppPollerListener) {
        mAppPollingListener = listener
    }

    fun beginAppPolling() {
        if (mCurrentAppFlatComponentName == null) {
            val am = mContext.getSystemService(Activity.ACTIVITY_SERVICE) as ActivityManager
            val runningTasks = am.getRunningTasks(1)
            if (runningTasks != null && runningTasks.size > 0) {
                val componentName = runningTasks[0].topActivity
                mCurrentAppFlatComponentName = componentName?.flattenToShortString()
                Log.d(TAG, "beginAppPolling() - current app:$mCurrentAppFlatComponentName")
            }
        }

        mNextAppFirstRunningTime = -1
        mNextAppFlatComponentName = null

        if (!mPolling) {
            mHandler.sendEmptyMessageDelayed(ACTION_POLL_CURRENT_APP, LOOP_TIME.toLong())
        }
        mPolling = true
    }

    fun endAppPolling() {
        mHandler.removeMessages(ACTION_POLL_CURRENT_APP)
        mPolling = false
        mCurrentAppFlatComponentName = null
        Log.d(TAG, "endAppPolling()")
    }

    // ES FileExplorer seems to employ a nasty hack whereby they start a new Activity when an app is installed/updated.
    // Add this equally nasty hack to ignore this one activity. Stops the Bubbles going into BubbleView mode without any input (see #179)
    private val IGNORE_ACTIVITIES = arrayOf(
            "com.estrongs.android.pop/.app.InstallMonitorActivity",
            "com.ideashower.readitlater.pro/com.ideashower.readitlater.activity.AddActivity",
            BuildConfig.APPLICATION_ID + "/" + ExpandedActivity::class.java.name,
            BuildConfig.APPLICATION_ID + "/" + NotificationCloseAllActivity::class.java.name,
            BuildConfig.APPLICATION_ID + "/" + NotificationCloseTabActivity::class.java.name,
            BuildConfig.APPLICATION_ID + "/" + NotificationHideActivity::class.java.name,
            BuildConfig.APPLICATION_ID + "/" + NotificationUnhideActivity::class.java.name,
            BuildConfig.APPLICATION_ID + "/" + NotificationOpenTabActivity::class.java.name,
            BuildConfig.APPLICATION_ID + "/" + EntryActivity::class.java.name)

    private fun shouldIgnoreActivity(flatComponentName: String): Boolean {
        for (string in IGNORE_ACTIVITIES) {
            if (string == flatComponentName) {
                if (!flatComponentName.contains(ExpandedActivity::class.java.name)) {
                    Log.d(TAG, "ignore $flatComponentName")
                }
                return true
            }
        }

        return false
    }

    private var mCurrentLoopCount = 0

    private val mHandler: Handler = object : Handler() {

        override fun handleMessage(msg: Message) {
            when (msg.what) {
                ACTION_POLL_CURRENT_APP -> {
                    mCurrentLoopCount++
                    mHandler.removeMessages(ACTION_POLL_CURRENT_APP)

                    if (MainController.get() == null) {
                        Log.d(TAG, "No main controller, exit")
                        return
                    }

                    val am = mContext.getSystemService(Activity.ACTIVITY_SERVICE) as ActivityManager

                    val runningTasks = am.getRunningTasks(1)
                    if (runningTasks.isEmpty()) {
                        CrashTracking.log("$TAG: No running tasks!")
                        return
                    }

                    val componentName = runningTasks[0].topActivity
                    val appFlatComponentName = componentName?.flattenToShortString()
                    val currentAppFlatComponentName = mCurrentAppFlatComponentName
                    if (appFlatComponentName != null
                            && currentAppFlatComponentName != null
                            && appFlatComponentName != currentAppFlatComponentName) {

                        val currentTime = System.currentTimeMillis()
                        if (mNextAppFirstRunningTime == -1L
                                || (mNextAppFlatComponentName != null && mNextAppFlatComponentName != appFlatComponentName)) {
                            mNextAppFirstRunningTime = currentTime
                            mNextAppFlatComponentName = appFlatComponentName
                            Log.d(TAG, "next app to maybe be changed from $currentAppFlatComponentName to $appFlatComponentName")
                        }

                        val timeDelta = currentTime - mNextAppFirstRunningTime
                        if (!shouldIgnoreActivity(appFlatComponentName)
                                && mNextAppFlatComponentName == appFlatComponentName && timeDelta >= VERIFY_TIME
                                && currentAppFlatComponentName != appFlatComponentName) {
                            val oldApp = currentAppFlatComponentName
                            mCurrentAppFlatComponentName = appFlatComponentName
                            // It's possible the current app has been set to an app we should ignore (like Pocket or ES File Explorer)
                            // in beginAppPolling(). In that case, change mCurrentAppFlatComponentName, but don't inform the app about the
                            // change as it involved an app we should be ignoring.
                            if (shouldIgnoreActivity(oldApp)) {
                                mHandler.sendEmptyMessageDelayed(ACTION_POLL_CURRENT_APP, LOOP_TIME.toLong())
                                Log.d(TAG, "ignore app changing from $mCurrentAppFlatComponentName to $appFlatComponentName")
                            } else {
                                Log.d(TAG, "current app changed from $mCurrentAppFlatComponentName to $appFlatComponentName, triggering onAppChanged()...")
                                mAppPollingListener?.onAppChanged()
                            }
                        } else {
                            mHandler.sendEmptyMessageDelayed(ACTION_POLL_CURRENT_APP, LOOP_TIME.toLong())
                        }
                    } else {
                        if (mNextAppFlatComponentName != null) {
                            Log.d(TAG, "*** successfully ignored setting current app to $mNextAppFlatComponentName")
                        }
                        mNextAppFirstRunningTime = -1
                        mNextAppFlatComponentName = null
                        mHandler.sendEmptyMessageDelayed(ACTION_POLL_CURRENT_APP, LOOP_TIME.toLong())
                    }
                    if (mCurrentLoopCount == sCountToCallGc) {
                        System.gc()
                        mCurrentLoopCount = 0
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "AppPoller"
        private const val VERIFY_TIME = 150
        private const val ACTION_POLL_CURRENT_APP = 1
        private const val LOOP_TIME = 50
        private const val sCountToCallGc = 2000
    }
}
