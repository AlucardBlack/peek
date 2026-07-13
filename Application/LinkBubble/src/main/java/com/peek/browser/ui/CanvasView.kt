/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.animation.Animator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import com.peek.browser.Config
import com.peek.browser.Constant
import com.peek.browser.MainController
import com.peek.browser.R
import com.peek.browser.Settings
import com.peek.browser.physics.Circle
import com.peek.browser.util.EventBus
import com.peek.browser.util.Util
import com.peek.browser.webrender.WebRenderer
import java.util.Vector

class CanvasView(context: Context) : FrameLayout(context) {

    private val mWindowManagerParams = WindowManager.LayoutParams()

    private val mTargets: Vector<BubbleTargetView> = Vector()
    private var mTopMaskView: ImageView? = null
    private var mBottomMaskView: ImageView? = null

    var mExpanded: Boolean = false
    var mDragging: Boolean = false
    var mTargetAlpha: Int = 0
    var mContentViewTargetAlpha: Int = 0

    private var mContentView: ContentView? = null

    private var mTargetOffsetDebugPaint: Paint? = null
    private var mTargetTractorDebugPaint: Paint? = null
    private var mTargetDebugRect: Rect? = null

    private val mClipResult = Util.ClipResult()
    private val mClosestPoint = Util.Point()
    private val mTractorRegion = Rect()

    private var mStatusBarCoverView: ImageView? = null

    private val mMinimizeExpandedActivityEvent = ExpandedActivity.MinimizeExpandedActivityEvent()

    init {
        EventBus.subscribe(this, MainController.CurrentTabResumeEvent::class.java, ::onCurrentTabResume)
        EventBus.subscribe(this, MainController.CurrentTabPauseEvent::class.java, ::onCurrentTabPause)
        EventBus.subscribe(this, MainController.CurrentTabChangedEvent::class.java, ::onCurrentTabChanged)
        EventBus.subscribe(this, MainController.BeginBubbleDragEvent::class.java, ::onBeginBubbleDrag)
        EventBus.subscribe(this, MainController.EndBubbleDragEvent::class.java, ::onEndBubbleDragEvent)
        EventBus.subscribe(this, MainController.BeginCollapseTransitionEvent::class.java, ::onBeginCollapseTransition)
        EventBus.subscribe(this, MainController.BeginExpandTransitionEvent::class.java, ::onBeginExpandTransition)
        EventBus.subscribe(this, MainController.EndCollapseTransitionEvent::class.java, ::onEndCollapseTransition)
        EventBus.subscribe(this, MainController.OrientationChangedEvent::class.java, ::onOrientationChanged)
        EventBus.subscribe(this, MainController.BeginAnimateFinalTabAwayEvent::class.java, ::onBeginAnimateFinalTabAway)
        EventBus.subscribe(this, MainController.HideContentEvent::class.java, ::onHideContentEvent)
        EventBus.subscribe(this, Settings.OnConsumeBubblesChangedEvent::class.java, ::onConsumeBubblesChanged)

        val canvasMaskHeight = resources.getDimensionPixelSize(R.dimen.canvas_mask_height)

        if (Constant.COVER_STATUS_BAR) {
            val statusBarHeight = Util.getSystemStatusBarHeight(context)!!

            mStatusBarCoverView = ImageView(context)
            mStatusBarCoverView!!.setImageResource(R.drawable.masked_status_bar)
            mStatusBarCoverView!!.scaleType = ImageView.ScaleType.FIT_XY
            val lp = WindowManager.LayoutParams()
            lp.gravity = Gravity.TOP or Gravity.LEFT
            lp.x = 0
            lp.y = -statusBarHeight
            lp.height = statusBarHeight
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            lp.flags = WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            lp.format = PixelFormat.TRANSPARENT

            mStatusBarCoverView!!.layoutParams = lp

            MainController.addRootWindow(mStatusBarCoverView!!, lp)
        }

        val resources = resources
        val inflater = LayoutInflater.from(context)

        if (Constant.TOP_CANVAS_MASK) {
            mTopMaskView = ImageView(context)
            mTopMaskView!!.setImageResource(R.drawable.masked_background_half)
            mTopMaskView!!.scaleType = ImageView.ScaleType.FIT_XY
            val topMaskLP = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, canvasMaskHeight)
            topMaskLP.gravity = Gravity.TOP
            mTopMaskView!!.layoutParams = topMaskLP
            addView(mTopMaskView)
        }

        if (Constant.BOTTOM_CANVAS_MASK) {
            mBottomMaskView = ImageView(context)
            mBottomMaskView!!.setImageResource(R.drawable.masked_background_half)
            mBottomMaskView!!.scaleType = ImageView.ScaleType.FIT_XY
            mBottomMaskView!!.rotation = 180f
            val bottomMaskLP = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, canvasMaskHeight)
            bottomMaskLP.gravity = Gravity.BOTTOM
            mBottomMaskView!!.layoutParams = bottomMaskLP
            addView(mBottomMaskView)
        }

        val closeBubbleTargetY = resources.getDimensionPixelSize(R.dimen.close_bubble_target_y)
        val closeTabTargetView = inflater.inflate(R.layout.view_close_tab_target, null) as CloseTabTargetView
        closeTabTargetView.configure(this, context, null, Constant.BubbleAction.Close,
                0, BubbleTargetView.HorizontalAnchor.Center,
                closeBubbleTargetY, BubbleTargetView.VerticalAnchor.Bottom,
                resources.getDimensionPixelSize(R.dimen.close_bubble_target_x_offset), closeBubbleTargetY,
                resources.getDimensionPixelSize(R.dimen.close_bubble_target_tractor_offset_x), closeBubbleTargetY)
        mTargets.add(closeTabTargetView)

        val consumeTargetY = resources.getDimensionPixelSize(R.dimen.bubble_target_y)
        val consumeDefaultX = resources.getDimensionPixelSize(R.dimen.consume_bubble_target_default_x)
        val consumeXOffset = resources.getDimensionPixelSize(R.dimen.consume_bubble_target_x_offset)
        val consumeTractorBeamX = resources.getDimensionPixelSize(R.dimen.consume_bubble_target_tractor_beam_x)

        val leftConsumeTarget = inflater.inflate(R.layout.view_consume_bubble_target, null) as BubbleTargetView
        val leftConsumeDrawable = Settings.get().getConsumeBubbleIcon(Constant.BubbleAction.ConsumeLeft)
        leftConsumeTarget.configure(this, context, leftConsumeDrawable, Constant.BubbleAction.ConsumeLeft,
                consumeDefaultX, BubbleTargetView.HorizontalAnchor.Left,
                consumeTargetY, BubbleTargetView.VerticalAnchor.Top,
                consumeXOffset, consumeTargetY, consumeTractorBeamX, consumeTargetY)
        mTargets.add(leftConsumeTarget)

        val rightConsumeTarget = inflater.inflate(R.layout.view_consume_bubble_target, null) as BubbleTargetView
        val rightConsumeDrawable = Settings.get().getConsumeBubbleIcon(Constant.BubbleAction.ConsumeRight)
        rightConsumeTarget.configure(this, context, rightConsumeDrawable, Constant.BubbleAction.ConsumeRight,
                consumeDefaultX, BubbleTargetView.HorizontalAnchor.Right,
                consumeTargetY, BubbleTargetView.VerticalAnchor.Top,
                consumeXOffset, consumeTargetY, consumeTractorBeamX, consumeTargetY)
        mTargets.add(rightConsumeTarget)

        visibility = GONE

        mWindowManagerParams.gravity = Gravity.TOP or Gravity.LEFT
        mWindowManagerParams.x = 0
        mWindowManagerParams.y = 0
        mWindowManagerParams.height = WindowManager.LayoutParams.MATCH_PARENT
        mWindowManagerParams.width = WindowManager.LayoutParams.MATCH_PARENT
        mWindowManagerParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        mWindowManagerParams.flags = WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        mWindowManagerParams.format = PixelFormat.TRANSPARENT
        mWindowManagerParams.setTitle("Peek: CanvasView")
        MainController.addRootWindow(this, mWindowManagerParams)

        if (Constant.DEBUG_SHOW_TARGET_REGIONS) {
            mTargetDebugRect = Rect()

            mTargetOffsetDebugPaint = Paint()
            mTargetOffsetDebugPaint!!.color = 0x80800000.toInt()

            mTargetTractorDebugPaint = Paint()
            mTargetTractorDebugPaint!!.color = 0x80008000.toInt()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)

        if (Constant.DEBUG_SHOW_TARGET_REGIONS) {
            for (bubbleTargetView in mTargets) {
                bubbleTargetView.getTractorDebugRegion(mTargetDebugRect!!)
                canvas.drawRect(mTargetDebugRect!!, mTargetTractorDebugPaint!!)

                bubbleTargetView.getOffsetDebugRegion(mTargetDebugRect!!)
                canvas.drawRect(mTargetDebugRect!!, mTargetOffsetDebugPaint!!)
            }
        }
    }

    private fun applyAlpha(targetAlpha: Int) {
        mTargetAlpha = targetAlpha
        visibility = VISIBLE
        if (mStatusBarCoverView != null) {
            mStatusBarCoverView!!.visibility = VISIBLE
            mStatusBarCoverView!!.animate().alpha(targetAlpha.toFloat()).setDuration(Constant.CANVAS_FADE_ANIM_TIME.toLong()).setListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (mStatusBarCoverView != null) {
                        if (targetAlpha == 0) {
                            mStatusBarCoverView!!.visibility = GONE
                            mStatusBarCoverView!!.alpha = 0f
                        }
                    }
                }

                override fun onAnimationCancel(animation: Animator) {
                }

                override fun onAnimationRepeat(animation: Animator) {
                }
            })
        }

        if (mTopMaskView != null) {
            mTopMaskView!!.visibility = VISIBLE
            mTopMaskView!!.animate().alpha(targetAlpha.toFloat()).setDuration(Constant.CANVAS_FADE_ANIM_TIME.toLong()).setListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (mTopMaskView != null) {
                        if (targetAlpha == 0) {
                            mTopMaskView!!.visibility = GONE
                            mTopMaskView!!.alpha = 0f
                        }
                    }
                }

                override fun onAnimationCancel(animation: Animator) {
                }

                override fun onAnimationRepeat(animation: Animator) {
                }
            })
        }
        if (mBottomMaskView != null) {
            //mBottomMaskView.setVisibility(VISIBLE);
            ///mBottomMaskView.setAlpha(mCurrentAlpha);
            mBottomMaskView!!.animate().alpha(targetAlpha.toFloat()).setDuration(Constant.CANVAS_FADE_ANIM_TIME.toLong()).setListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (mBottomMaskView != null) {
                        if (targetAlpha == 0) {
                            mBottomMaskView!!.visibility = GONE
                            mBottomMaskView!!.alpha = 0f
                        }
                    }
                }

                override fun onAnimationCancel(animation: Animator) {
                }

                override fun onAnimationRepeat(animation: Animator) {
                }
            })
        }
        applyContentViewAlpha(mTargetAlpha)
    }

    private fun applyContentViewAlpha(targetAlpha: Int) {
        mContentViewTargetAlpha = targetAlpha
        if (mContentView != null) {
            mContentView!!.animate().cancel()
            mContentView!!.visibility = VISIBLE
            visibility = VISIBLE
            if (mContentViewTargetAlpha != 0 && !mExpanded) {
                mContentView!!.alpha = 0f
            }

            mContentView!!.animate().alpha(targetAlpha.toFloat()).setDuration(Constant.CANVAS_FADE_ANIM_TIME.toLong()).setListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (mContentView != null) {
                        if (mContentViewTargetAlpha == 0) {
                            mContentView!!.alpha = 0f
                            mContentView!!.visibility = GONE
                            if (!mExpanded) {
                                removeView(mContentView)
                            }
                        } else {
                            mContentView!!.alpha = 1f
                            mContentView!!.visibility = VISIBLE
                        }
                    }
                    // If we also have target alpha 0 then hide the view so clicks behind will work.
                    if (mTargetAlpha == 0 && !mDragging) {
                        visibility = GONE
                    }
                }

                override fun onAnimationCancel(animation: Animator) {
                }

                override fun onAnimationRepeat(animation: Animator) {
                }
            })

            if (targetAlpha == 0) {
                try {
                    clearFocus()
                } catch (e: Exception) {
                    Log.d(TAG, "handled exception while clearing focus")
                }
            }
        }
    }

    private fun setContentView(bubble: TabView?, unhideNotification: Boolean) {
        if (mContentView != null) {

            // The webview can throw an exception when trying to remove focus inside of removeView.
            // To prevent a crash we try to manually unfocus first, within a try/catch to reset ViewGroup::mFocused.
            try {
                mContentView!!.clearFocus()
            } catch (e: Exception) {
                Log.d(TAG, "handled exception while clearing focus")
            }

            removeView(mContentView)
            if (mExpanded) {
                applyContentViewAlpha(1)
            } else {
                mContentView!!.alpha = 0f
                mContentView!!.visibility = GONE
            }
            mContentView!!.onCurrentContentViewChanged(false)
        }

        val contentView = if (bubble != null) bubble.getContentView() else null
        //if (bubble != null) {
        //    int bubbleIndex = MainController.get().getTabIndex(bubble);
        //    Log.d("CanvasView", "setContentView() - index:" + bubbleIndex);
        //}

        //Log.d("blerg", "setContentView(): from " + (mContentView != null ? "valid" : "none") + " to " + (contentView != null ? "valid" : "none"));
        mContentView = contentView
        if (unhideNotification) {
            return
        }
        if (mContentView != null) {
            val p = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            p.topMargin = Config.mContentOffset
            addView(mContentView, p)
            mContentView!!.onCurrentContentViewChanged(true)
            mContentView!!.requestFocus()
        }
    }

    private fun showContentView() {
        applyContentViewAlpha(1)
    }

    private fun hideContentView() {
        applyContentViewAlpha(0)
    }

    fun onCurrentTabResume(e: MainController.CurrentTabResumeEvent) {
        if (null == e.mTab) {
            return
        }
        val contentView = e.mTab!!.getContentView()
        if (null == contentView) {
            return
        }
        val webRenderer = contentView.getWebRenderer()
        if (null == webRenderer) {
            return
        }
        webRenderer.resumeOnSetActive()
    }

    fun onCurrentTabPause(e: MainController.CurrentTabPauseEvent) {
        if (null == e.mTab) {
            return
        }
        val contentView = e.mTab!!.getContentView()
        if (null == contentView) {
            return
        }
        val webRenderer = contentView.getWebRenderer()
        if (null == webRenderer) {
            return
        }
        webRenderer.pauseOnSetInactive()
    }

    fun onCurrentTabChanged(e: MainController.CurrentTabChangedEvent) {
        setContentView(e.mTab, e.mUnhideNotification)
    }

    fun onBeginBubbleDrag(e: MainController.BeginBubbleDragEvent) {
        mDragging = true
        if (mExpanded) {
            fadeOut()
        } else {
            visibility = VISIBLE
            fadeIn()
            if (mBottomMaskView != null) {
                mBottomMaskView!!.visibility = VISIBLE
            }
            hideContentView()
            MainController.get()!!.showBadge(false)
            if (mContentView != null) {
                mContentView!!.onBeginBubbleDrag()
            }
        }
    }

    fun onEndBubbleDragEvent(e: MainController.EndBubbleDragEvent) {
        mDragging = false
        mExpanded = false
        fadeOut()
        removeView(mContentView)
        visibility = GONE
        MainController.get()!!.showBadge(true)
        EventBus.post(mMinimizeExpandedActivityEvent)
    }

    fun onBeginCollapseTransition(e: MainController.BeginCollapseTransitionEvent) {
        if (!mExpanded) {
            return
        }
        mExpanded = false
        if (mContentView != null) {
            mContentView!!.onAnimateOffscreen()
            fadeOut()
        }

        // TODO: replace 24 with Android N version once we update an SDK
        if (Build.VERSION.SDK_INT < 24 || !e.mFromCloseSystemDialogs) {
            EventBus.post(mMinimizeExpandedActivityEvent)
        }
    }

    fun onBeginExpandTransition(e: MainController.BeginExpandTransitionEvent) {
        mExpanded = true
        fadeIn()

        if (mContentView != null) {
            if (null == mContentView!!.parent) {
                addView(mContentView)
            }
            mContentView!!.onAnimateOnScreen()
            showContentView()
        }
    }

    fun onEndCollapseTransition(e: MainController.EndCollapseTransitionEvent) {
        if (mExpanded) {
            fadeOut()
        }
    }

    fun onOrientationChanged(e: MainController.OrientationChangedEvent) {
        for (i in mTargets.indices) {
            val bt = mTargets[i]
            bt.OnOrientationChanged()
        }
        if (mContentView != null) {
            mContentView!!.onOrientationChanged()
        }
    }

    fun onBeginAnimateFinalTabAway(event: MainController.BeginAnimateFinalTabAwayEvent) {
        fadeOut()
        hideContentView()
        setContentView(event.mTab, false)
        val collapseTransitionEvent = MainController.BeginCollapseTransitionEvent()
        collapseTransitionEvent.mPeriod = (Constant.BUBBLE_ANIM_TIME / 1000f) * 0.666667f
        onBeginCollapseTransition(collapseTransitionEvent)
    }

    fun onHideContentEvent(event: MainController.HideContentEvent) {
        mExpanded = false
        setContentView(null, false)
    }

    fun onConsumeBubblesChanged(event: Settings.OnConsumeBubblesChangedEvent) {
        for (i in mTargets.indices) {
            mTargets[i].onConsumeBubblesChanged()
        }
    }

    private fun fadeIn() {
        applyAlpha(1)
    }

    private fun fadeOut() {
        applyAlpha(0)
    }

    fun destroy() {
        for (bt in mTargets) {
            bt.destroy()
        }

        EventBus.unsubscribeAll(this)

        MainController.removeRootWindow(this)
        if (mStatusBarCoverView != null) {
            MainController.removeRootWindow(mStatusBarCoverView!!)
        }

        // Note: sometimes this element leaks. Seems to be result of this: http://goo.gl/Ite5F9
    }

    fun update(dt: Float) {
        for (i in mTargets.indices) {
            mTargets[i].update(dt)
        }
    }

    fun getSnapTarget(x0: Float, y0: Float, x1: Float, y1: Float, p: Util.Point): BubbleTargetView? {

        var closestTargetView: BubbleTargetView? = null
        var closestDistance = 9e9f

        for (tv in mTargets) {

            tv.getTractorDebugRegion(mTractorRegion)
            val targetCircle = tv.GetDefaultCircle()

            if (Util.clipLineSegmentToRectangle(x0, y0, x1, y1, mTractorRegion.left.toFloat(), mTractorRegion.top.toFloat(), mTractorRegion.right.toFloat(), mTractorRegion.bottom.toFloat(), mClipResult)) {
                Util.closestPointToLineSegment(mClipResult.x0.toFloat(), mClipResult.y0.toFloat(), mClipResult.x1.toFloat(), mClipResult.y1.toFloat(), targetCircle.mX, targetCircle.mY, mClosestPoint)

                val d = Util.distance(x0, y0, mClosestPoint.x.toFloat(), mClosestPoint.y.toFloat())
                if (d < closestDistance) {
                    p.x = mClosestPoint.x
                    p.y = mClosestPoint.y
                    closestTargetView = tv
                }
            }
        }

        return closestTargetView
    }

    fun getSnapTarget(bubbleCircle: Circle, radiusScaler: Float): BubbleTargetView? {

        for (i in mTargets.indices) {
            val bt = mTargets[i]

            if (bt.shouldSnap(bubbleCircle, radiusScaler)) {
                return bt
            }
        }

        return null
    }

    companion object {
        private const val TAG = "CanvasView"
    }
}
