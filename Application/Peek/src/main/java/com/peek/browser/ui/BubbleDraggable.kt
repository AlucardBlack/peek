/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.peek.browser.Config
import com.peek.browser.Constant
import com.peek.browser.MainController
import com.peek.browser.R
import com.peek.browser.Settings
import com.peek.browser.physics.Circle
import com.peek.browser.physics.Draggable
import com.peek.browser.physics.DraggableHelper
import com.peek.browser.util.CrashTracking
import com.peek.browser.util.EventBus
import com.peek.browser.util.Util
import java.net.MalformedURLException

class BubbleDraggable @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : BubbleView(context, attrs, defStyle), Draggable {

    private lateinit var mDraggableHelper: DraggableHelper
    private var mOnUpdateListener: Draggable.OnUpdateListener? = null
    lateinit var mBadgeView: BadgeView
    private var mCanvasView: CanvasView? = null
    private var mBubbleFlowDraggable: BubbleFlowDraggable? = null
    private val mTractorBeamIntersectionPoint = Util.Point()

    private val mBeginBubbleDragEvent = MainController.BeginBubbleDragEvent()
    private val mDraggableBubbleMovedEvent = MainController.DraggableBubbleMovedEvent()
    private val mEndBubbleDragEvent = MainController.EndBubbleDragEvent()
    private val mBeginCollapseTransitionEvent = MainController.BeginCollapseTransitionEvent()
    private val mEndCollapseTransitionEvent = MainController.EndCollapseTransitionEvent()

    // Physics state
    enum class Mode {
        BubbleView,
        ContentView
    }

    private var mCurrentSnapTarget: BubbleTargetView? = null
    private var mHasMoved: Boolean = false
    private var mTouchDown: Boolean = false
    private var mTouchInitialX: Int = 0
    private var mTouchInitialY: Int = 0
    private var mAnimActive: Boolean = false
    private lateinit var mMode: Mode
    private var mTimeOnSnapTarget: Float = 0f
    private lateinit var mCircle: Circle

    override val isDragging: Boolean
        get() = mTouchDown

    fun getCurrentMode(): Mode {
        return mMode
    }

    private fun onAnimComplete() {
        Util.Assert(mAnimActive, "mAnimActive=$mAnimActive")
        mAnimActive = false
    }

    private fun doSnap() {
        var xp = (0.5f + mDraggableHelper.getXPos() + Config.mBubbleWidth * 0.5f).toInt()
        val yp = mDraggableHelper.getYPos()

        xp = if (xp < Config.mScreenCenterX) {
            Config.mBubbleSnapLeftX
        } else {
            Config.mBubbleSnapRightX
        }

        animate().alpha(Constant.BUBBLE_MODE_ALPHA).setDuration(Constant.BUBBLE_ANIM_TIME.toLong())
        mBadgeView.animate().alpha(Constant.BUBBLE_MODE_ALPHA).setDuration(Constant.BUBBLE_ANIM_TIME.toLong())

        setTargetPos(xp, yp, 0.5f, DraggableHelper.AnimationType.MediumOvershoot, object : DraggableHelper.AnimationEventListener {
            override fun onAnimationComplete() {
                onAnimComplete()
                Settings.get().setBubbleRestingPoint(mDraggableHelper.getXPos(), mDraggableHelper.getYPos())
            }

            override fun onCancel() {
                onAnimComplete()
            }
        })
    }

    fun switchToBubbleView(fromCloseSystemDialogs: Boolean) {
        doAnimateToBubbleView(0, fromCloseSystemDialogs)
    }

    fun switchToExpandedView() {
        doAnimateToContentView()
    }

    private fun doSnapAction(action: Constant.BubbleAction) {
        val mainController = MainController.get()

        val snapTime = mTimeOnSnapTarget - Config.ANIMATE_TO_SNAP_TIME
        if (action == Constant.BubbleAction.Close && snapTime >= Config.CLOSE_ALL_BUBBLES_DELAY) {
            mainController!!.closeAllBubbles()
            mMode = Mode.BubbleView
        } else {
            if (mainController!!.closeCurrentTab(action, false)) {
                if (mMode == Mode.ContentView && action == Constant.BubbleAction.Close) {
                    doAnimateToContentView()
                } else {
                    doAnimateToBubbleView(0, false)
                }
            } else {
                mMode = Mode.BubbleView
            }
        }
    }

    private fun doFlick(vx: Float, vy: Float) {
        var animType = DraggableHelper.AnimationType.Linear
        BubbleTargetView.enableTractor()

        mCurrentSnapTarget = null

        val initialX = mDraggableHelper.getXPos()
        val initialY = mDraggableHelper.getYPos()
        var targetX: Int
        var targetY: Int

        if (Math.abs(vx) < 0.1f) {
            targetX = initialX

            targetY = if (vy > 0.0f) {
                Config.mBubbleMaxY
            } else {
                Config.mBubbleMinY
            }
        } else {

            targetX = if (vx > 0.0f) {
                Config.mBubbleSnapRightX
            } else {
                Config.mBubbleSnapLeftX
            }

            val m = vy / vx

            targetY = (m * (targetX - initialX) + initialY).toInt()

            if (targetY < Config.mBubbleMinY) {
                targetY = Config.mBubbleMinY
                targetX = (initialX + (targetY - initialY) / m).toInt()
            } else if (targetY > Config.mBubbleMaxY) {
                targetY = Config.mBubbleMaxY
                targetX = (initialX + (targetY - initialY) / m).toInt()
            } else {
                animType = DraggableHelper.AnimationType.MediumOvershoot
            }
        }

        val flickDistance = Util.distance(initialX.toFloat(), initialY.toFloat(), targetX.toFloat(), targetY.toFloat())
        val flickVelocity = Math.sqrt((vx * vx + vy * vy).toDouble()).toFloat()
        var flickAnimPeriod = flickDistance / flickVelocity
        flickAnimPeriod = Util.clamp(0.05f, flickAnimPeriod, 0.5f)

        // Check for tractor beam intercept

        // Get center line of flick
        val x0 = initialX + Config.mBubbleWidth * 0.5f
        val y0 = initialY + Config.mBubbleHeight * 0.5f
        val x1 = targetX + Config.mBubbleWidth * 0.5f
        val y1 = targetY + Config.mBubbleHeight * 0.5f

        // Get the closest (if any) snap target that will be able to grab the bubble.
        val tv = mCanvasView!!.getSnapTarget(x0, y0, x1, y1, mTractorBeamIntersectionPoint)
        if (tv != null) {
            val intBubbleX = mTractorBeamIntersectionPoint.x - Config.mBubbleWidth * 0.5f
            val intBubbleY = mTractorBeamIntersectionPoint.y - Config.mBubbleHeight * 0.5f

            val intersectionDistance = Util.distance(initialX.toFloat(), initialY.toFloat(), intBubbleX, intBubbleY)
            var intFraction = 0.0f
            if (flickDistance > 0.0001f) {
                intFraction = intersectionDistance / flickDistance
            }
            try {
                Util.Assert(intFraction >= 0.0f && intFraction <= 1.05f, "intFraction:$intFraction, flickDistance:$flickDistance")
                val intTime = flickAnimPeriod * intFraction

                animType = DraggableHelper.AnimationType.Linear
                flickAnimPeriod = intTime
                targetX = intBubbleX.toInt()
                targetY = intBubbleY.toInt()

                tv.setTargetCenter(mTractorBeamIntersectionPoint.x.toInt(), mTractorBeamIntersectionPoint.y.toInt())
            } catch (exc: AssertionError) {
                if (animType != DraggableHelper.AnimationType.Linear) {
                    flickAnimPeriod += 0.15f
                }
            }

        } else {
            if (animType != DraggableHelper.AnimationType.Linear) {
                flickAnimPeriod += 0.15f
            }
        }

        // #431 - Ensure there is always >0 time to animate the flick.
        flickAnimPeriod = Math.max(0.01f, flickAnimPeriod)

        animate().alpha(Constant.BUBBLE_MODE_ALPHA).setDuration(Constant.BUBBLE_ANIM_TIME.toLong())
        mBadgeView.animate().alpha(Constant.BUBBLE_MODE_ALPHA).setDuration(Constant.BUBBLE_ANIM_TIME.toLong())

        setTargetPos(targetX, targetY, flickAnimPeriod, animType, object : DraggableHelper.AnimationEventListener {
            override fun onAnimationComplete() {
                BubbleTargetView.disableTractor()
                onAnimComplete()

                EventBus.post(mEndBubbleDragEvent)

                if (tv == null) {
                    val x = mDraggableHelper.getXPos()
                    if (x != Config.mBubbleSnapLeftX && x != Config.mBubbleSnapRightX) {
                        doSnap()
                    }
                } else {
                    val action = tv.getAction()
                    doSnapAction(action)
                }
            }

            override fun onCancel() {
                onAnimComplete()
            }
        })
    }

    fun snapToBubbleView() {
        mMode = Mode.BubbleView
        mDraggableHelper.cancelAnimation()

        MainController.get()!!.collapseBubbleFlow(0)

        val bubbleRestingPoint = Settings.get().getBubbleRestingPoint()
        setTargetPos(bubbleRestingPoint.x, bubbleRestingPoint.y, 0f, DraggableHelper.AnimationType.Linear, null)

        EventBus.post(mEndCollapseTransitionEvent)
    }

    private fun doAnimateToBubbleView(animTimeMsIn: Int, fromCloseSystemDialogs: Boolean) {
        var animTimeMs = animTimeMsIn
        if (mAnimActive) {
            if (mMode == Mode.BubbleView) {
                return
            } else {
                mDraggableHelper.cancelAnimation()
            }
        }
        if (fromCloseSystemDialogs && Mode.BubbleView == mMode) {
            return
        }

        //StackTraceElement[] cause = Thread.currentThread().getStackTrace();
        //String log = "";
        //for (StackTraceElement i : cause) {
        //    log += i.toString() + "\n";
        //}
        //Log.d(TAG, "doAnimateToBubbleView() - " + log);

        mTouchDown = false
        mMode = Mode.BubbleView

        if (MainController.get()!!.activeTabCount == 0) {
            return
        }

        if (animTimeMs == 0) {
            animTimeMs = Constant.BUBBLE_ANIM_TIME
        }

        animate().alpha(Constant.BUBBLE_MODE_ALPHA).setDuration(animTimeMs.toLong())
        mBadgeView.animate().alpha(Constant.BUBBLE_MODE_ALPHA).setDuration(animTimeMs.toLong())

        val bubblePeriod = animTimeMs.toFloat() / 1000f
        val contentPeriod = bubblePeriod * 0.666667f      // 0.66667 is the normalized t value when f = 1.0f for overshoot interpolator of 0.5 tension

        val mainController = MainController.get()
        visibility = View.VISIBLE
        val currentTab = mBubbleFlowDraggable!!.getCurrentTab()
        if (currentTab != null) {
            // ensure imitator image is up to date, fixes #228
            mFavicon.clearImage()
            currentTab.setImitator(this)
        }

        val bubbleRestingPoint = Settings.get().getBubbleRestingPoint()
        setTargetPos(bubbleRestingPoint.x, bubbleRestingPoint.y, bubblePeriod, DraggableHelper.AnimationType.SmallOvershoot, object : DraggableHelper.AnimationEventListener {
            override fun onAnimationComplete() {
                EventBus.post(mEndCollapseTransitionEvent)
                onAnimComplete()
            }

            override fun onCancel() {
                onAnimComplete()
            }
        })

        mainController!!.endAppPolling()
        mainController.collapseBubbleFlow((contentPeriod * 1000).toLong())

        mBeginCollapseTransitionEvent.mPeriod = contentPeriod
        EventBus.post(MainController.BeginCollapseTransitionEvent(fromCloseSystemDialogs))
    }

    private fun doAnimateToContentView() {
        doAnimateToContentView(true)
    }

    private fun doAnimateToContentView(saveBubbleRestingPoint: Boolean) {
        CrashTracking.log("doAnimateToContentView()")
        if (mAnimActive) {
            if (mMode == Mode.ContentView) {
                CrashTracking.log("doAnimateToContentView() mMode == Mode.ContentView, early exit")
                return
            } else {
                CrashTracking.log("doAnimateToContentView() cancelAnimation()")
                mDraggableHelper.cancelAnimation()
            }
        }

        if (mMode != Mode.ContentView && saveBubbleRestingPoint) {
            Settings.get().setBubbleRestingPoint(mDraggableHelper.getXPos(), mDraggableHelper.getYPos())
        }

        mTouchDown = false
        mMode = Mode.ContentView

        val bubblePeriod = Constant.BUBBLE_ANIM_TIME.toFloat() / 1000f
        val contentPeriod = bubblePeriod * 0.666667f      // 0.66667 is the normalized t value when f = 1.0f for overshoot interpolator of 0.5 tension

        val mainController = MainController.get()
        visibility = View.VISIBLE

        animate().alpha(1.0f).setDuration(Constant.BUBBLE_ANIM_TIME.toLong())
        mBadgeView.animate().alpha(1.0f).setDuration(Constant.BUBBLE_ANIM_TIME.toLong())

        val xp = Config.getContentViewX(0, 1).toInt()
        val yp = Config.mContentViewBubbleY

        setTargetPos(xp, yp, bubblePeriod, DraggableHelper.AnimationType.SmallOvershoot, object : DraggableHelper.AnimationEventListener {
            override fun onAnimationComplete() {
                onAnimComplete()
                val activeCount = mainController!!.activeTabCount
                if (activeCount == 0) {
                    // Ensure we don't enter state where there are no tabs to display. Fix #448
                    EventBus.post(MainController.EndCollapseTransitionEvent())
                    EventBus.post(ExpandedActivity.MinimizeExpandedActivityEvent())
                    CrashTracking.log("doAnimateToContentView(): onAnimationComplete(): getActiveTabCount()==0")
                } else {
                    CrashTracking.log("doAnimateToContentView(): onAnimationComplete(): getActiveTabCount():$activeCount")
                }
            }

            override fun onCancel() {
                onAnimComplete()
                mainController!!.endAppPolling()
                mainController.collapseBubbleFlow((contentPeriod * 1000).toLong())
            }
        })
        mainController!!.beginAppPolling()
        mainController.expandBubbleFlow((contentPeriod * 1000).toLong(), true)
        if (!Constant.ACTIVITY_WEBVIEW_RENDERING) {
            // Launch ExpandedActivity (an invisible, focusable placeholder that lets the overlay's
            // WebView receive input/keyboard/back-press) now, in parallel with the bubble-flight
            // animation, instead of waiting for it to finish. ExpandedActivity draws nothing itself,
            // so overlapping its real Activity-launch latency with the animation the user is already
            // watching removes what used to be a serial, post-animation stall before the expanded
            // bubble became interactive.
            mainController.showExpandedActivity()
        }
    }

    fun configure(x0: Int, y0: Int, targetX: Int, targetY: Int, targetTime: Int, cv: CanvasView) {

        try {
            super.configure("http://blerg.com") // the URL is not actually used...
        } catch (e: MalformedURLException) {
            e.printStackTrace()
        }

        //setBackgroundColor(0xff00ff00);

        mMode = Mode.BubbleView
        mAnimActive = false
        mHasMoved = false
        mCanvasView = cv
        mBadgeView = findViewById(R.id.badge_view)
        mBadgeView.hide()
        mBadgeView.visibility = View.GONE
        mCircle = Circle(0f, 0f, 1f)

        val bubbleSize = resources.getDimensionPixelSize(R.dimen.bubble_size)

        val windowManagerParams = WindowManager.LayoutParams()
        windowManagerParams.gravity = Gravity.TOP or Gravity.LEFT
        windowManagerParams.x = x0
        windowManagerParams.y = y0
        windowManagerParams.height = bubbleSize
        windowManagerParams.width = bubbleSize
        windowManagerParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        windowManagerParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        windowManagerParams.format = PixelFormat.TRANSPARENT
        windowManagerParams.setTitle("Peek: BubbleDraggable")

        mDraggableHelper = DraggableHelper(this, windowManagerParams, true, object : DraggableHelper.OnTouchActionEventListener {

            override fun onActionDown(e: DraggableHelper.TouchEvent) {
                if (!mAnimActive) {
                    mCurrentSnapTarget = null
                    mHasMoved = false
                    mTouchDown = true
                    mTouchInitialX = e.posX
                    mTouchInitialY = e.posY

                    animate().alpha(1.0f).setDuration(Constant.BUBBLE_ANIM_TIME.toLong())
                    mBadgeView.animate().alpha(1.0f).setDuration(Constant.BUBBLE_ANIM_TIME.toLong())

                    val mainController = MainController.get()
                    if (mainController != null) {
                        mainController.scheduleUpdate()
                    }

                    EventBus.post(mBeginBubbleDragEvent)
                    CrashTracking.log("BubbleDraggable.configure(): onActionDown() - start drag")
                }
            }

            override fun onActionMove(e: DraggableHelper.MoveEvent) {
                if (mTouchDown) {
                    var targetX = (e.rawX - Config.mBubbleWidth * 0.5f).toInt()
                    var targetY = (e.rawY - Config.mBubbleHeight).toInt()

                    targetX = Util.clamp(Config.mBubbleSnapLeftX, targetX, Config.mBubbleSnapRightX)
                    targetY = Util.clamp(Config.mBubbleMinY, targetY, Config.mBubbleMaxY)

                    val d = Math.sqrt((e.dx * e.dx + e.dy * e.dy).toDouble()).toFloat()
                    if (d >= Config.dpToPx(10.0f)) {
                        mHasMoved = true
                    }

                    if (!mAnimActive) {
                        // c = null, t = null           wasn't snapping, no snap -> move
                        // c = 1, t = null              was snapping, no snap -> anim out
                        // c = null, t = 1              wasn't snapping, is snap -> anim in
                        // c = 1, t = 1                 was snapping, is snapping -> NO move

                        mCircle.Update(targetX + Config.mBubbleWidth * 0.5f, targetY + Config.mBubbleHeight * 0.5f, Config.mBubbleWidth * 0.5f)
                        val tv = mCanvasView!!.getSnapTarget(mCircle, 1.0f)

                        if (mCurrentSnapTarget == null) {
                            if (tv == null) {
                                setTargetPos(targetX, targetY, 0.0f, DraggableHelper.AnimationType.DistanceProportion, null)
                            } else {
                                tv.beginSnapping()
                                mCurrentSnapTarget = tv
                                mTimeOnSnapTarget = 0.0f

                                val dc = tv.GetDefaultCircle()
                                val xt = (0.5f + dc.mX - Config.mBubbleWidth * 0.5f).toInt()
                                val yt = (0.5f + dc.mY - Config.mBubbleHeight * 0.5f).toInt()
                                setTargetPos(xt, yt, Config.ANIMATE_TO_SNAP_TIME, DraggableHelper.AnimationType.Linear, object : DraggableHelper.AnimationEventListener {
                                    override fun onAnimationComplete() {
                                        onAnimComplete()
                                    }

                                    override fun onCancel() {
                                        onAnimComplete()
                                    }
                                })
                            }
                        } else {
                            if (tv == null) {
                                setTargetPos(targetX, targetY, 0.05f, DraggableHelper.AnimationType.Linear, object : DraggableHelper.AnimationEventListener {
                                    override fun onAnimationComplete() {
                                        mCurrentSnapTarget!!.endSnapping()
                                        mCurrentSnapTarget!!.endLongHovering()
                                        mCurrentSnapTarget = null
                                        mTimeOnSnapTarget = 0f
                                        onAnimComplete()
                                    }

                                    override fun onCancel() {
                                        onAnimComplete()
                                    }
                                })
                            }
                        }
                    }
                }
            }

            override fun onActionUp(e: DraggableHelper.ReleaseEvent) {
                if (mTouchDown) {
                    CrashTracking.log("BubbleDraggable.configure(): onActionUp() - end drag")
                    mDraggableHelper.cancelAnimation()

                    if (mHasMoved) {
                        if (mCurrentSnapTarget == null) {
                            val v = Math.sqrt((e.vx * e.vx + e.vy * e.vy).toDouble()).toFloat()
                            val threshold = Config.dpToPx(900.0f)
                            if (v > threshold) {
                                doFlick(e.vx, e.vy)
                                CrashTracking.log("BubbleDraggable.configure(): onActionUp() - doFlick()")
                            } else {
                                EventBus.post(mEndBubbleDragEvent)

                                val doBubbleView = mMode == Mode.BubbleView ||
                                        e.posX < Config.mScreenWidth * 0.2f ||
                                        e.posX > Config.mScreenWidth * 0.8f ||
                                        e.posY > Config.mScreenHeight * 0.5f

                                if (doBubbleView) {
                                    mMode = Mode.BubbleView
                                    CrashTracking.log("BubbleDraggable.configure(): onActionUp() - doSnap()")
                                    doSnap()
                                } else {
                                    CrashTracking.log("BubbleDraggable.configure(): onActionUp() - doAnimateToContentView() [mHasMoved==true]")
                                    doAnimateToContentView()
                                }
                            }
                        } else {
                            EventBus.post(mEndBubbleDragEvent)
                            CrashTracking.log("BubbleDraggable.configure(): onActionUp() - doSnapAction()")
                            doSnapAction(mCurrentSnapTarget!!.getAction())
                        }
                    } else {
                        EventBus.post(mEndBubbleDragEvent)

                        if (mMode == Mode.BubbleView) {
                            CrashTracking.log("BubbleDraggable.configure(): onActionUp() - doAnimateToContentView() [mMode == Mode.BubbleView]")
                            doAnimateToContentView()
                        } else {
                            if (mMode == Mode.ContentView && mBubbleFlowDraggable!!.isExpanded() == false) {
                                CrashTracking.log("BubbleDraggable.configure(): onActionUp() - doAnimateToContentView() [mMode == Mode.ContentView]")
                                doAnimateToContentView()
                            } else {
                                CrashTracking.log("BubbleDraggable.configure(): onActionUp() - doAnimateToBubbleView()")
                                doAnimateToBubbleView(0, false)
                            }
                        }
                    }

                    mTouchDown = false
                }
            }
        })

        if (mDraggableHelper.isAlive()) {
            MainController.addRootWindow(this, windowManagerParams)

            slideOnScreen(x0, y0, targetX, targetY, targetTime)
        }
    }

    fun slideOnScreen(x0: Int, y0: Int, targetX: Int, targetY: Int, targetTime: Int) {
        setExactPos(x0, y0)
        if (targetX != x0 || targetY != y0) {
            setTargetPos(targetX, targetY, targetTime.toFloat() / 1000f, DraggableHelper.AnimationType.LargeOvershoot, null)
        }
        CrashTracking.log("BubbleDraggable.slideOnScreen()")
    }

    fun setBubbleFlowDraggable(bubbleFlowDraggable: BubbleFlowDraggable) {
        mBubbleFlowDraggable = bubbleFlowDraggable
    }

    fun destroy() {
        //setOnTouchListener(null);
        setOnUpdateListener(null) // prevent memory leak
        mDraggableHelper.destroy()
    }

    fun setOnUpdateListener(onUpdateListener: Draggable.OnUpdateListener?) {
        mOnUpdateListener = onUpdateListener
    }

    override val draggableHelper: DraggableHelper
        get() = mDraggableHelper

    override fun update(dt: Float) {
        if (mTouchDown) {
            if (mCurrentSnapTarget != null) {
                mTimeOnSnapTarget += dt
                val snapTime = mTimeOnSnapTarget - Config.ANIMATE_TO_SNAP_TIME
                if (mCurrentSnapTarget!!.isLongHovering() == false && snapTime >= Config.CLOSE_ALL_BUBBLES_DELAY) {
                    mCurrentSnapTarget!!.beginLongHovering()
                }
                if (!mAnimActive) {
                    val dc = mCurrentSnapTarget!!.GetDefaultCircle()
                    val xt = (0.5f + dc.mX - Config.mBubbleWidth * 0.5f).toInt()
                    val yt = (0.5f + dc.mY - Config.mBubbleHeight * 0.5f).toInt()
                    mDraggableHelper.setTargetPos(xt, yt, 0.02f, DraggableHelper.AnimationType.Linear, null)
                }
            }
            MainController.get()!!.scheduleUpdate()
        }

        mDraggableHelper.update(dt)

        val x = mDraggableHelper.getXPos()
        val y = mDraggableHelper.getYPos()

        mDraggableBubbleMovedEvent.mX = x
        mDraggableBubbleMovedEvent.mY = y

        if (mOnUpdateListener != null) {
            mOnUpdateListener!!.onUpdate(this@BubbleDraggable, 0f)
        }
    }

    override fun onOrientationChanged() {
        if (mMode == Mode.BubbleView) {
            doAnimateToBubbleView(1, false)
        } else {
            switchToExpandedView()
        }
    }

    fun setExactPos(x: Int, y: Int) {
        mDraggableHelper.setExactPos(x, y)
    }

    fun setTargetPos(xp: Int, yp: Int, t: Float, type: DraggableHelper.AnimationType, listener: DraggableHelper.AnimationEventListener?) {
        try {
            Util.Assert(!mAnimActive, "mAnimActive:$mAnimActive")
        } catch (e: AssertionError) {
            e.printStackTrace()
        }
        //Util.Assert(t > 0.0f, "t:" + t);      // Don't think this happens anymore - just to catch if it does happen and investigate why.
        mAnimActive = listener != null
        mDraggableHelper.setTargetPos(xp, yp, t, type, listener)
    }

    companion object {
        private const val TAG = "BubbleDraggable"
    }
}
