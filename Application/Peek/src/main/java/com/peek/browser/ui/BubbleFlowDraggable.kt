/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.peek.browser.Config
import com.peek.browser.Constant
import com.peek.browser.MainApplication
import com.peek.browser.MainController
import com.peek.browser.R
import com.peek.browser.Settings
import com.peek.browser.physics.Draggable
import com.peek.browser.physics.DraggableHelper
import com.peek.browser.util.CrashTracking
import com.peek.browser.util.EventBus
import com.peek.browser.util.VerticalGestureListener
import com.peek.browser.webrender.WebViewPreloader
import java.net.MalformedURLException

class BubbleFlowDraggable @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : BubbleFlowView(context, attrs, defStyle), Draggable {

    private lateinit var mDraggableHelper: DraggableHelper
    private var mEventHandler: EventHandler? = null
    private var mBubbleFlowWidth: Int = 0
    private var mBubbleFlowHeight: Int = 0
    private var mCurrentTab: TabView? = null
    private var mBubbleDraggable: BubbleDraggable? = null
    private val mTempSize = Point()

    private val mCurrentTabChangedEvent = MainController.CurrentTabChangedEvent()
    private val mCurrentTabResumeEvent = MainController.CurrentTabResumeEvent()
    private val mCurrentTabPauseEvent = MainController.CurrentTabPauseEvent()

    interface EventHandler {
        fun onMotionEvent_Touch(sender: BubbleFlowDraggable, event: DraggableHelper.TouchEvent)
        fun onMotionEvent_Move(sender: BubbleFlowDraggable, event: DraggableHelper.MoveEvent)
        fun onMotionEvent_Release(sender: BubbleFlowDraggable, event: DraggableHelper.ReleaseEvent)
    }

    override val isDragging: Boolean
        get() = false

    fun configure(eventHandler: EventHandler?) {
        mBubbleFlowWidth = Config.mScreenWidth
        mBubbleFlowHeight = resources.getDimensionPixelSize(R.dimen.bubble_pager_height)

        configure(mBubbleFlowWidth,
                resources.getDimensionPixelSize(R.dimen.bubble_pager_item_width),
                resources.getDimensionPixelSize(R.dimen.bubble_pager_item_height))

        setBubbleFlowViewListener(object : BubbleFlowView.Listener {
            override fun onCenterItemClicked(sender: BubbleFlowView, view: View) {
                try {
                    MainController.get()!!.switchToBubbleView(false)
                } catch (exc: NullPointerException) {
                    CrashTracking.logHandledException(exc)
                }
            }

            override fun onCenterItemLongClicked(sender: BubbleFlowView, view: View) {
                if (view is TabView) {
                    val mainController = MainController.get()
                    if (mainController!!.activeTabCount != 0) {
                        mainController.startDraggingFromContentView()
                    }
                }
            }

            override fun onCenterItemSwiped(gestureDirection: VerticalGestureListener.GestureDirection) {
                // TODO: Implement me
            }

            override fun onCenterItemChanged(sender: BubbleFlowView, view: View) {
                setCurrentTab(view as TabView)
            }
        })

        val windowManagerParams = WindowManager.LayoutParams()
        windowManagerParams.gravity = Gravity.TOP or Gravity.LEFT
        windowManagerParams.x = 0
        windowManagerParams.y = 0
        windowManagerParams.height = mBubbleFlowHeight
        windowManagerParams.width = mBubbleFlowWidth
        windowManagerParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        windowManagerParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        windowManagerParams.format = PixelFormat.TRANSPARENT
        windowManagerParams.setTitle("Peek: BubbleFlowView")

        mDraggableHelper = DraggableHelper(this, windowManagerParams, false, object : DraggableHelper.OnTouchActionEventListener {

            override fun onActionDown(event: DraggableHelper.TouchEvent) {
                if (mEventHandler != null) {
                    mEventHandler!!.onMotionEvent_Touch(this@BubbleFlowDraggable, event)
                }
            }

            override fun onActionMove(event: DraggableHelper.MoveEvent) {
                if (mEventHandler != null) {
                    mEventHandler!!.onMotionEvent_Move(this@BubbleFlowDraggable, event)
                }
            }

            override fun onActionUp(event: DraggableHelper.ReleaseEvent) {
                if (mEventHandler != null) {
                    mEventHandler!!.onMotionEvent_Release(this@BubbleFlowDraggable, event)
                }
            }
        })

        mEventHandler = eventHandler

        if (mDraggableHelper.isAlive()) {
            MainController.addRootWindow(this, windowManagerParams)

            setExactPos(0, 0)
        }
    }

    override fun configure(width: Int, itemWidth: Int, itemHeight: Int) {
        mBubbleFlowWidth = Config.mScreenWidth

        super.configure(width, itemWidth, itemHeight)

        if (this::mDraggableHelper.isInitialized && mDraggableHelper.getWindowManagerParams() != null) {
            val windowManagerParams = mDraggableHelper.getWindowManagerParams()
            windowManagerParams!!.width = width
            windowManagerParams.x = 0
            windowManagerParams.y = 0
            windowManagerParams.gravity = Gravity.TOP or Gravity.LEFT
            // Keep the helper's restore-size in sync, else ending a windowed animation after
            // rotation would restore the stale pre-rotation width.
            mDraggableHelper.setBaseSize(width, windowManagerParams.height)

            setExactPos(0, 0)
        }
    }

    fun destroy() {
        //setOnTouchListener(null);
        mDraggableHelper.destroy()
    }

    fun nextTab() {
        val tabCount = getActiveTabCount()
        val currentTab = getCurrentTab()
        if (currentTab != null) {
            val tabIndex = getIndexOfView(currentTab)
            val nextTabIndex = tabIndex + 1

            if (nextTabIndex < tabCount) {
                setCenterIndex(nextTabIndex)
            }
        }
    }

    fun previousTab() {
        val tabCount = getActiveTabCount()
        val currentTab = getCurrentTab()
        if (currentTab != null) {
            val tabIndex = getIndexOfView(currentTab)
            val nextTabIndex = tabIndex - 1

            if (nextTabIndex >= 0) {
                setCenterIndex(nextTabIndex)
            }
        }
    }

    // The number of items currently actively managed by the BubbleFlowView.
    // Note: it's possible for getActiveTabCount() to equal 0, but getVisibleTabCount() to be > 0.
    fun getActiveTabCount(): Int {
        return mViews.size
    }

    fun isUrlActive(urlAsString: String): Boolean {
        for (v in mViews) {
            val tabView = v as TabView
            if (tabView.getUrl().toString() == urlAsString) {
                return true
            }
        }

        return false
    }

    // The number of items being drawn on the BubbleFlowView.
    // Note: It's possible for getVisibleTabCount() to be greater than getActiveTabCount() in the event an item is animating off.
    // Eg, when Back is pressed to dismiss the last Bubble.
    // This function does NOT return the number of items currently fitting on the current width of the screen.
    fun getVisibleTabCount(): Int {
        return mContent.childCount
    }

    override fun expand(time: Long, animationEventListener: AnimationEventListener?): Boolean {

        CrashTracking.log("BubbleFlowDraggable.expand(): time:$time")

        if (isExpanded() == false && mCurrentTab != null) {
            // Ensure the centerIndex matches the current bubble. This should only *NOT* be the case when
            // restoring with N Bubbles from a previous session and the user clicks to expand the BubbleFlowView.
            val currentTabIndex = getIndexOfView(mCurrentTab)
            val centerIndex = getCenterIndex()
            if (centerIndex > -1 && currentTabIndex != centerIndex && isAnimatingToCenterIndex() == false) {
                setCenterIndex(currentTabIndex, false)
            }
        }

        if (super.expand(time, animationEventListener)) {
            val centerIndex = getCenterIndex()
            if (centerIndex > -1) {
                setCurrentTab(mViews[centerIndex] as TabView)
            }
            return true
        }

        return false
    }

    fun getCurrentTab(): TabView? {
        return mCurrentTab
    }

    fun setCurrentTabAsActive() {
        if (null != mCurrentTab) {
            setCurrentTab(mCurrentTab)
        }
    }

    private fun setCurrentTab(tab: TabView?) {
        mCurrentTabResumeEvent.mTab = tab
        EventBus.post(mCurrentTabResumeEvent)
        if (mCurrentTab === tab) {
            if (null != mCurrentTab) {
                val contentView = mCurrentTab!!.getContentView()
                if (null != contentView) {
                    contentView.setTabAsActive()
                }
            }
            return
        }

        if (mCurrentTab != null) {
            mCurrentTab!!.setImitator(null)
        }
        mCurrentTabPauseEvent.mTab = mCurrentTab
        EventBus.post(mCurrentTabPauseEvent)
        mCurrentTab = tab
        mCurrentTabChangedEvent.mTab = tab
        EventBus.post(mCurrentTabChangedEvent)
        if (mCurrentTab != null) {
            mCurrentTab!!.setImitator(mBubbleDraggable)
            val contentView = mCurrentTab!!.getContentView()
            if (null != contentView) {
                contentView.setTabAsActive()
            }
        }
    }

    fun setBubbleDraggable(bubbleDraggable: BubbleDraggable) {
        mBubbleDraggable = bubbleDraggable
    }

    override val draggableHelper: DraggableHelper
        get() = mDraggableHelper

    override fun update(dt: Float) {
        mDraggableHelper.update(dt)
    }

    fun syncWithBubble(draggable: Draggable) {
        // Use the bubble's logical position/size rather than its real WindowManager params directly -
        // during a windowed animation (see DraggableHelper.beginWindowExpansion) the real window is
        // temporarily grown to cover the whole travel path, so its raw x/y/width/height no longer
        // reflect where the bubble is actually drawn.
        val bubbleX = draggable.draggableHelper.getXPos()
        val bubbleY = draggable.draggableHelper.getYPos()

        val xOffset = (Config.mBubbleWidth.toInt() - mBubbleFlowWidth) / 2
        val yOffset = (Config.mBubbleHeight.toInt() - mBubbleFlowHeight) / 2

        mDraggableHelper.setExactPos(bubbleX + xOffset, bubbleY + yOffset)
    }

    // Mirror the bubble's windowed animation (see DraggableHelper.beginWindowExpansion) on this
    // window: grow once across the whole travel path so the per-frame syncWithBubble() calls
    // translate the view instead of paying a WindowManager IPC per frame.
    fun beginFollowingBubble(bubbleFromX: Int, bubbleFromY: Int, bubbleToX: Int, bubbleToY: Int) {
        val xOffset = (Config.mBubbleWidth.toInt() - mBubbleFlowWidth) / 2
        val yOffset = (Config.mBubbleHeight.toInt() - mBubbleFlowHeight) / 2
        mDraggableHelper.beginFollow(bubbleFromX + xOffset, bubbleFromY + yOffset,
                bubbleToX + xOffset, bubbleToY + yOffset)
    }

    fun endFollowingBubble() {
        mDraggableHelper.endFollow()
    }

    override fun onOrientationChanged() {
        clearTargetPos()

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.getSize(mTempSize)
        configure(mTempSize.x, mItemWidth, mItemHeight)
        updatePositions()
        updateScales(scrollX)

        setExactPos(0, 0)
        if (null != mCurrentTab) {
            mCurrentTab!!.getContentView()!!.onOrientationChanged()
        }
    }

    fun clearTargetPos() {
        mDraggableHelper.clearTargetPos()
    }

    fun setExactPos(x: Int, y: Int) {
        mDraggableHelper.setExactPos(x, y)
    }

    @Throws(MalformedURLException::class)
    fun openUrlInTab(url: String, urlLoadStartTime: Long, setAsCurrentTab: Boolean, hasShownAppPicker: Boolean,
                      performEmptyClick: Boolean): TabView? {
        val tabView: TabView
        try {
            val inflater = LayoutInflater.from(context)
            tabView = inflater.inflate(R.layout.view_tab, null) as TabView
            tabView.configure(url, urlLoadStartTime, hasShownAppPicker, performEmptyClick)
        } catch (e: MalformedURLException) {
            // TODO: Inform the user somehow?
            return null
        }

        // Only insert next to current Bubble when in ContentView mode. Ensures links opened when app is
        // minimized are added to the end.
        add(tabView, mBubbleDraggable!!.getCurrentMode() == BubbleDraggable.Mode.ContentView)

        mBubbleDraggable!!.mBadgeView.setCount(getActiveTabCount())

        if (setAsCurrentTab) {
            setCurrentTab(tabView)
        }

        saveCurrentTabs()

        // Warm the next tab's WebView while the main thread is idle.
        WebViewPreloader.preload(context)

        return tabView
    }

    fun restoreTab(tabView: TabView) {
        add(tabView, mBubbleDraggable!!.getCurrentMode() == BubbleDraggable.Mode.ContentView)

        mBubbleDraggable!!.mBadgeView.setCount(getActiveTabCount())

        saveCurrentTabs()

        if (getActiveTabCount() == 1) {
            setCurrentTab(tabView)
        }

        tabView.mWasRestored = true
    }

    override fun remove(index: Int, animateOff: Boolean, removeFromList: Boolean, onRemovedListener: OnRemovedListener?) {
        if (index < 0 || index >= mViews.size) {
            return
        }
        val tab = mViews[index] as TabView

        val internalOnRemoveListener = object : OnRemovedListener {
            override fun onRemoved(view: View) {
                if (onRemovedListener != null) {
                    onRemovedListener.onRemoved(view)
                }

                if (getActiveTabCount() == 0 && getVisibleTabCount() == 0) {
                    EventBus.post(MainController.EndAnimateFinalTabAwayEvent())
                }
            }
        }

        super.remove(index, animateOff, removeFromList, internalOnRemoveListener)
        if (animateOff && mSlideOffAnimationPlaying) {
            // Kick off an update so as to ensure BubbleFlowView.update() is always called when animating items off screen (see #189)
            MainController.get()!!.scheduleUpdate()
            if (getActiveTabCount() == 0 && getVisibleTabCount() > 0) {
                // Bit of a hack, but we need to ensure CanvasView has a valid mContentView, so use the one currently being killed.
                // This is perfectly safe, because the view doesn't get destroyed until it has animated off screen.
                val event = MainController.BeginAnimateFinalTabAwayEvent()
                event.mTab = tab
                EventBus.post(event)
            }
        }
    }

    private val mOnTabRemovedListener = object : OnRemovedListener {

        override fun onRemoved(view: View) {
            // Tabs are now destroyed after a time so the Undo close tab functionality works.
            //((TabView)view).destroy();

            (view as TabView).getContentView()!!.onRemoved()
        }
    }

    fun getTabByNotification(notificationId: Int): TabView? {
        if (mViews != null) {
            for (view in mViews) {
                val tabView = view as TabView
                if (tabView.getContentView()!!.getArticleNotificationId() == notificationId) {
                    return tabView
                }
            }
        }

        return null
    }

    fun setCurrentTabByNotification(notificationId: Int, contentViewShowing: Boolean) {
        val tabView = getTabByNotification(notificationId)
        if (tabView != null) {
            val currentTabIndex = getIndexOfView(tabView)
            if (currentTabIndex > -1) {
                val centerIndex = getCenterIndex()
                Log.d("blerg", "centerIndex:$centerIndex, currentTabIndex:$currentTabIndex")
                if (contentViewShowing) {
                    if (centerIndex != currentTabIndex) {
                        setCenterIndex(currentTabIndex, true)
                    }
                } else {
                    if (centerIndex > -1 && currentTabIndex != centerIndex && isAnimatingToCenterIndex() == false) {
                        setCenterIndex(currentTabIndex, false)
                    }
                }
                setCurrentTab(tabView)
            }
        }
    }

    private fun closeTab(tab: TabView, animateRemove: Boolean, removeFromList: Boolean) {
        val index = mViews.indexOf(tab)
        if (index == -1) {
            return
        }


        remove(index, animateRemove, removeFromList, mOnTabRemovedListener)

        // Don't do this if we're animating the final tab off, as the setCurrentTab() call messes with the
        // CanvasView.mContentView, which has already been forcible set above in remove() via BeginAnimateFinalTabAwayEvent.
        val animatingFinalTabOff = getActiveTabCount() == 0 && getVisibleTabCount() > 0 && mSlideOffAnimationPlaying
        if (mCurrentTab === tab && animatingFinalTabOff == false) {
            var newCurrentTab: TabView? = null
            val viewsCount = mViews.size
            if (viewsCount > 0) {
                newCurrentTab = if (viewsCount == 1) {
                    mViews[0] as TabView
                } else if (index < viewsCount) {
                    mViews[index] as TabView
                } else {
                    if (index > 0) {
                        mViews[index - 1] as TabView
                    } else {
                        mViews[0] as TabView
                    }
                }
            }
            setCurrentTab(newCurrentTab)
        }
    }

    private fun postClosedTab(removeFromCurrentTabs: Boolean) {
        if (removeFromCurrentTabs) {
            saveCurrentTabs()
        }
    }

    fun closeTab(tabView: TabView?, animateRemove: Boolean, action: Constant.BubbleAction, totalTrackedLoadTime: Long) {
        if (tabView != null) {
            val url = tabView.getUrl().toString()
            closeTab(tabView, animateRemove, true)
            postClosedTab(true)
            if (action != Constant.BubbleAction.None) {
                MainApplication.handleBubbleAction(context, action, url, totalTrackedLoadTime)
            }
        }
    }

    fun closeAllBubbles(removeFromCurrentTabs: Boolean) {
        var closeCount = 0
        for (view in mViews) {
            closeTab((view as TabView), false, false)
            view.destroy()
            closeCount++
        }

        CrashTracking.log("closeAllbubbles(): closeCount:$closeCount")

        mViews.clear()
        postClosedTab(removeFromCurrentTabs)
    }

    fun updateIncognitoMode(incognito: Boolean) {
        for (view in mViews) {
            (view as TabView).updateIncognitoMode(incognito)
        }
    }

    fun saveCurrentTabs() {
        Settings.get().saveCurrentTabs(mViews)
        CrashTracking.log("saveCurrentTabs()")
    }
}
