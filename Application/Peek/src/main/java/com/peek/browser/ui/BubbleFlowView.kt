/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import com.peek.browser.Config
import com.peek.browser.Constant
import com.peek.browser.MainController
import com.peek.browser.util.CrashTracking
import com.peek.browser.util.Util
import com.peek.browser.util.VerticalGestureListener
import java.util.ArrayList

open class BubbleFlowView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : HorizontalScrollView(context, attrs, defStyle) {

    interface Listener {
        fun onCenterItemClicked(sender: BubbleFlowView, view: View)
        fun onCenterItemLongClicked(sender: BubbleFlowView, view: View)
        fun onCenterItemSwiped(gestureDirection: VerticalGestureListener.GestureDirection)
        // Note: only called when scrolling has finished
        fun onCenterItemChanged(sender: BubbleFlowView, view: View)
    }

    interface AnimationEventListener {
        fun onAnimationEnd(sender: BubbleFlowView)
    }

    interface TouchInterceptor {
        fun onTouchActionDown(event: MotionEvent): Boolean
        fun onTouchActionMove(event: MotionEvent): Boolean
        fun onTouchActionUp(event: MotionEvent): Boolean
    }

    private var mDoingCollapse: Boolean = false
    protected val mViews: MutableList<View> = ArrayList()
    protected val mContent: FrameLayout = FrameLayout(context)
    private var mIsExpanded: Boolean = false
    private var mWidth: Int = 0
    protected var mItemWidth: Int = 0
    protected var mItemHeight: Int = 0
    private var mFullScaleX: Float = 0f
    private var mMinScaleX: Float = 0f
    private var mEdgeMargin: Int = 0
    private var mIndexOnActionDown: Int = 0
    private var mFlingCalled: Boolean = false
    protected var mSlideOffAnimationPlaying: Boolean = false

    private var mBubbleFlowListener: Listener? = null
    private var mTouchInterceptor: TouchInterceptor? = null
    private var mActiveTouchPointerId = INVALID_POINTER
    private var mInterceptingTouch = false
    private var mLastMotionY: Int = 0

    private val mVerticalGestureDetector: GestureDetector
    private val mVerticalGestureListener = VerticalGestureListener()
    private var mLastVerticalGestureTime: Long = 0

    private var mStillTouchFrameCount: Int = 0
    private var mCenterViewTouchPointerId = INVALID_POINTER
    private var mCenterViewDownX: Float = 0f
    private var mCenterViewDownY: Float = 0f
    private var mTouchView: View? = null
    private var mLongPress: Boolean = false

    private val mOnTouchListener = OnTouchListener { v, ev ->
        val action = ev.action

        val maskedAction = action and MotionEvent.ACTION_MASK
        when (maskedAction) {
            MotionEvent.ACTION_DOWN -> {
                if (mTouchInterceptor != null && mTouchInterceptor!!.onTouchActionDown(ev)) {
                    return@OnTouchListener true
                }

                mActiveTouchPointerId = ev.getPointerId(0)
                mLastMotionY = ev.x.toInt()
                mIndexOnActionDown = getCenterIndex()
            }

            MotionEvent.ACTION_MOVE -> {
                if (mTouchInterceptor != null && mTouchInterceptor!!.onTouchActionMove(ev)) {
                    return@OnTouchListener true
                }

                // Sometimes ACTION_DOWN is not called, so ensure mIndexOnActionDown is set
                if (mIndexOnActionDown == -1) {
                    mActiveTouchPointerId = ev.getPointerId(0)
                    mIndexOnActionDown = getCenterIndex()
                }

                val activePointerIndex = ev.findPointerIndex(mActiveTouchPointerId)
                if (activePointerIndex == -1) {
                    Log.e(TAG, "Invalid pointerId=$mActiveTouchPointerId in onTouchEvent")
                    return@OnTouchListener false
                }

                mLastMotionY = ev.getY(activePointerIndex).toInt()
            }

            MotionEvent.ACTION_UP -> {
                if (mTouchInterceptor != null && mTouchInterceptor!!.onTouchActionUp(ev)) {
                    return@OnTouchListener true
                }

                mFlingCalled = false
                this@BubbleFlowView.onTouchEvent(ev)
                if (mFlingCalled == false) {
                    setCenterIndex(getCenterIndex())
                    if (DEBUG) {
                        Log.d(TAG, "No fling - back to middle!")
                    }
                }
                mIndexOnActionDown = -1
                mActiveTouchPointerId = INVALID_POINTER
                return@OnTouchListener true
            }

            MotionEvent.ACTION_CANCEL -> {
                mActiveTouchPointerId = INVALID_POINTER
            }
        }

        false
    }

    init {
        mContent.layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT)

        addView(mContent)

        mIsExpanded = true

        setOnTouchListener(mOnTouchListener)

        mVerticalGestureDetector = GestureDetector(mVerticalGestureListener)
    }

    fun setBubbleFlowViewListener(listener: Listener) {
        mBubbleFlowListener = listener
    }

    fun update(): Boolean {
        var result = false

        if (mSlideOffAnimationPlaying) {
            result = true
        }

        if (mTouchView != null) {
            if (mStillTouchFrameCount > -1) {
                ++mStillTouchFrameCount
                if (DEBUG) {
                    Log.d(TAG, "[longpress] update(): mStillTouchFrameCount:$mStillTouchFrameCount")
                }

                if (mStillTouchFrameCount == LONG_PRESS_FRAMES) {
                    if (mBubbleFlowListener != null) {
                        mLongPress = true
                        mBubbleFlowListener!!.onCenterItemLongClicked(this@BubbleFlowView, mTouchView!!)
                    }
                }

                // Check mContent rather than mViews, because it's possible for mViews to be empty yet mContent have a child
                // (e.g, in the instance the final Bubble is animating off screen).
                if (mContent.childCount > 0) {
                    result = true
                }
            }
            return result
        }

        return false
    }

    fun setTouchInterceptor(touchInterceptor: TouchInterceptor?) {
        mTouchInterceptor = touchInterceptor
        if (mTouchInterceptor == null) {
            mInterceptingTouch = false
        }
    }

    fun getTouchInterceptor(): TouchInterceptor? {
        return mTouchInterceptor
    }

    fun setInterceptingTouch(interceptingTouch: Boolean) {
        mInterceptingTouch = interceptingTouch
    }

    open fun configure(width: Int, itemWidth: Int, itemHeight: Int) {
        mWidth = width
        mItemWidth = itemWidth
        mItemHeight = itemHeight
        mEdgeMargin = (width - itemWidth) / 2

        mFullScaleX = mItemWidth * .3f
        mMinScaleX = mItemWidth * 1.2f
    }

    fun add(view: View, insertNextToCenterItem: Boolean) {

        //view.setBackgroundColor(mViews.size() % 2 == 0 ? 0xff660066 : 0xff666600);

        view.setOnClickListener(mViewOnClickListener)
        view.setOnTouchListener(mViewOnTouchListener)

        val centerIndex = getCenterIndex()
        val insertAtIndex = if (insertNextToCenterItem) centerIndex + 1 else mViews.size

        if (view.parent != null) {
            (view.parent as ViewGroup).removeView(view)
        }

        val lp = LayoutParams(mItemWidth, mItemHeight, Gravity.TOP or Gravity.LEFT)
        lp.leftMargin = mEdgeMargin + insertAtIndex * mItemWidth
        mContent.addView(view, lp)
        mContent.invalidate()

        if (insertNextToCenterItem) {
            mViews.add(centerIndex + 1, view)
        } else {
            mViews.add(view)
        }

        updatePositions()
        updateScales(scrollX)

        if (insertNextToCenterItem) {
            view.translationY = (-mItemHeight).toFloat()
            view.animate()
                    .translationY(0f)
                    .setDuration(Constant.BUBBLE_FLOW_ANIM_TIME.toLong())
                    .start()

            for (i in centerIndex + 2 until mViews.size) {
                val viewToShift = mViews[i]
                viewToShift.translationX = (-mItemWidth).toFloat()
                viewToShift.animate()
                        .translationX(0f)
                        .setDuration(Constant.BUBBLE_FLOW_ANIM_TIME.toLong())
                        .start()
            }
        }

        val contentLP = mContent.layoutParams
        contentLP.width = (mViews.size * mItemWidth) + mItemWidth + (2 * mEdgeMargin)
        mContent.layoutParams = contentLP
    }

    // Called when the item has actually been removed. Will be instantly when no animation occurs,
    // or if animating, at the completion of the animation.
    protected interface OnRemovedListener {
        fun onRemoved(view: View)
    }

    fun remove(index: Int, animateOff: Boolean, removeFromList: Boolean) {
        remove(index, animateOff, removeFromList, null)
    }

    protected open fun remove(index: Int, animateOff: Boolean, removeFromList: Boolean, onRemovedListener: OnRemovedListener?) {
        if (index < 0 || index >= mViews.size) {
            return
        }
        val view = mViews[index]

        if (animateOff) {
            if (removeFromList == false) {
                throw RuntimeException("removeFromList must be true if animating off")
            }

            // Populated below, but captured by reference into the withEndAction closure now -
            // by the time that closure actually runs (asynchronously, after the animation
            // completes), the list has already been filled in synchronously further down.
            val shiftAnimators = ArrayList<ValueAnimator>()

            invalidate()       // This fixes #284 - it's a hack, but it will do for now.
            view.animate()
                    .translationY((-mItemHeight).toFloat())
                    .setDuration(Constant.BUBBLE_FLOW_ANIM_TIME.toLong())
                    .withEndAction {
                        mContent.removeView(view)

                        // Cancel any still-in-flight animation on the views so the offset no
                        // longer applies. Property values persist after an animation ends
                        // (unlike the old fillAfter Animation, which was wiped by
                        // clearAnimation()), so this must be explicit.
                        for (shiftAnimator in shiftAnimators) {
                            shiftAnimator.cancel()
                        }
                        for (i in mViews.indices) {
                            val v = mViews[i]
                            v.animate().cancel()
                            v.translationX = 0f
                            v.translationY = 0f
                        }
                        updatePositions()
                        updateScales(scrollX)
                        mSlideOffAnimationPlaying = false

                        onRemovedListener?.onRemoved(view)
                    }
                    .start()
            mSlideOffAnimationPlaying = true

            mViews.remove(view)

            val viewsSize = mViews.size
            if (index < viewsSize) {
                for (i in index until viewsSize) {
                    shiftAnimators.add(animateShift(mViews[i], (-mItemWidth).toFloat()))
                }
            } else if (viewsSize > 0) {
                for (i in 0 until index) {
                    shiftAnimators.add(animateShift(mViews[i], mItemWidth.toFloat()))
                }
            }
        } else {
            mContent.removeView(view)
            if (removeFromList) {
                mViews.remove(view)
                updatePositions()
                updateScales(scrollX)
                mContent.invalidate()
            }
            if (onRemovedListener != null) {
                onRemovedListener.onRemoved(view)
            }
        }
    }

    // Shifts a view horizontally by dx, recalculating its scale every frame based on its live
    // (translated) x position - replaces the old TranslateAnimationEx's per-frame
    // onApplyTransform callback. The view's real layout position (LayoutParams.leftMargin)
    // doesn't change until updatePositions() runs at slide-off's withEndAction, so the scale
    // recalculation must be driven from the view's current visual x (view.x already includes
    // the live translationX set below, unlike the legacy matrix-transform approach where
    // view.x excluded the animation's own offset and had to have dx added back in manually).
    private fun animateShift(view: View, dx: Float): ValueAnimator {
        val animator = ValueAnimator.ofFloat(0f, dx)
        animator.duration = Constant.BUBBLE_FLOW_ANIM_TIME.toLong()
        animator.addUpdateListener {
            view.translationX = it.animatedValue as Float
            val centerX = scrollX + (mWidth / 2) - (mItemWidth / 2)
            updateScaleForView(view, centerX.toFloat(), view.x)
        }
        animator.start()
        return animator
    }

    fun updatePositions() {
        val size = mViews.size
        for (i in 0 until size) {
            val view = mViews[i]
            val lp = view.layoutParams as LayoutParams
            lp.leftMargin = mEdgeMargin + (i * mItemWidth)

            if (size - 1 == i) {
                lp.rightMargin = mEdgeMargin
            } else {
                lp.rightMargin = 0
            }
        }
    }

    fun updateScales(scrollX: Int) {
        val centerX = scrollX + (mWidth / 2) - (mItemWidth / 2)

        val size = mViews.size
        for (i in 0 until size) {
            updateScaleForView(mViews[i], centerX.toFloat(), ((i * mItemWidth) + mEdgeMargin).toFloat())
        }
    }

    fun updateScaleForView(view: View, centerX: Float, viewX: Float) {
        val xDelta = Math.abs(centerX - viewX)
        val targetScale: Float
        if (xDelta < mFullScaleX) {
            targetScale = 1f
        } else if (xDelta > mMinScaleX) {
            targetScale = MIN_SCALE
        } else {
            val ratio = 1f - ((xDelta - mFullScaleX) / (mMinScaleX - mFullScaleX))
            targetScale = MIN_SCALE + (ratio * (1f - MIN_SCALE))
        }
        // No explicit invalidate() needed: setScaleX/Y already schedule the (cheaper)
        // invalidateViewProperty path when the value actually changes. The old code also
        // compared against this container's own scaleX (always 1) rather than the child's,
        // so it force-invalidated nearly every scaled child on every scroll delta.
        view.scaleX = targetScale
        view.scaleY = targetScale
    }

    fun getItemCount(): Int {
        return mViews.size
    }

    fun getIndexOfView(view: View?): Int {
        return mViews.indexOf(view)
    }

    fun getCenterIndex(): Int {
        val centerX = (mWidth / 2) + scrollX
        var closestXAbsDelta = Integer.MAX_VALUE
        var closestIndex = -1
        for (i in mViews.indices) {
            val x = mEdgeMargin + (i * mItemWidth) + (mItemWidth / 2)
            val absDelta = Math.abs(x - centerX)
            if (absDelta < closestXAbsDelta) {
                closestXAbsDelta = absDelta
                closestIndex = i
            }
        }
        return closestIndex
    }

    fun setCenterIndex(index: Int) {
        setCenterIndex(index, true)
    }

    fun setCenterIndex(index: Int, animate: Boolean) {
        val scrollToX = mEdgeMargin + (index * mItemWidth) - (mWidth / 2) + (mItemWidth / 2)
        startScrollFinishedCheckTask(scrollToX)
        if (animate) {
            smoothScrollTo(scrollToX, 0)
        } else {
            scrollTo(scrollToX, 0)
        }
    }

    fun setCenterItem(view: View) {
        val index = mViews.indexOf(view)
        if (index > -1) {
            setCenterIndex(index)
        }
    }

    fun expand(): Boolean {
        // Note: if this function changes to not pass default arguments along, be sure to update BubbleFlowDraggable's expand() override(s) accordingly.
        return expand(DEFAULT_ANIM_TIME.toLong(), null)
    }

    open fun expand(time: Long, animationEventListener: AnimationEventListener?): Boolean {
        CrashTracking.log("BubbleFlowView.expand($time), mIsExpanded:$mIsExpanded")
        if (mIsExpanded) {
            return false
        }

        mDoingCollapse = false

        mStillTouchFrameCount = -1
        if (DEBUG) {
            //Log.d(TAG, "[longpress] expand(): mStillTouchFrameCount=" + mStillTouchFrameCount);
        }

        val size = mViews.size
        val centerIndex = getCenterIndex()
        if (centerIndex == -1) {    // fixes #343
            return false
        }
        val centerView = mViews[centerIndex]
        var addedAnimationListener = false
        for (i in 0 until size) {
            val view = mViews[i]
            if (centerView !== view) {
                val xOffset = (centerView.x - ((i * mItemWidth) + mEdgeMargin)).toInt()
                view.translationX = xOffset.toFloat()
                val animator = view.animate().translationX(0f).setDuration(time)
                if (addedAnimationListener == false) {
                    animator.withEndAction {
                        animationEventListener?.onAnimationEnd(this@BubbleFlowView)
                    }
                    addedAnimationListener = true
                }
                animator.start()
            }
        }

        if (centerIndex == 0 && mViews.size == 1) {
            if (animationEventListener != null) {
                animationEventListener.onAnimationEnd(this)
            }
        }

        bringTabViewToFront(centerView)
        mIsExpanded = true
        return true
    }

    private fun bringTabViewToFront(tabView: View) {
        tabView.animate().cancel()
        tabView.translationX = 0f
        tabView.translationY = 0f
        tabView.bringToFront()
        mContent.requestLayout()
        mContent.invalidate()
    }

    fun collapse() {
        collapse(DEFAULT_ANIM_TIME.toLong(), null)
    }

    fun collapse(time: Long, animationEventListener: AnimationEventListener?) {
        CrashTracking.log("BubbleFlowView.collapse(): time:$time, mIsExpanded:$mIsExpanded")
        if (mIsExpanded == false) {
            return
        }

        mDoingCollapse = true
        mIsExpanded = false
        mStillTouchFrameCount = -1
        if (DEBUG) {
            //Log.d(TAG, "[longpress] collapse(): mStillTouchFrameCount=" + mStillTouchFrameCount);
        }

        val size = mViews.size
        val centerIndex = getCenterIndex()
        if (centerIndex == -1) {
            return
        }
        val centerView = mViews[centerIndex]

        // There was previously a collapse animation to match the expand animation, but for
        // perf reasons it was removed so that it wouldn't need to track the currently dragging bubble.
        if (animationEventListener != null) {
            animationEventListener.onAnimationEnd(this)
        }

        bringTabViewToFront(centerView)
    }

    private var mCollapseEndAnimationEventListener: AnimationEventListener? = null
    fun forceCollapseEnd(): Boolean {
        var result = false
        if (mCollapseEndAnimationEventListener != null && mDoingCollapse) {
            mCollapseEndAnimationEventListener!!.onAnimationEnd(this@BubbleFlowView)
            result = true
        }
        mCollapseEndAnimationEventListener = null
        mDoingCollapse = false

        return result
    }

    fun isExpanded(): Boolean {
        return mIsExpanded
    }

    override fun onScrollChanged(x: Int, y: Int, oldX: Int, oldY: Int) {
        super.onScrollChanged(x, y, oldX, oldY)

        mStillTouchFrameCount = -1
        if (DEBUG) {
            //Log.d(TAG, "[longpress] onScrollChanged(): mStillTouchFrameCount=" + mStillTouchFrameCount);
        }

        updateScales(x)
    }

    private var mScrollFinishedCheckerInitialXPosition = -1
    private val mScrollFinishedChecker = object : Runnable {

        override fun run() {
            val scrollX = scrollX
            if (mScrollFinishedCheckerInitialXPosition - scrollX == 0) {
                mScrollFinishedCheckerInitialXPosition = -1
                if (mBubbleFlowListener != null) {
                    val currentCenterIndex = getCenterIndex()
                    if (currentCenterIndex > -1) {
                        mBubbleFlowListener!!.onCenterItemChanged(this@BubbleFlowView, mViews[currentCenterIndex])
                    }
                }
            } else {
                mScrollFinishedCheckerInitialXPosition = scrollX
                postDelayed(this, SCROLL_FINISHED_CHECK_TIME.toLong())
            }
        }
    }

    fun startScrollFinishedCheckTask(targetXPosition: Int) {
        mScrollFinishedCheckerInitialXPosition = targetXPosition
        postDelayed(mScrollFinishedChecker, SCROLL_FINISHED_CHECK_TIME.toLong())
    }

    fun isAnimatingToCenterIndex(): Boolean {
        return if (mScrollFinishedCheckerInitialXPosition > -1) true else false
    }

    /*
     * Override the fling functionality by manually setting the target index to animate towards.
     * This allows us to ensure a view is always centered in the middle of the BubbleFlowView
     */
    override fun fling(velocityX: Int) {
        mFlingCalled = true
        var debugMessage = "fling() - velocityX:$velocityX"

        val currentIndex = getCenterIndex()
        var targetIndex: Int
        val absVelocityX = Math.abs(velocityX)
        if (absVelocityX > 8000) {
            //super.fling(velocityX);
            targetIndex = if (velocityX < 0) {
                0
            } else {
                mViews.size
            }
        } else {
            if (absVelocityX > 6000) {
                targetIndex = if (velocityX < 0) {
                    currentIndex - 6
                } else {
                    currentIndex + 6
                }
            } else if (absVelocityX > 4500) {
                targetIndex = if (velocityX < 0) {
                    currentIndex - 2
                } else {
                    currentIndex + 2
                }
            } else if (absVelocityX > 2000) {
                targetIndex = if (velocityX < 0) {
                    currentIndex - 1
                } else {
                    currentIndex + 1
                }
            } else {
                if (velocityX < 0 && (currentIndex == mIndexOnActionDown)) {
                    targetIndex = mIndexOnActionDown - 1
                    debugMessage += ", [babyFling] mIndexOnActionDown: $mIndexOnActionDown, target: $targetIndex"
                } else if (velocityX > 0 && (currentIndex == mIndexOnActionDown)) {
                    targetIndex = mIndexOnActionDown + 1
                    debugMessage += ", [babyFling] mIndexOnActionDown: $mIndexOnActionDown, target: $targetIndex"
                } else {
                    debugMessage += ", [babyFling] mIndexOnActionDown: $mIndexOnActionDown, currentIndex: $currentIndex"
                    targetIndex = currentIndex
                }
            }
        }

        if (targetIndex < 0) {
            targetIndex = 0
        } else if (targetIndex >= mViews.size) {
            targetIndex = mViews.size - 1
        }
        debugMessage += ", delta:" + (targetIndex - currentIndex)
        setCenterIndex(targetIndex)

        if (DEBUG) {
            Log.d(TAG, debugMessage)
        }
    }

    /*
     * BubbleFlowView extends HorizontalScrollView, which does NOT intercept touch events when the delta is on the Y axis only.
     * We need to detect y input delta when passing input via the TouchInterceptor, thus override this function to ensure
     * true is returned in this case (but only if mTouchInterceptor != null).
     */
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {

        val action = event.action
        if ((action == MotionEvent.ACTION_MOVE) && mInterceptingTouch && mTouchInterceptor != null) {
            return true
        }

        run switchBlock@{
            when (action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_MOVE -> {
                    if (mActiveTouchPointerId == INVALID_POINTER) {
                        // If we don't have a valid id, the touch down wasn't on content.
                        return@switchBlock
                    }

                    val pointerIndex = event.findPointerIndex(mActiveTouchPointerId)
                    if (pointerIndex == -1) {
                        Log.e(TAG, "Invalid pointerId=$mActiveTouchPointerId in onInterceptTouchEvent")
                        return@switchBlock
                    }

                    val y = event.getY(pointerIndex).toInt()
                    val yDiff = Math.abs(y - mLastMotionY)
                    if (yDiff > 0) {
                        mLastMotionY = y
                        // Here is the crux of it all...
                        if (mTouchInterceptor != null) {
                            mInterceptingTouch = true
                        }
                    }

                    // ACTION_DOWN always refers to pointer index 0.
                    mLastMotionY = event.y.toInt()
                    mActiveTouchPointerId = event.getPointerId(0)
                }

                MotionEvent.ACTION_DOWN -> {
                    // ACTION_DOWN always refers to pointer index 0.
                    mLastMotionY = event.y.toInt()
                    mActiveTouchPointerId = event.getPointerId(0)
                }

                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                    if (!mInterceptingTouch && mLongPress) {
                        val bubblePeriod = Constant.BUBBLE_FLOW_ANIM_TIME.toFloat() / 1000f
                        val contentPeriod = bubblePeriod * 0.666667f      // 0.66667 is the normalized t value when f = 1.0f for overshoot interpolator of 0.5 tension
                        MainController.get()!!.expandBubbleFlow((contentPeriod * 1000).toLong(), false)
                    }
                    mActiveTouchPointerId = INVALID_POINTER
                    mInterceptingTouch = false
                }
            }
        }

        if (super.onInterceptTouchEvent(event)) {
            return true
        }

        return mInterceptingTouch
    }

    private val mViewOnClickListener = OnClickListener { v ->
        // If we just registered a vertical gesture, don't trigger a click also.
        val delta = System.currentTimeMillis() - mLastVerticalGestureTime
        if (delta < 33) {
            return@OnClickListener
        }

        val index = mViews.indexOf(v)
        if (index > -1) {
            val currentCenterIndex = getCenterIndex()
            if (currentCenterIndex != index) {
                setCenterIndex(index)
            } else {
                if (mBubbleFlowListener != null) {
                    mBubbleFlowListener!!.onCenterItemClicked(this@BubbleFlowView, v)
                }
            }
        }
    }

    val mViewOnTouchListener = OnTouchListener { view, event ->
        var result = false
        if (mViews.indexOf(view) == getCenterIndex()) {

            val action = event.action
            if (action == MotionEvent.ACTION_DOWN) {
                mTouchView = view
                mLongPress = false
                mStillTouchFrameCount = 0
                mCenterViewTouchPointerId = event.getPointerId(0)
                mCenterViewDownX = event.x
                mCenterViewDownY = event.y

                if (DEBUG) {
                    Log.d(TAG, "[longpress] onTouch() DOWN: mStillTouchFrameCount=$mStillTouchFrameCount")
                }
                if (MainController.get() != null) {
                    MainController.get()!!.scheduleUpdate()
                }
            } else if (action == MotionEvent.ACTION_UP) {
                mTouchView = null
                mLongPress = false
                mStillTouchFrameCount = -1
                if (DEBUG) {
                    Log.d(TAG, "[longpress] onTouch() UP: mStillTouchFrameCount=$mStillTouchFrameCount")
                }
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (mCenterViewTouchPointerId != INVALID_POINTER) {
                    val pointerIndex = event.findPointerIndex(mCenterViewTouchPointerId)
                    if (pointerIndex != -1) {
                        val x = event.getX(pointerIndex)
                        val y = event.getY(pointerIndex)
                        val absXDelta = Math.abs(mCenterViewDownX - x)
                        val absYDelta = Math.abs(mCenterViewDownY - y)

                        val viewsSize = mViews.size
                        // If there's only 1 view, we don't need to worry about not consuming the input that should go towards
                        // making the BubbleFlow scroll between its items, so just start working towards making this a long press.
                        if (viewsSize == 1) {
                            val distance = Config.dpToPx(6f)
                            if (absXDelta * absXDelta + absYDelta * absYDelta > distance * distance) {    // save a squareroot call
                                if (DEBUG) {
                                    Log.d(TAG, "[longpress] onTouch() MOVE: delta:" + Util.distance(0f, 0f, absXDelta, absYDelta) + " > " + distance)
                                }
                                mStillTouchFrameCount = LONG_PRESS_FRAMES - 1
                            } else {
                                mStillTouchFrameCount++
                            }
                        } else if (viewsSize > 1) {
                            if (mStillTouchFrameCount >= 0) {
                                if (absYDelta > 8f) {
                                    mStillTouchFrameCount = LONG_PRESS_FRAMES - 1
                                    if (DEBUG) {
                                        Log.e(TAG, "[longpress] onTouch() MOVE: [FORCE], absYDelta:$absYDelta")
                                    }
                                } else if (absXDelta > 3f) {
                                    mStillTouchFrameCount = -1
                                    if (DEBUG) {
                                        Log.e(TAG, "[longpress] onTouch() MOVE: [CANCEL] mStillTouchFrameCount=$mStillTouchFrameCount"
                                                + ", absXDelta:$absXDelta, absYDelta:$absYDelta")
                                    }
                                } else {
                                    if (DEBUG) {
                                        Log.d(TAG, "[longpress] onTouch() MOVE: absXDelta:$absXDelta, absYDelta:$absYDelta")
                                    }
                                }

                            }
                        }
                    }
                }
            }

            result = mVerticalGestureDetector.onTouchEvent(event)
            val gestureDirection = mVerticalGestureListener.getLastGestureDirection()
            if (gestureDirection == VerticalGestureListener.GestureDirection.Down
                    || gestureDirection == VerticalGestureListener.GestureDirection.Up) {
                mLastVerticalGestureTime = System.currentTimeMillis()
                mVerticalGestureListener.resetLastGestureDirection()
                if (mBubbleFlowListener != null) {
                    mBubbleFlowListener!!.onCenterItemSwiped(gestureDirection!!)
                }
            }
        }
        result
    }

    companion object {
        private const val TAG = "BubbleFlowView"
        private const val DEBUG = true
        private const val INVALID_POINTER = -1
        private const val MIN_SCALE = .7f
        private const val LONG_PRESS_FRAMES = 6
        private const val DEFAULT_ANIM_TIME = 300
        private const val SCROLL_FINISHED_CHECK_TIME = 33
    }
}
