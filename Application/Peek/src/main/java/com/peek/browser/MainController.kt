/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser

import android.animation.Animator
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.CookieSyncManager
import android.webkit.ValueCallback
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.peek.browser.physics.Draggable
import com.peek.browser.physics.DraggableHelper
import com.peek.browser.ui.BubbleDraggable
import com.peek.browser.ui.BubbleFlowDraggable
import com.peek.browser.ui.BubbleFlowView
import com.peek.browser.ui.CanvasView
import com.peek.browser.ui.ExpandedActivity
import com.peek.browser.ui.Prompt
import com.peek.browser.ui.TabView
import com.peek.browser.util.ActionItem
import com.peek.browser.util.SourceTag
import com.peek.browser.util.AppPoller
import com.peek.browser.util.CrashTracking
import com.peek.browser.util.EventBus
import com.peek.browser.util.Util
import com.peek.browser.webrender.WebViewPreloader
import java.net.MalformedURLException
import java.net.URL
import java.util.Timer
import java.util.TimerTask
import java.util.Vector

class MainController private constructor(context: Context, eventHandler: EventHandler) : Choreographer.FrameCallback {

    // Simple event classes used for the event bus
    class BeginBubbleDragEvent
    class EndBubbleDragEvent

    class BeginAnimateFinalTabAwayEvent {
        @JvmField var mTab: TabView? = null
    }

    class EndAnimateFinalTabAwayEvent

    class BeginExpandTransitionEvent {
        @JvmField var mPeriod = 0f
    }

    class BeginCollapseTransitionEvent @JvmOverloads constructor(@JvmField var mFromCloseSystemDialogs: Boolean = false) {
        @JvmField var mPeriod = 0f
    }

    class EndCollapseTransitionEvent

    class OrientationChangedEvent

    class CurrentTabChangedEvent @JvmOverloads constructor(
            @JvmField var mTab: TabView? = null,
            @JvmField var mUnhideNotification: Boolean = false
    )

    class CurrentTabResumeEvent @JvmOverloads constructor(@JvmField var mTab: TabView? = null)

    class CurrentTabPauseEvent @JvmOverloads constructor(@JvmField var mTab: TabView? = null)

    class DraggableBubbleMovedEvent {
        @JvmField var mX = 0
        @JvmField var mY = 0
    }

    class HideContentEvent
    class UnhideContentEvent

    class ScreenOnEvent
    class ScreenOffEvent
    class UserPresentEvent

    private val mRootViews = Vector<View>()
    private var mRootWindowsVisible = true
    private val mWindowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mOrientationChangedEvent = OrientationChangedEvent()
    private val mBeginExpandTransitionEvent = BeginExpandTransitionEvent()
    private val mMinimizeExpandedActivityEvent = ExpandedActivity.MinimizeExpandedActivityEvent()

    private val mUserPresentEvent = UserPresentEvent()
    private val mScreenOnEvent = ScreenOnEvent()
    private val mScreenOffEvent = ScreenOffEvent()
    private var mScreenOn = true
    private var mTimer: Timer? = null

    private var mHeightSizeTopMargin = false

    private class OpenUrlInfo(val mUrlAsString: String, val mStartTime: Long)

    private val mOpenUrlInfos = ArrayList<OpenUrlInfo>()

    // End of event bus classes

    private fun enableRootWindows() {
        if (!mRootWindowsVisible) {
            for (v in mRootViews) {
                val lp = v.layoutParams as WindowManager.LayoutParams
                //lp.alpha = 1.0f;
                //mWindowManager.updateViewLayout(v, lp);
                mWindowManager.addView(v, lp)
                // Hack to ensure BubbleFlowDraggable doesn't display in Bubble mode, fix #457
                if (v is BubbleFlowView) {
                    v.forceCollapseEnd()
                    (v as BubbleFlowDraggable).setCurrentTabAsActive()
                }
            }
            mRootWindowsVisible = true
        }
    }

    private fun disableRootWindows() {
        if (mRootWindowsVisible) {
            for (v in mRootViews) {
                //WindowManager.LayoutParams lp = (WindowManager.LayoutParams) v.getLayoutParams();
                //lp.alpha = 0.5f;
                //mWindowManager.updateViewLayout(v, lp);
                // Hack to ensure BubbleFlowDraggable doesn't display in Bubble mode, fix #457
                if (v is BubbleFlowView) {
                    v.forceCollapseEnd()
                }
                mWindowManager.removeView(v)
            }
            mRootWindowsVisible = false
        }
    }

    interface EventHandler {
        fun onDestroy()
    }

    private val mEventHandler: EventHandler = eventHandler
    private var mBubblesLoaded = 0
    private val mAppPoller: AppPoller = AppPoller(context)

    private val mContext: Context = context
    private val mAppPackageName: String = mContext.packageName
    // Declared before init{} so the listener registration there doesn't get clobbered
    // by a later property initializer (Kotlin runs them in textual order).
    private var mKeyguardManager: KeyguardManager? = null
    private var mLastKeyguardCheckTimeMillis: Long = 0
    private var mKeyguardLockedStateListener: KeyguardManager.KeyguardLockedStateListener? = null
    private val mChoreographer: Choreographer
    private var mUpdateScheduled: Boolean
    private val mCanvasView: CanvasView

    private val mBubbleFlowDraggable: BubbleFlowDraggable
    private val mBubbleDraggable: BubbleDraggable

    private var mPreviousFrameTime: Long = 0
    private val mOriginalBubbleFlowDraggableParams: WindowManager.LayoutParams?
    private var mOriginalLocationY: Float
    private var mPreviousBubbleAdjustmentValue = 0
    private var mCurrentAdjustment = 0f

    // false if the user has forcibilty minimized the Bubbles from ContentView. Set back to true once a new link is loaded.
    private var mCanAutoDisplayLink: Boolean

    val mOnBubbleFlowExpandFinishedListener: BubbleFlowView.AnimationEventListener = object : BubbleFlowView.AnimationEventListener {
        override fun onAnimationEnd(sender: BubbleFlowView) {
            val currentTab = (sender as BubbleFlowDraggable).getCurrentTab()
            if (currentTab != null && currentTab.getContentView() != null) {
                currentTab.getContentView()!!.saveLoadTime()
            }
        }
    }

    val mOnBubbleFlowCollapseFinishedListener: BubbleFlowView.AnimationEventListener = object : BubbleFlowView.AnimationEventListener {
        override fun onAnimationEnd(sender: BubbleFlowView) {
            onBubbleFlowCollapseFinished()
        }
    }

    private fun onBubbleFlowCollapseFinished() {
        mBubbleDraggable.visibility = View.VISIBLE
        val tab = mBubbleFlowDraggable.getCurrentTab()
        if (tab != null) {
            tab.setImitator(mBubbleDraggable)
        }
        mSetBubbleFlowGone = true
        mBubbleFlowDraggable.postDelayed(mSetBubbleFlowGoneRunnable, 33)
    }

    private var mSetBubbleFlowGone = false
    val mSetBubbleFlowGoneRunnable: Runnable = Runnable {
        if (mSetBubbleFlowGone) {
            // A window that goes GONE mid-follow must still shrink back to normal bounds.
            mBubbleFlowDraggable.endFollowingBubble()
            mBubbleFlowDraggable.visibility = View.GONE
        }
    }

    val mSetBubbleGoneRunnable: Runnable = Runnable {
        mBubbleDraggable.visibility = View.GONE
    }

    /*
     * Pass all the input along to mBubbleDraggable
     */
    val mBubbleFlowTouchInterceptor: BubbleFlowView.TouchInterceptor = object : BubbleFlowView.TouchInterceptor {

        override fun onTouchActionDown(event: MotionEvent): Boolean {
            return mBubbleDraggable.draggableHelper.onTouchActionDown(event)
        }

        override fun onTouchActionMove(event: MotionEvent): Boolean {
            return mBubbleDraggable.draggableHelper.onTouchActionMove(event)
        }

        override fun onTouchActionUp(event: MotionEvent): Boolean {
            val result = mBubbleDraggable.draggableHelper.onTouchActionUp(event)
            mBubbleFlowDraggable.setTouchInterceptor(null)
            return result
        }
    }

    private var mTextView: TextView? = null
    private val mWindowManagerParams = WindowManager.LayoutParams()
    private var mCanDisplay: Boolean

    init {
        Util.Assert(sInstance == null, "non-null instance")
        sInstance = this

        mCanAutoDisplayLink = true

        mCanDisplay = true

        if (Constant.PROFILE_FPS) {
            mTextView = TextView(mContext)
            mTextView!!.setTextColor(-0xff0100)
            mTextView!!.textSize = 32.0f
            mWindowManagerParams.gravity = Gravity.TOP or Gravity.LEFT
            mWindowManagerParams.x = 500
            mWindowManagerParams.y = 16
            mWindowManagerParams.height = WindowManager.LayoutParams.WRAP_CONTENT
            mWindowManagerParams.width = WindowManager.LayoutParams.WRAP_CONTENT
            mWindowManagerParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            mWindowManagerParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            mWindowManagerParams.format = PixelFormat.TRANSPARENT
            mWindowManagerParams.setTitle("Peek: Debug Text")
            mWindowManager.addView(mTextView, mWindowManagerParams)
        }

        mUpdateScheduled = false
        mChoreographer = Choreographer.getInstance()
        mCanvasView = CanvasView(mContext)

        EventBus.subscribe(this, EndCollapseTransitionEvent::class.java, ::onEndCollapseTransition)
        EventBus.subscribe(this, BeginExpandTransitionEvent::class.java, ::onBeginExpandTransition)
        EventBus.subscribe(this, ExpandedActivity.ExpandedActivityReadyEvent::class.java, ::onExpandedActivityReadyEvent)
        EventBus.subscribe(this, MainApplication.StateChangedEvent::class.java, ::onStateChangedEvent)
        EventBus.subscribe(this, EndAnimateFinalTabAwayEvent::class.java, ::onEndAnimateFinalTabAway)

        val inflater = LayoutInflater.from(mContext)

        mBubbleDraggable = inflater.inflate(R.layout.view_bubble_draggable, null) as BubbleDraggable
        val bubbleRestingPoint = Settings.get().getBubbleRestingPoint()
        val fromX = Settings.get().getBubbleStartingX(bubbleRestingPoint)
        mBubbleDraggable.configure(fromX, bubbleRestingPoint.y, bubbleRestingPoint.x, bubbleRestingPoint.y,
                Constant.BUBBLE_SLIDE_ON_SCREEN_TIME, mCanvasView)

        mBubbleDraggable.setOnUpdateListener(object : Draggable.OnUpdateListener {
            override fun onUpdate(draggable: Draggable, dt: Float) {
                // While the flow window is GONE (all of collapsed mode), moving it is a
                // wasted WindowManager IPC per frame; doExpandBubbleFlow() re-syncs once
                // before flipping it VISIBLE.
                if (!draggable.isDragging && mBubbleFlowDraggable.visibility == View.VISIBLE) {
                    mBubbleFlowDraggable.syncWithBubble(draggable)
                }
            }
        })

        mBubbleFlowDraggable = inflater.inflate(R.layout.view_bubble_flow, null) as BubbleFlowDraggable
        mBubbleFlowDraggable.configure(null)
        mBubbleFlowDraggable.collapse(0, null)
        mBubbleFlowDraggable.setBubbleDraggable(mBubbleDraggable)
        mBubbleFlowDraggable.visibility = View.GONE
        mOriginalBubbleFlowDraggableParams = mBubbleFlowDraggable.layoutParams as WindowManager.LayoutParams?
        mOriginalLocationY = mBubbleFlowDraggable.getChildAt(0).y

        mBubbleDraggable.setBubbleFlowDraggable(mBubbleFlowDraggable)

        // When the bubble runs a windowed animation (grow-once + translate, see
        // DraggableHelper.beginWindowExpansion), have the flow window that tracks it per-frame
        // mirror the optimization instead of paying a WindowManager IPC per synced frame.
        // On expand the flow is still GONE when this fires — doExpandBubbleFlow() starts the
        // follow-session in that case, right after making the flow visible.
        mBubbleDraggable.draggableHelper.setOnWindowedAnimationListener(object : DraggableHelper.OnWindowedAnimationListener {
            override fun onBegin(fromX: Int, fromY: Int, toX: Int, toY: Int) {
                if (mBubbleFlowDraggable.visibility == View.VISIBLE) {
                    mBubbleFlowDraggable.beginFollowingBubble(fromX, fromY, toX, toY)
                }
            }

            override fun onEnd() {
                mBubbleFlowDraggable.endFollowingBubble()
            }
        })

        updateIncognitoMode(Settings.get().isIncognitoMode)

        // The device can lock while the screen stays on (lock timeout), which fires no
        // SCREEN_OFF broadcast — and the doFrame() poll only runs while something is
        // animating, so an idle bubble would stay visible on top of the lockscreen.
        // API 33+ has a real event for it; older releases keep the doFrame() fallback.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mKeyguardManager = mContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager?
            val listener = KeyguardManager.KeyguardLockedStateListener { isLocked ->
                CrashTracking.log("KeyguardLockedStateListener: isLocked=$isLocked")
                setCanDisplay(!isLocked)
            }
            try {
                // Requires the (normal) SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE permission.
                mKeyguardManager?.addKeyguardLockedStateListener(ContextCompat.getMainExecutor(mContext), listener)
                mKeyguardLockedStateListener = listener
            } catch (e: SecurityException) {
                // Listener unavailable — doFrame()'s throttled poll takes over.
                CrashTracking.logHandledException(e)
            }
        }

        // Warm the first tab's WebView while the main thread is idle.
        WebViewPreloader.preload(mContext)
    }

    /*
     * Begin the destruction process.
     */
    fun finish() {
        mEventHandler.onDestroy()
    }

    fun adjustBubblesPanel(newY: Int, oldY: Int, afterTouchAdjust: Boolean, resetToOriginal: Boolean) {
        val settings = Settings.get()
        if (!settings.isHideBubbles()) {
            return
        }

        val currentTabView = currentTab

        if (null == mOriginalBubbleFlowDraggableParams || null == currentTabView || !afterTouchAdjust && !resetToOriginal &&
                (mPreviousBubbleAdjustmentValue - newY > 0 - PIXELS_TO_SKIP_BEFORE_SCROLL &&
                        mPreviousBubbleAdjustmentValue - newY < PIXELS_TO_SKIP_BEFORE_SCROLL)) {
            return
        }
        val toolbarHeight = currentTabView.toolbarHeight()
        var adjustOn = newY - mPreviousBubbleAdjustmentValue
        if (afterTouchAdjust) {
            adjustOn = 0
        }
        var currentParams: FrameLayout.LayoutParams? = null
        try {
            currentParams = mBubbleFlowDraggable.getChildAt(0).layoutParams as FrameLayout.LayoutParams
        } catch (exc: NullPointerException) {
        }
        if (null == currentParams) {
            return
        }
        val oldAdjustment = mCurrentAdjustment
        if (!afterTouchAdjust && 0 == oldY &&
                adjustOn > 0 - PIXELS_TO_SKIP_BEFORE_SCROLL && adjustOn < PIXELS_TO_SKIP_BEFORE_SCROLL || resetToOriginal) {
            mCurrentAdjustment = mOriginalLocationY
            mHeightSizeTopMargin = false
        } else if (0 == adjustOn) {
            val half = (mOriginalBubbleFlowDraggableParams.height + currentParams.height) / 2
            val third = (mOriginalBubbleFlowDraggableParams.height + currentParams.height) / 3
            if (0 - mCurrentAdjustment > third && mPreviousBubbleAdjustmentValue > half) {
                mCurrentAdjustment = (0 - (mOriginalBubbleFlowDraggableParams.height + currentParams.height + toolbarHeight)).toFloat()
                mHeightSizeTopMargin = true
            } else {
                mCurrentAdjustment = mOriginalLocationY
                mHeightSizeTopMargin = false
            }
        } else {
            mCurrentAdjustment += 0 - adjustOn
            if (mCurrentAdjustment + (mOriginalBubbleFlowDraggableParams.height + currentParams.height + toolbarHeight) < 0) {
                mCurrentAdjustment = (0 - (mOriginalBubbleFlowDraggableParams.height + currentParams.height + toolbarHeight)).toFloat()
                mHeightSizeTopMargin = true
            } else if (mCurrentAdjustment > mOriginalLocationY) {
                mCurrentAdjustment = mOriginalLocationY
                mHeightSizeTopMargin = false
            }
        }
        if (0 != adjustOn) {
            mPreviousBubbleAdjustmentValue = newY
        }
        if (mCurrentAdjustment == oldAdjustment) {
            return
        }
        var animationDuration = ANIMATION_DURATION_BUBBLES_HIDE
        if (adjustOn < 0) {
            animationDuration = ANIMATION_DURATION_BUBBLES_SHOW
        }
        if (currentTabView.adjustBubblesPanel(mCurrentAdjustment, mHeightSizeTopMargin, animationDuration)) {
            val animator = ObjectAnimator.ofFloat(mBubbleFlowDraggable.getChildAt(0), "translationY", mCurrentAdjustment)
                    .setDuration(animationDuration.toLong())
            animator.addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animator: Animator) {
                    if (!mHeightSizeTopMargin) {
                        if (mBubbleFlowDraggable.isExpanded()) {
                            mBubbleFlowDraggable.visibility = View.VISIBLE
                        }
                    }
                }

                override fun onAnimationEnd(animator: Animator) {
                    if (!mHeightSizeTopMargin) {
                        if (mBubbleFlowDraggable.isExpanded()) {
                            mBubbleFlowDraggable.visibility = View.VISIBLE
                        }
                    } else {
                        val view = mBubbleFlowDraggable.getCurrentTab()
                        if (null != view && !view.mIsClosing) {
                            mBubbleFlowDraggable.visibility = View.GONE
                        }
                    }
                }

                override fun onAnimationCancel(animator: Animator) {

                }

                override fun onAnimationRepeat(animator: Animator) {

                }
            })
            animator.start()
        }
    }

    fun onPageLoaded(tab: TabView, withError: Boolean) {
        // Ensure this is not an edge case where the Tab has already been destroyed, re #254
        if (activeTabCount == 0 || !isTabActive(tab)) {
            return
        }

        // Debounce the saving call so we don't attempt to save after every concurrent page load, causing potential problems with the database connection.
        if (mTimer != null) {
            try {
                mTimer!!.cancel()
                mTimer!!.purge()
            } catch (exc: NullPointerException) {
                // We can have a crash here sometimes when several pages loaded at one time, some of threads will do those calls in any case
            }
        }

        mTimer = Timer()
        val tt: TimerTask = object : TimerTask() {
            override fun run() {
                mTimer = null
                saveCurrentTabs()
            }
        }
        mTimer!!.schedule(tt, 200)
    }

    fun autoContentDisplayLinkLoaded(tab: TabView) {
        // Ensure this is not an edge case where the Tab has already been destroyed, re #254
        if (activeTabCount == 0 || !isTabActive(tab)) {
            return
        }

        if (Settings.get().getAutoContentDisplayLinkLoaded()) {
            displayTab(tab)
        }
    }

    fun displayTab(tab: TabView): Boolean {
        if (!mBubbleDraggable.isDragging && mCanAutoDisplayLink) {
            when (mBubbleDraggable.getCurrentMode()) {
                BubbleDraggable.Mode.BubbleView -> {
                    mBubbleFlowDraggable.setCenterItem(tab)
                    mBubbleDraggable.switchToExpandedView()
                    return true
                }
                else -> {}
            }
        }

        return false
    }

    fun saveCurrentTabs() {
        mBubbleFlowDraggable.saveCurrentTabs()
    }

    fun updateIncognitoMode(incognito: Boolean) {
        CookieSyncManager.createInstance(mContext)
        CookieManager.getInstance().setAcceptCookie(true)

        mBubbleFlowDraggable.updateIncognitoMode(incognito)
    }

    fun onEndCollapseTransition(e: EndCollapseTransitionEvent) {
        showBadge(true)
    }

    fun onBeginExpandTransition(e: BeginExpandTransitionEvent) {
        showBadge(false)
    }

    fun onExpandedActivityReadyEvent(event: ExpandedActivity.ExpandedActivityReadyEvent) {
        if (mDeferredExpandBubbleFlowTime > -1) {
            doExpandBubbleFlow(mDeferredExpandBubbleFlowTime, mDeferredExpandBubbleFlowHideDraggable)
            mDeferredExpandBubbleFlowTime = -1
            mDeferredExpandBubbleFlowHideDraggable = false
        }
    }

    fun showExpandedActivity() {
        Log.e(TAG, "showExpandedActivity()")
        sStartExpandedActivityTime = System.currentTimeMillis()
        val intent = Intent(mContext, ExpandedActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        mContext.applicationContext.startActivity(intent)
    }

    fun scheduleUpdate() {
        if (!mUpdateScheduled) {
            mUpdateScheduled = true
            mChoreographer.postFrameCallback(this)
        }
    }

    // TODO: think of a better name
    fun startDraggingFromContentView() {
        // When we start dragging, configure the BubbleFlowView to pass all its input to our TouchInterceptor so we
        // can re-route it to the BubbleDraggable. This is a bit messy, but necessary so as to cleanly using the same
        // MotionEvent chain for the BubbleFlowDraggable and BubbleDraggable so the items visually sync up.
        mBubbleFlowDraggable.setTouchInterceptor(mBubbleFlowTouchInterceptor)
        mBubbleFlowDraggable.collapse(Constant.BUBBLE_ANIM_TIME.toLong(), mOnBubbleFlowCollapseFinishedListener)
        mBubbleDraggable.visibility = View.VISIBLE
        if (mBubbleFlowDraggable.getTouchInterceptor() != null) {
            mBubbleFlowDraggable.setInterceptingTouch(true)
        }
    }

    fun getBubbleDraggable(): BubbleDraggable {
        return mBubbleDraggable
    }

    val activeTabCount: Int
        get() = mBubbleFlowDraggable.getActiveTabCount()

    val visibleTabCount: Int
        get() = mBubbleFlowDraggable.getVisibleTabCount()

    fun isUrlActive(urlAsString: String): Boolean {
        return mBubbleFlowDraggable.isUrlActive(urlAsString)
    }

    fun wasUrlRecentlyLoaded(urlAsString: String, urlLoadStartTime: Long): Boolean {
        for (openUrlInfo in mOpenUrlInfos) {
            val delta = urlLoadStartTime - openUrlInfo.mStartTime
            if (openUrlInfo.mUrlAsString == urlAsString && delta < 7 * 1000) {
                //Log.d("blerg", "urlAsString:" + urlAsString + ", openUrlInfo.mUrlAsString:" + openUrlInfo.mUrlAsString + ", delta: " + delta);
                if (mBubbleFlowDraggable.isUrlActive(urlAsString)) {
                    return true
                }
            }
        }
        return false
    }

    fun getTabIndex(tab: TabView): Int {
        return mBubbleFlowDraggable.getIndexOfView(tab)
    }

    fun isTabActive(tab: TabView): Boolean {
        val index = getTabIndex(tab)
        return index > -1
    }

    private val mSamples = FloatArray(MAX_SAMPLE_COUNT)
    private var mSampleCount = 0

    override fun doFrame(frameTimeNanos: Long) {
        mUpdateScheduled = false

        //if (mHiddenByUser == true) {
        //    return;
        //}

        val t0 = mPreviousFrameTime / 1000000000.0f
        val t1 = frameTimeNanos / 1000000000.0f
        val t = t1 - t0
        mPreviousFrameTime = frameTimeNanos
        val dt: Float
        dt = if (Constant.DYNAMIC_ANIM_STEP) {
            Util.clamp(0.0f, t, 3.0f / 60.0f)
        } else {
            1.0f / 60.0f
        }

        if (mBubbleFlowDraggable.update()) {
            scheduleUpdate()
        }

        mBubbleDraggable.update(dt)

        mCanvasView.update(dt)

        if (activeTabCount == 0 && mBubblesLoaded > 0 && !mUpdateScheduled) {
            // Will be non-zero in the event a link has been dismissed by a user, but its TabView
            // instance is still animating off screen. In that case, keep triggering an update so that when the
            // item finishes, we are ready to call onDestroy().
            if (mBubbleFlowDraggable.getVisibleTabCount() == 0 && !Prompt.isShowing()) {
                finish()
            } else {
                scheduleUpdate()
            }
        }

        // Fallback when the KeyguardLockedStateListener isn't active (pre-33, or its
        // registration failed): poll (throttled — isKeyguardLocked is a binder IPC)
        // while animating.
        if (!mHiddenByUser && mKeyguardLockedStateListener == null) {
            val nowMillis = frameTimeNanos / 1000000
            if (nowMillis - mLastKeyguardCheckTimeMillis >= 500) {
                mLastKeyguardCheckTimeMillis = nowMillis
                updateKeyguardLocked()
            }
        }

        if (Constant.PROFILE_FPS) {
            if (t < MAX_VALID_TIME) {
                mSamples[mSampleCount % MAX_SAMPLE_COUNT] = t
                ++mSampleCount
            }

            var total = 0.0f
            var worst = 0.0f
            var best = 99999999.0f
            var badFrames = 0
            val frameCount = Math.min(mSampleCount, MAX_SAMPLE_COUNT)
            for (i in 0 until frameCount) {
                total += mSamples[i]
                worst = Math.max(worst, mSamples[i])
                best = Math.min(best, mSamples[i])
                if (mSamples[i] > 1.5f / 60.0f) {
                    ++badFrames
                }
            }

            val sbest = String.format("%.2f", 1000.0f * best)
            val sworst = String.format("%.2f", 1000.0f * worst)
            val savg = String.format("%.2f", 1000.0f * total / frameCount)
            val badpc = String.format("%.2f", 100.0f * badFrames / frameCount)
            val s = "Best=$sbest\nWorst=$sworst\nAvg=$savg\nBad=$badFrames\nBad %=$badpc%"

            mTextView!!.setSingleLine(false)
            mTextView!!.text = s
            scheduleUpdate()
        }
    }

    fun onCloseSystemDialogs() {
        val delta = System.currentTimeMillis() - mLastOpenTabFromNotificationTime
        // Intent.ACTION_CLOSE_SYSTEM_DIALOGS gets triggered when NotificationOpenTabActivity is instantiated. Ignore that to stop minimizing...
        if (delta < 200) {
            return
        }
        switchToBubbleView(true)
    }

    fun getWebviewVersion(context: Context): Long {
        if (mVersionNumber != 0L) {
            return mVersionNumber
        }

        val pm = context.packageManager
        try {
            val pi = pm.getPackageInfo("com.google.android.webview", 0)
            mVersionNumber = pi.versionCode.toLong()
        } catch (e: PackageManager.NameNotFoundException) {
            mVersionNumber = -1
        }
        return mVersionNumber
    }

    fun hasStableWebViewForSelects(context: Context): Boolean {
        return getWebviewVersion(context) >= STABLE_SELECT_WEBVIEW_VERSIONCODE
    }

    fun onOrientationChanged() {
        Config.init(mContext)
        Settings.get().onOrientationChange()
        mBubbleDraggable.onOrientationChanged()
        mBubbleFlowDraggable.onOrientationChanged()
        EventBus.post(mOrientationChangedEvent)
    }

    private fun handleResolveInfo(resolveInfo: ResolveInfo, urlAsString: String, urlLoadStartTime: Long): Boolean {
        if (Settings.get().didRecentlyRedirectToApp(urlAsString)) {
            return false
        }

        val isPeek = resolveInfo.activityInfo != null
                && resolveInfo.activityInfo.packageName == mAppPackageName
        if (!isPeek && MainApplication.loadResolveInfoIntent(mContext, resolveInfo, urlAsString, -1)) {
            if (activeTabCount == 0 && !Prompt.isShowing()) {
                finish()
            }

            val title = String.format(mContext.getString(R.string.link_loaded_with_app),
                    resolveInfo.loadLabel(mContext.packageManager))
            MainApplication.saveUrlInHistory(mContext, resolveInfo, urlAsString, title)
            Settings.get().addRedirectToApp(urlAsString)
            Settings.get().trackLinkLoadTime(System.currentTimeMillis() - urlLoadStartTime, Settings.LinkLoadType.AppRedirectInstant, urlAsString)
            return true
        }

        return false
    }

    fun openUrl(urlAsString: String, urlLoadStartTime: Long, setAsCurrentTab: Boolean,
                openedFromAppName: String?): TabView? {

        if (wasUrlRecentlyLoaded(urlAsString, urlLoadStartTime) && urlAsString != mContext.getString(R.string.empty_bubble_page)) {
            Toast.makeText(mContext, R.string.duplicate_link_will_not_be_loaded, Toast.LENGTH_SHORT).show()
            return null
        }

        val url: URL
        try {
            url = URL(urlAsString)
        } catch (e: MalformedURLException) { // If this is not a valid scheme, back out. #271
            Toast.makeText(mContext, mContext.getString(R.string.unsupported_scheme), Toast.LENGTH_SHORT).show()
            if (activeTabCount == 0 && !Prompt.isShowing()) {
                finish()
            }
            return null
        }

        if (Settings.get().redirectUrlToBrowser(url)) {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(urlAsString)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (MainApplication.openInBrowser(mContext, intent, false)) {
                if (activeTabCount == 0 && !Prompt.isShowing()) {
                    finish()
                }

                val title = String.format(mContext.getString(R.string.link_redirected), Settings.get().getDefaultBrowserLabel())
                MainApplication.saveUrlInHistory(mContext, null, urlAsString, title)
                return null
            }
        }

        var showAppPicker = false

        val packageManager = mContext.packageManager
        val urlString = urlAsString
        var tempResolveInfos: List<ResolveInfo>? = ArrayList()
        if (urlString != mContext.getString(R.string.empty_bubble_page)) {
            tempResolveInfos = Settings.get().getAppsThatHandleUrl(urlString, packageManager)
        }
        val resolveInfos = tempResolveInfos
        val defaultAppResolveInfo = Settings.get().getDefaultAppForUrl(url, resolveInfos)
        if (resolveInfos != null && resolveInfos.isNotEmpty()) {
            if (defaultAppResolveInfo != null) {
                if (handleResolveInfo(defaultAppResolveInfo, urlAsString, urlLoadStartTime)) {
                    return null
                }
            } else if (resolveInfos.size == 1) {
                if (handleResolveInfo(resolveInfos[0], urlAsString, urlLoadStartTime)) {
                    return null
                }
            } else {
                // If this app itself is a valid resolve target, do not show other options to open the content.
                for (info in resolveInfos) {
                    if (info.activityInfo.packageName.startsWith("com.peek.browser.playstore")) {
                        showAppPicker = false
                        break
                    } else {
                        showAppPicker = true
                    }
                }
            }
        }

        var openedFromItself = false
        if (null != openedFromAppName && (openedFromAppName == SourceTag.OPENED_URL_FROM_NEW_TAB
                        || openedFromAppName == SourceTag.OPENED_URL_FROM_HISTORY)) {
            showAppPicker = true
            openedFromItself = true
        }
        mCanAutoDisplayLink = true
        val result = openUrlInTab(urlAsString, urlLoadStartTime, setAsCurrentTab, showAppPicker,
                !(openedFromAppName?.equals(SourceTag.OPENED_URL_FROM_MAIN_NEW_TAB) ?: false))

        // Show app picker after creating the tab to load so that we have the instance to close if redirecting to an app, re #292.
        if (!openedFromItself && showAppPicker && !MainApplication.sShowingAppPickerDialog && 0 != resolveInfos?.size) {
            val dialog = ActionItem.getActionItemPickerAlert(mContext, resolveInfos!!, R.string.pick_default_app,
                    object : ActionItem.OnActionItemDefaultSelectedListener {
                        override fun onSelected(actionItem: ActionItem, always: Boolean) {
                            var loaded = false
                            for (resolveInfo in resolveInfos!!) {
                                if (resolveInfo.activityInfo.packageName == actionItem.mPackageName
                                        && resolveInfo.activityInfo.name == actionItem.mActivityClassName) {
                                    if (always) {
                                        Settings.get().setDefaultApp(urlAsString, resolveInfo)
                                    }

                                    // Jump out of the loop and load directly via a BubbleView below
                                    if (resolveInfo.activityInfo.packageName == mAppPackageName) {
                                        break
                                    }

                                    loaded = MainApplication.loadIntent(mContext, actionItem.mPackageName,
                                            actionItem.mActivityClassName, urlAsString, -1, true)
                                    break
                                }
                            }

                            if (loaded) {
                                Settings.get().addRedirectToApp(urlAsString)
                                closeTab(result, contentViewShowing(), false)
                                if (activeTabCount == 0 && !Prompt.isShowing()) {
                                    finish()
                                }
                                // L_WATCH: L currently lacks getRecentTasks(), so minimize here
                                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.KITKAT) {
                                    get()!!.switchToBubbleView(false)
                                }
                            }
                        }
                    })

            dialog.setOnDismissListener(DialogInterface.OnDismissListener {
                MainApplication.sShowingAppPickerDialog = false
            })

            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            Util.showThemedDialog(dialog)
            MainApplication.sShowingAppPickerDialog = true
        }

        return result
    }

    fun openUrlInTab(url: String, urlLoadStartTime: Long, setAsCurrentTab: Boolean, hasShownAppPicker: Boolean,
                      performEmptyClick: Boolean): TabView {
        setHiddenByUser(false)

        if (activeTabCount == 0) {
            mBubbleDraggable.visibility = View.VISIBLE
            collapseBubbleFlow(0)
            mBubbleFlowDraggable.visibility = View.GONE
            // Only do this snap if ContentView is showing. No longer obliterates slide-in animation
            if (contentViewShowing()) {
                mBubbleDraggable.snapToBubbleView()
            } else {
                val bubbleRestingPoint = Settings.get().getBubbleRestingPoint()
                val fromX = Settings.get().getBubbleStartingX(bubbleRestingPoint)
                mBubbleDraggable.slideOnScreen(fromX, bubbleRestingPoint.y, bubbleRestingPoint.x, bubbleRestingPoint.y,
                        Constant.BUBBLE_SLIDE_ON_SCREEN_TIME)
            }
        }

        val result = mBubbleFlowDraggable.openUrlInTab(url, urlLoadStartTime, setAsCurrentTab, hasShownAppPicker, performEmptyClick)
        showBadge(activeTabCount > 1)
        ++mBubblesLoaded

        mOpenUrlInfos.add(OpenUrlInfo(url, urlLoadStartTime))

        return result!!
    }

    fun restoreTab(tab: TabView) {
        mBubbleFlowDraggable.restoreTab(tab)
        // Only do this if there's just 1 tab open. Fix #446
        if (activeTabCount == 1) {
            // If the bubble was closed when in BubbleView mode, forcibly reset to Bubble mode
            if (mBubbleDraggable.getCurrentMode() == BubbleDraggable.Mode.BubbleView) {
                mBubbleDraggable.visibility = View.VISIBLE
                collapseBubbleFlow(0)
                mBubbleFlowDraggable.visibility = View.GONE

                // Ensure CanvasView has a valid ContentView
                val event = CurrentTabChangedEvent()
                event.mTab = tab
                EventBus.post(event)

                mBubbleDraggable.snapToBubbleView()
            } else {
                val bubblePeriod = Constant.BUBBLE_ANIM_TIME / 1000f
                val contentPeriod = bubblePeriod * 0.666667f      // 0.66667 is the normalized t value when f = 1.0f for overshoot interpolator of 0.5 tension
                expandBubbleFlow((contentPeriod * 1000).toLong(), false)
                if (!Constant.ACTIVITY_WEBVIEW_RENDERING) {
                    // No need to do this if above is true because it's already done
                    showExpandedActivity()
                }
            }
        } else {
            showBadge(true)
        }
        ++mBubblesLoaded
    }

    fun showBadge(show: Boolean) {
        val tabCount = mBubbleFlowDraggable.getActiveTabCount()
        mBubbleDraggable.mBadgeView.setCount(tabCount)
        if (show) {
            if (tabCount > 1 && mBubbleDraggable.getCurrentMode() == BubbleDraggable.Mode.BubbleView) {
                mBubbleDraggable.mBadgeView.show()
            }
        } else {
            mBubbleDraggable.mBadgeView.hide()
        }
    }

    fun contentViewShowing(): Boolean {
        return mBubbleDraggable.getCurrentMode() == BubbleDraggable.Mode.ContentView
    }

    private var mLastOpenTabFromNotificationTime: Long = -1

    fun openTabFromNotification(notificationId: Int) {
        mLastOpenTabFromNotificationTime = System.currentTimeMillis()
        val contentViewShowing = contentViewShowing()
        mBubbleFlowDraggable.setCurrentTabByNotification(notificationId, contentViewShowing)
        if (!contentViewShowing) {
            mBubbleDraggable.switchToExpandedView()
        }
    }

    fun startFileBrowser(acceptTypes: Array<String>, filePathCallback: ValueCallback<Array<Uri>>) {
        EventBus.post(
                ExpandedActivity.ShowFileBrowserEvent(acceptTypes, filePathCallback))
    }

    fun closeTab(notificationId: Int): Boolean {
        val tabView = mBubbleFlowDraggable.getTabByNotification(notificationId)
        if (tabView != null) {
            return closeTab(tabView, contentViewShowing() && mScreenOn, true)
        }
        return false
    }

    fun closeCurrentTab(action: Constant.BubbleAction, animateOff: Boolean): Boolean {
        return closeTab(mBubbleFlowDraggable.getCurrentTab(), action, animateOff, true)
    }

    @JvmOverloads
    fun closeTab(tabView: TabView?, animateOff: Boolean, canShowUndoPrompt: Boolean = true): Boolean {
        return closeTab(tabView, Constant.BubbleAction.Close, animateOff, canShowUndoPrompt)
    }

    fun closeTab(tabView: TabView?, action: Constant.BubbleAction, animateOff: Boolean, canShowUndoPrompt: Boolean): Boolean {

        if (tabView == null) {
            CrashTracking.log("closeTab attempt on null tabView")
            return false
        }

        // If the tab is already closing, do nothing. Otherwise we could end up in a weird state,
        // where we attempt to show multiple prompts and crashing upon tab restore.
        if (tabView.mIsClosing) {
            CrashTracking.log("Ignoring duplicate tabView close request")
            return false
        }
        tabView.mIsClosing = true

        val contentViewShowing = contentViewShowing()
        if (contentViewShowing && mBubbleFlowDraggable.visibility == View.GONE && mBubbleFlowDraggable.isExpanded()) {
            mBubbleFlowDraggable.visibility = View.VISIBLE
        }
        CrashTracking.log("MainController.closeTab(): action:" + action.toString() + ", contentViewShowing:" + contentViewShowing
                + ", visibleTabCount:" + visibleTabCount + ", activeTabCount:" + activeTabCount + ", canShowUndoPrompt:" + canShowUndoPrompt
                + ", animateOff:" + animateOff + ", canShowUndoPrompt:" + canShowUndoPrompt)
        mBubbleFlowDraggable.closeTab(tabView, animateOff, action, tabView.getTotalTrackedLoadTime())

        val activeTabCount = activeTabCount

        if (activeTabCount > 0
                && null != mBubbleFlowDraggable.getCurrentTab()
                && mBubbleFlowDraggable.isExpanded()) {
            adjustBubblesPanel(0, 0, false, true)
        }

        showBadge(activeTabCount > 1)
        if (activeTabCount == 0) {
            hideBubbleDraggable()
            // Ensure BubbleFlowDraggable gets at least 1 update in the event items are animating off screen. See #237.
            scheduleUpdate()

            EventBus.post(mMinimizeExpandedActivityEvent)
        }

        if (canShowUndoPrompt && Settings.get().getShowUndoCloseTab()) {
            showClosePrompt(tabView)
        } else {
            destroyTabOnDelay(tabView)
        }

        return activeTabCount > 0
    }

    private fun destroyTabOnDelay(tabView: TabView) {
        mBubbleDraggable.postDelayed({
            tabView.destroy()
        }, 500)
    }

    private fun showClosePrompt(tabView: TabView) {
        var title: String? = null
        if (tabView.getUrl() != null && MainApplication.sTitleHashMap != null) {
            val urlAsString = tabView.getUrl().toString()
            title = MainApplication.sTitleHashMap?.get(urlAsString)
        }
        val message: String
        message = if (title != null) {
            String.format(mContext.resources.getString(R.string.undo_close_tab_title), title)
        } else {
            mContext.resources.getString(R.string.undo_close_tab_no_title)
        }
        tabView.mWasRestored = false
        Prompt.show(message,
                mContext.resources.getString(R.string.action_undo).uppercase(),
                Prompt.LENGTH_SHORT,
                true,
                object : Prompt.OnPromptEventListener {
                    override fun onActionClick() {
                        if (!tabView.mWasRestored) {
                            tabView.mIsClosing = false
                            restoreTab(tabView)
                            tabView.getContentView()!!.onRestored()
                        }
                    }

                    override fun onClose() {
                        if (!tabView.mWasRestored) {
                            tabView.destroy()
                        }
                    }
                }
        )
    }

    @JvmOverloads
    fun closeAllBubbles(removeFromCurrentTabs: Boolean = true) {
        mBubbleFlowDraggable.closeAllBubbles(removeFromCurrentTabs)
        hideBubbleDraggable()
    }

    private fun hideBubbleDraggable() {
        mBubbleDraggable.visibility = View.GONE
    }

    private var mDeferredExpandBubbleFlowTime: Long = -1
    private var mDeferredExpandBubbleFlowHideDraggable = false

    fun expandBubbleFlow(time: Long, hideDraggable: Boolean) {
        if (Constant.ACTIVITY_WEBVIEW_RENDERING) {
            mDeferredExpandBubbleFlowTime = time
            mDeferredExpandBubbleFlowHideDraggable = hideDraggable
            showExpandedActivity()
        } else {
            doExpandBubbleFlow(time, hideDraggable)
        }
    }

    private fun doExpandBubbleFlow(time: Long, hideDraggable: Boolean) {
        mBeginExpandTransitionEvent.mPeriod = time / 1000.0f

        // The flow stopped tracking the bubble while GONE — reposition it to the
        // bubble's current spot before it becomes visible.
        mBubbleFlowDraggable.syncWithBubble(mBubbleDraggable)
        mBubbleFlowDraggable.visibility = View.VISIBLE
        // The bubble's expand flight starts before the flow becomes visible, so the
        // OnWindowedAnimationListener skipped it — start the follow-session now.
        val bubbleHelper = mBubbleDraggable.draggableHelper
        if (bubbleHelper.isWindowedAnimationActive()) {
            mBubbleFlowDraggable.beginFollowingBubble(
                    bubbleHelper.getAnimInitialX(), bubbleHelper.getAnimInitialY(),
                    bubbleHelper.getAnimTargetX(), bubbleHelper.getAnimTargetY())
        }
        mSetBubbleFlowGone = false // cancel any pending operation to set visibility to GONE (see #190)
        mBubbleFlowDraggable.expand(time, mOnBubbleFlowExpandFinishedListener)

        EventBus.post(mBeginExpandTransitionEvent)

        if (hideDraggable) {
            mBubbleDraggable.postDelayed(mSetBubbleGoneRunnable, 33)
        }
    }

    fun collapseBubbleFlow(time: Long) {
        try {
            mCurrentAdjustment = mOriginalLocationY
            val currentTabView = currentTab
            if (null != currentTabView) {
                if (currentTabView.adjustBubblesPanel(mCurrentAdjustment, false, 0)) {
                    ObjectAnimator
                            .ofFloat(mBubbleFlowDraggable.getChildAt(0), "translationY", mCurrentAdjustment)
                            .setDuration(ANIMATION_DURATION_BUBBLES_SHOW.toLong())
                            .start()
                }
            }
            if (mBubbleFlowDraggable.isExpanded()) {
                mBubbleFlowDraggable.visibility = View.VISIBLE
            }
        } catch (exc: NullPointerException) {
        }

        mBubbleFlowDraggable.collapse(time, mOnBubbleFlowCollapseFinishedListener)
    }

    fun switchToBubbleView(fromCloseSystemDialogs: Boolean) {
        mCanAutoDisplayLink = false
        if (get()!!.activeTabCount > 0) {
            mBubbleDraggable.switchToBubbleView(fromCloseSystemDialogs)
        } else {
            // If there's no tabs, ensuring pressing Home will cause the CanvasView to go away. Fix #448
            EventBus.post(EndCollapseTransitionEvent())
        }
    }

    fun switchToExpandedView() {
        mBubbleDraggable.switchToExpandedView()
    }

    fun beginAppPolling() {
        mAppPoller.beginAppPolling()
    }

    fun endAppPolling() {
        mAppPoller.endAppPolling()
    }

    val mAppPollerListener: AppPoller.AppPollerListener = object : AppPoller.AppPollerListener {
        override fun onAppChanged() {
            switchToBubbleView(false)
        }
    }

    fun showPreviousBubble() {
        mBubbleFlowDraggable.previousTab()
    }

    fun showNextBubble() {
        mBubbleFlowDraggable.nextTab()
    }

    fun onStateChangedEvent(event: MainApplication.StateChangedEvent) {
        closeAllBubbles(false)
        val urls = Settings.get().loadCurrentTabs()
        if (urls.size > 0) {
            for (url in urls) {
                MainApplication.openLink(mContext, url, null)
            }
        }
    }

    fun onEndAnimateFinalTabAway(event: EndAnimateFinalTabAwayEvent) {
        mBubbleFlowDraggable.visibility = View.GONE
    }

    fun reloadAllTabs(context: Context): Boolean {
        CrashTracking.log("MainController.reloadAllTabs()")
        var reloaded = false
        closeAllBubbles(false)
        val urls = Settings.get().loadCurrentTabs()
        if (urls.size > 0) {
            for (url in urls) {
                MainApplication.openLink(context.applicationContext, url, null)
                reloaded = true
            }
        }

        return reloaded
    }

    private var mHiddenByUser = false

    fun setHiddenByUser(hiddenByUser: Boolean) {
        if (mHiddenByUser != hiddenByUser) {
            mHiddenByUser = hiddenByUser
            if (mHiddenByUser) {
                when (mBubbleDraggable.getCurrentMode()) {
                    BubbleDraggable.Mode.ContentView -> mBubbleDraggable.snapToBubbleView()
                    else -> {}
                }
                EventBus.post(HideContentEvent())
                EventBus.post(MainService.ShowUnhideNotificationEvent())
            } else {
                EventBus.post(CurrentTabChangedEvent(mBubbleFlowDraggable.getCurrentTab(), true))
                EventBus.post(MainService.ShowDefaultNotificationEvent())
                EventBus.post(UnhideContentEvent())
            }
            setCanDisplay(!mHiddenByUser)
        }
    }

    //private static final String SCREEN_LOCK_TAG = "screenlock";

    private fun setCanDisplay(canDisplay: Boolean) {
        if (canDisplay == mCanDisplay) {
            return
        }
        //Log.d(SCREEN_LOCK_TAG, "*** setCanDisplay() - old:" + mCanDisplay + ", new:" + canDisplay);
        mCanDisplay = canDisplay
        if (canDisplay) {
            enableRootWindows()
        } else {
            disableRootWindows()
        }
    }

    private fun updateKeyguardLocked() {
        if (mKeyguardManager == null) {
            mKeyguardManager = mContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager?
        }
        val keyguardManager = mKeyguardManager
        if (keyguardManager != null) {
            val isLocked = keyguardManager.isKeyguardLocked
            //Log.d(SCREEN_LOCK_TAG, "keyguardManager.isKeyguardLocked():" + mCanDisplay);
            setCanDisplay(!isLocked)
        }
    }

    fun updateScreenState(action: String) {
        //Log.d(SCREEN_LOCK_TAG, "---" + action);
        CrashTracking.log("BubbleFlowView - updateScreenState(): $action")

        if (action == Intent.ACTION_SCREEN_OFF) {
            mScreenOn = false
            setCanDisplay(false)
            EventBus.post(mScreenOffEvent)
        } else if (action == Intent.ACTION_SCREEN_ON) {
            updateKeyguardLocked()
            mScreenOn = true
            EventBus.post(mScreenOnEvent)
        } else if (action == Intent.ACTION_USER_PRESENT) {
            setCanDisplay(!mHiddenByUser)
            EventBus.post(mUserPresentEvent)
        }
    }


    fun isScreenOn(): Boolean {
        return mScreenOn
    }

    val currentTab: TabView?
        get() = mBubbleFlowDraggable.getCurrentTab()

    companion object {
        private const val TAG = "MainController"
        private const val PIXELS_TO_SKIP_BEFORE_SCROLL = 50
        private const val ANIMATION_DURATION_BUBBLES_HIDE = 850
        private const val ANIMATION_DURATION_BUBBLES_SHOW = 765

        private var sInstance: MainController? = null

        @JvmStatic
        fun addRootWindow(v: View, lp: WindowManager.LayoutParams) {
            val mc = get()!!
            if (!mc.mRootViews.contains(v)) {
                mc.mRootViews.add(v)
                if (mc.mRootWindowsVisible) {
                    mc.mWindowManager.addView(v, lp)
                }
            }
        }

        @JvmStatic
        fun removeRootWindow(v: View) {
            val mc = get()!!
            if (mc.mRootViews.contains(v)) {
                mc.mRootViews.remove(v)
                if (mc.mRootWindowsVisible) {
                    mc.mWindowManager.removeView(v)
                }
            }
        }

        @JvmStatic
        fun updateRootWindowLayout(v: View, lp: WindowManager.LayoutParams) {
            val mc = get()!!
            if (mc.mRootWindowsVisible && mc.mRootViews.contains(v)) {
                mc.mWindowManager.updateViewLayout(v, lp)
            }
        }

        @JvmStatic
        fun get(): MainController? {
            return sInstance
        }

        @JvmStatic
        fun create(context: Context, eventHandler: EventHandler) {
            if (sInstance != null) {
                throw RuntimeException("Only one instance of MainController allowed at any one time")
            }
            sInstance = MainController(context, eventHandler)
        }

        @JvmStatic
        fun destroy() {
            val instance = sInstance ?: throw RuntimeException("No instance to destroy")

            Settings.get().saveData()

            EventBus.unsubscribeAll(instance)

            if (Settings.get().isIncognitoMode) {
                val cookieManager = CookieManager.getInstance()
                if (cookieManager != null && cookieManager.hasCookies()) {
                    cookieManager.removeAllCookie()
                }
            }

            if (Constant.PROFILE_FPS) {
                instance.mWindowManager.removeView(instance.mTextView)
            }
            val keyguardListener = instance.mKeyguardLockedStateListener
            if (keyguardListener != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                instance.mKeyguardManager?.removeKeyguardLockedStateListener(keyguardListener)
                instance.mKeyguardLockedStateListener = null
            }
            instance.mBubbleDraggable.destroy()
            instance.mBubbleFlowDraggable.destroy()
            instance.mCanvasView.destroy()
            // The warm WebView references the service context — don't let it outlive us.
            WebViewPreloader.shutdown()
            instance.mChoreographer.removeFrameCallback(instance)
            instance.endAppPolling()
            sInstance = null
        }

        // Before this version select elements would crash WebView in a background service
        // STABLE_SELECT_WEBVIEW_VERSIONCODE used to be set to 249007650 because drop downs
        // worked on some devices but not all.  For example it works on a Samsung Galaxy S4 but not
        // on Nexus 6P.  Hopefully the next update will work across all devices.
        const val STABLE_SELECT_WEBVIEW_VERSIONCODE = Long.MAX_VALUE

        private var mVersionNumber: Long = 0

        @JvmField
        var sStartExpandedActivityTime: Long = -1

        private const val MAX_SAMPLE_COUNT = 60 * 10
        private const val MAX_VALID_TIME = 10.0f / 60.0f

        @JvmStatic
        fun doCrash() {
            throw RuntimeException("Forced Exception")
        }
    }
}
