/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.ActionMode
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.peek.browser.BuildConfig
import com.peek.browser.Constant
import com.peek.browser.MainController
import com.peek.browser.MainService
import com.peek.browser.R
import com.peek.browser.util.CrashTracking
import com.peek.browser.util.EventBus
import org.jsoup.helper.StringUtil
import java.util.ArrayList

class ExpandedActivity : Activity() {

    class MinimizeExpandedActivityEvent

    class ShowFileBrowserEvent(@JvmField val mAcceptTypes: Array<String>, @JvmField val mFilePathCallback: ValueCallback<Array<Uri>>) {
        fun getFilePathCallback(): ValueCallback<Array<Uri>> {
            return mFilePathCallback
        }

        fun getAcceptTypes(): Array<String> {
            return mAcceptTypes
        }
    }

    class ExpandedActivityReadyEvent

    private var mIsAlive = false
    private var mIsShowing = false
    private var mRegisteredForBus = false
    private val mHandler = Handler()

    private var mWebRendererContainer: LinearLayout? = null

    private var mFilePathCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {

        CrashTracking.log("ExpandedActivity.onCreate()")

        super.onCreate(savedInstanceState)

        // Fixes #454
        if (MainController.get() == null || MainController.get()!!.activeTabCount == 0) {
            CrashTracking.log("early finish() because nothing to display")
            finish()
            return
        }

        sInstance = this

        mIsAlive = true

        setContentView(R.layout.activity_expanded)

        actionBar!!.hide()

        registerForBus()

        val rootView = findViewById<FrameLayout>(R.id.expanded_root)

        mWebRendererContainer = findViewById(R.id.web_renderer_container)
        if (Constant.ACTIVITY_WEBVIEW_RENDERING == false) {
            //mWebRendererContainer.setWillNotDraw(true);
            rootView.removeView(mWebRendererContainer)
            mWebRendererContainer = null
        }

        if (Constant.EXPANDED_ACTIVITY_DEBUG) {
            rootView.setBackgroundColor(0x5500ff00.toInt())
        } else {
            rootView.setWillNotDraw(true)
        }
    }

    override fun onActionModeStarted(actionMode: ActionMode) {
        super.onActionModeStarted(actionMode)
        CrashTracking.log("onActionModeStarted()")
    }

    override fun onActionModeFinished(actionMode: ActionMode) {
        super.onActionModeFinished(actionMode)
        CrashTracking.log("onActionModeFinished()")
    }

    /*
     * Get the most recent RecentTaskInfo, but ensure the result is not Link Bubble.
     */
    fun getPreviousTaskInfo(recentTasks: List<ActivityManager.RecentTaskInfo>): ActivityManager.RecentTaskInfo? {
        for (i in recentTasks.indices) {
            val recentTaskInfo = recentTasks[i]
            if (recentTaskInfo.baseIntent != null
                    && recentTaskInfo.baseIntent.component != null) {
                val packageName = recentTaskInfo.baseIntent.component!!.packageName
                if (packageName != "android" && packageName != BuildConfig.APPLICATION_ID) {
                    return recentTaskInfo
                }
            }
        }

        return null
    }

    override fun onDestroy() {
        CrashTracking.log("***ExpandedActivity.onDestroy()")

        super.onDestroy()

        mIsAlive = false

        if (sInstance === this) {
            sInstance = null
        }

        unregisterForBus()
    }

    override fun onStart() {
        CrashTracking.log("ExpandedActivity.onStart()")
        Log.e(TAG, "Expand time: " + (System.currentTimeMillis() - MainController.sStartExpandedActivityTime))

        EventBus.post(ExpandedActivityReadyEvent())

        super.onStart()
    }

    override fun onStop() {
        CrashTracking.log("ExpandedActivity.onStop()")
        super.onStop()

        //mTopMaskImage.setVisibility(View.GONE);
        //mBottomMaskImage.setVisibility(View.GONE);
    }

    override fun onResume() {
        CrashTracking.log("ExpandedActivity.onResume()")
        super.onResume()

        mIsShowing = true

        val mainController = MainController.get()
        if (mainController == null || mainController.contentViewShowing() == false) {
            minimize()
        }

        if (Constant.ACTIVITY_WEBVIEW_RENDERING && mWebRendererContainer!!.childCount == 0) {
            val current = MainController.get()!!.currentTab
            if (current != null) {
                setWebRenderer(current.getContentView()!!.getWebRenderer().getView()!!)
            }
        }
    }

    override fun onPause() {
        CrashTracking.log("ExpandedActivity.onPause()")
        super.onPause()
        mIsShowing = false
    }

    override fun finish() {
        CrashTracking.log("ExpandedActivity.finish()")
        super.finish()
        overridePendingTransition(0, 0)
    }

    override fun onBackPressed() {
        delayedMinimize()
    }

    fun registerForBus() {
        EventBus.subscribe(this, MinimizeExpandedActivityEvent::class.java, ::onMinimizeExpandedActivity)
        EventBus.subscribe(this, ShowFileBrowserEvent::class.java, ::onShowFileBrowserEvent)
        EventBus.subscribe(this, MainController.HideContentEvent::class.java, ::onHideContentEvent)
        EventBus.subscribe(this, MainService.OnDestroyMainServiceEvent::class.java, ::OnOnDestroyMainServiceEvent)
        mRegisteredForBus = true
    }

    fun unregisterForBus() {
        if (mRegisteredForBus) {
            EventBus.unsubscribeAll(this)
            mRegisteredForBus = false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        // check that the response is a good one
        if (requestCode == FILECHOOSER_RESULTCODE) {
            var results = arrayOf<Uri>()
            if (resultCode == Activity.RESULT_OK) {
                if (intent != null) {
                    val dataString = intent.dataString
                    if (dataString != null) {
                        results = arrayOf(Uri.parse(dataString))
                    }
                }
            }
            mFilePathCallback!!.onReceiveValue(results)
            mFilePathCallback = null
            MainController.get()!!.switchToExpandedView()
        }
    }

    fun minimize() {
        if (mIsAlive == false) {
            return
        }

        // Comment out as a fix for #455
        //if (mIsShowing == false) {
        //    Log.d(TAG, "minimize() - mIsShowing:" + mIsShowing + ", exiting...");
        //    return;
        //}

        if (moveTaskToBack(true)) {
            CrashTracking.log("minimize() - moveTaskToBack(true);")
            return
        }

        if (MainController.get() != null) {
            MainController.get()!!.showBadge(true)
        }

        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val recentTasks = activityManager.getRecentTasks(16, ActivityManager.RECENT_WITH_EXCLUDED)

        if (recentTasks.size > 0) {
            val recentTaskInfo = getPreviousTaskInfo(recentTasks)
            val componentName = recentTaskInfo!!.baseIntent.component
            //openedFromAppName = componentName.getPackageName();
            val intent = Intent()
            intent.component = componentName
            intent.flags = Intent.FLAG_ACTIVITY_NO_ANIMATION
            startActivity(intent)
            overridePendingTransition(0, 0)
            CrashTracking.log("minimize() - $componentName")
        } else {
            CrashTracking.log("minimize() - NONE!")
        }
    }

    fun showFileBrowser(acceptTypes: Array<String>, filePathCallback: ValueCallback<Array<Uri>>) {
        MainController.get()!!.switchToBubbleView(false)
        mFilePathCallback = filePathCallback

        val runnable = Runnable {
            mHandler.post {
                val i = Intent(Intent.ACTION_GET_CONTENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)

                // Android intents wants mime types, filepickers can specify
                // mime types or file types, filter out the file types.
                val filteredList = ArrayList<String>()
                for (acceptType in acceptTypes) {
                    if (acceptType.contains("/")) {
                        filteredList.add(acceptType)
                    }
                }
                if (filteredList.size == 0) {
                    i.type = "*/*"
                } else {
                    i.type = StringUtil.join(filteredList, ",")
                }
                startActivityForResult(Intent.createChooser(i, "File Chooser"), FILECHOOSER_RESULTCODE)
            }
        }
        Thread(runnable).start()
    }

    fun delayedMinimize() {
        // Kill the activity to ensure it is not alive in the event a link is intercepted,
        // thus displaying the ugly UI for a few frames
        //fadeOut();
        mHandler.postDelayed(mMinimizeRunnable, 500)
    }

    fun cancelDelayedMinimize() {
        //fadeIn();
        mHandler.removeCallbacks(mMinimizeRunnable)
    }

    private val mMinimizeRunnable = Runnable {
        minimize()
    }

    fun setWebRenderer(view: View) {
        if (mWebRendererContainer!!.childCount == 0) {
            if (view.parent != null) {
                (view.parent as ViewGroup).removeView(view)
            }
            mWebRendererContainer!!.addView(view)
        }
    }

    fun onMinimizeExpandedActivity(e: MinimizeExpandedActivityEvent) {
        minimize()
    }

    fun onShowFileBrowserEvent(e: ShowFileBrowserEvent) {
        showFileBrowser(e.getAcceptTypes(), e.getFilePathCallback())
    }

    fun onHideContentEvent(event: MainController.HideContentEvent) {
        finish()
    }

    fun OnOnDestroyMainServiceEvent(event: MainService.OnDestroyMainServiceEvent) {
        finish()
    }

    companion object {
        private const val TAG = "ExpandedActivity"

        private var sInstance: ExpandedActivity? = null

        @JvmStatic
        fun get(): ExpandedActivity? {
            return sInstance
        }

        private const val FILECHOOSER_RESULTCODE = 1
    }
}
