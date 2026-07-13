/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.physics

import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import com.peek.browser.MainController
import com.peek.browser.util.CrashTracking
import com.peek.browser.util.Util

class DraggableHelper(
        private val mView: View,
        private val mWindowManagerParams: WindowManager.LayoutParams,
        setOnTouchListener: Boolean,
        private val mOnTouchActionEventListener: OnTouchActionEventListener?
) {

    companion object {
        // Below this duration, an animation is a live touch-drag follow or a brief snap-hover
        // adjustment - cheap enough (few frames) that moving the real window directly is fine, and
        // not worth paying two extra window resizes (grow + shrink) for the windowed-translation path.
        private const val MIN_ANIM_DURATION_FOR_WINDOW_EXPANSION = 0.15f
    }

    enum class AnimationType {
        Linear,
        SmallOvershoot,
        MediumOvershoot,
        LargeOvershoot,
        DistanceProportion
    }

    interface OnTouchActionEventListener {
        fun onActionDown(event: TouchEvent)
        fun onActionMove(event: MoveEvent)
        fun onActionUp(event: ReleaseEvent)
    }

    class TouchEvent {
        @JvmField var posX = 0
        @JvmField var posY = 0
        @JvmField var rawX = 0f
        @JvmField var rawY = 0f
    }

    class MoveEvent {
        @JvmField var dx = 0
        @JvmField var dy = 0
        @JvmField var rawX = 0f
        @JvmField var rawY = 0f
    }

    class ReleaseEvent {
        @JvmField var posX = 0
        @JvmField var posY = 0
        @JvmField var vx = 0f
        @JvmField var vy = 0f
        @JvmField var rawX = 0f
        @JvmField var rawY = 0f
    }

    private class InternalMoveEvent(var mX: Float, var mY: Float, var mTime: Long)

    // Reusable events
    val mTouchEvent = TouchEvent()
    val mMoveEvent = MoveEvent()
    val mReleaseEvent = ReleaseEvent()

    private var mAlive: Boolean

    // Move animation state
    private var mInitialX = 0
    private var mInitialY = 0
    private var mTargetX = 0
    private var mTargetY = 0
    private var mAnimPeriod = 0f
    private var mAnimTime = 0f
    private var mAnimType: AnimationType? = null
    private val mLinearInterpolator = LinearInterpolator()
    private val mOvershootInterpolatorSmall = OvershootInterpolator(0.5f)
    private val mOvershootInterpolatorMedium = OvershootInterpolator(1.5f)
    private val mOvershootInterpolatorLarge = OvershootInterpolator(2.0f)

    private val mStartTouchRaw: InternalMoveEvent
    private val mEndTouchRaw: InternalMoveEvent

    private var mFlingTracker: FlingTracker? = null
    private var mStartTouchX = -1
    private var mStartTouchY = -1
    private var mAnimationListener: AnimationEventListener? = null

    // The window's real WindowManager position/size, kept in sync with mLogicalX/mLogicalY except
    // during a "windowed" animation (see beginWindowExpansion), where the real window is grown once
    // to cover the whole travel path and the view is translated within it instead of being moved via
    // a WindowManager IPC call every frame - see the AnimationType-vs-duration gating in setTargetPos.
    private var mLogicalX = mWindowManagerParams.x
    private var mLogicalY = mWindowManagerParams.y
    private var mOriginalWidth = mWindowManagerParams.width
    private var mOriginalHeight = mWindowManagerParams.height
    private var mWindowExpanded = false
    private var mWindowOriginX = 0
    private var mWindowOriginY = 0
    private var mFollowActive = false
    private var mOnWindowedAnimationListener: OnWindowedAnimationListener? = null

    interface AnimationEventListener {
        fun onAnimationComplete()
        fun onCancel()
    }

    // Fired when a windowed (grow-once + translate) animation starts/ends on this helper,
    // so a follower window tracking this one per-frame can mirror the optimization.
    interface OnWindowedAnimationListener {
        fun onBegin(fromX: Int, fromY: Int, toX: Int, toY: Int)
        fun onEnd()
    }

    fun setOnWindowedAnimationListener(listener: OnWindowedAnimationListener?) {
        mOnWindowedAnimationListener = listener
    }

    fun isWindowedAnimationActive(): Boolean {
        return mWindowExpanded && !mFollowActive
    }

    fun getAnimInitialX(): Int = mInitialX
    fun getAnimInitialY(): Int = mInitialY
    fun getAnimTargetX(): Int = mTargetX
    fun getAnimTargetY(): Int = mTargetY

    private val mOnTouchListener = View.OnTouchListener { v, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                onTouchActionDown(event)
            }
            MotionEvent.ACTION_MOVE -> {
                onTouchActionMove(event)
            }
            MotionEvent.ACTION_UP -> {
                onTouchActionUp(event)
            }
            //case MotionEvent.ACTION_CANCEL: {
            //    return true;
            //}
            else -> false
        }
    }

    init {
        mAlive = true

        mStartTouchRaw = InternalMoveEvent(0f, 0f, 0)
        mEndTouchRaw = InternalMoveEvent(0f, 0f, 0)


        if (setOnTouchListener) {
            mView.setOnTouchListener(mOnTouchListener)
        }
    }

    private fun addMoveEvent(x: Float, y: Float, t: Long) {
        if (mStartTouchRaw.mTime == 0L) {
            mStartTouchRaw.mTime = t
            mStartTouchRaw.mX = x
            mStartTouchRaw.mY = y
        }
        mEndTouchRaw.mTime = t
        mEndTouchRaw.mX = x
        mEndTouchRaw.mY = y
    }

    fun onTouchActionDown(event: MotionEvent): Boolean {
        mTouchEvent.posX = mWindowManagerParams.x
        mTouchEvent.posY = mWindowManagerParams.y
        mTouchEvent.rawX = event.rawX
        mTouchEvent.rawY = event.rawY

        addMoveEvent(event.rawX, event.rawY, event.eventTime)

        mOnTouchActionEventListener?.onActionDown(mTouchEvent)

        mStartTouchX = mWindowManagerParams.x
        mStartTouchY = mWindowManagerParams.y

        val flingTracker = FlingTracker.obtain()
        mFlingTracker = flingTracker
        flingTracker.addMovement(event)

        return true
    }

    fun onTouchActionMove(event: MotionEvent): Boolean {
        if (mStartTouchX == -1 && mStartTouchY == -1) {
            onTouchActionDown(event)
        }

        val touchXRaw = event.rawX
        val touchYRaw = event.rawY

        val deltaX = (touchXRaw - mStartTouchRaw.mX).toInt()
        val deltaY = (touchYRaw - mStartTouchRaw.mY).toInt()

        addMoveEvent(touchXRaw, touchYRaw, event.eventTime)

        mMoveEvent.dx = deltaX
        mMoveEvent.dy = deltaY
        mMoveEvent.rawX = touchXRaw
        mMoveEvent.rawY = touchYRaw
        mOnTouchActionEventListener?.onActionMove(mMoveEvent)

        event.offsetLocation((mWindowManagerParams.x - mStartTouchX).toFloat(), (mWindowManagerParams.y - mStartTouchY).toFloat())
        mFlingTracker!!.addMovement(event)

        return true
    }

    fun hasAtLeast2TouchEvents(): Boolean {
        return mStartTouchRaw.mTime != 0L && mEndTouchRaw.mTime != 0L && mEndTouchRaw.mTime != mStartTouchRaw.mTime
    }

    fun onTouchActionUp(event: MotionEvent): Boolean {
        mReleaseEvent.posX = mWindowManagerParams.x
        mReleaseEvent.posY = mWindowManagerParams.y
        mReleaseEvent.vx = 0.0f
        mReleaseEvent.vy = 0.0f
        mReleaseEvent.rawX = event.rawX
        mReleaseEvent.rawY = event.rawY

        if (hasAtLeast2TouchEvents()) {
            val touchTime = (mEndTouchRaw.mTime - mStartTouchRaw.mTime) / 1000.0f
            mReleaseEvent.vx = (mEndTouchRaw.mX - mStartTouchRaw.mX) / touchTime
            mReleaseEvent.vy = (mEndTouchRaw.mY - mStartTouchRaw.mY) / touchTime
        }

        // *Should* always be true, but under certain circumstances, is not. #384
        val flingTracker = mFlingTracker
        if (flingTracker != null) {
            flingTracker.computeCurrentVelocity(1000)
            val fvx = flingTracker.getXVelocity()
            val fvy = flingTracker.getYVelocity()

            mReleaseEvent.vx = fvx
            mReleaseEvent.vy = fvy

            flingTracker.recycle()
        }

        mOnTouchActionEventListener?.onActionUp(mReleaseEvent)

        mStartTouchX = -1
        mStartTouchY = -1
        mStartTouchRaw.mTime = 0
        mEndTouchRaw.mTime = 0
        return true
    }

    fun cancelAnimation() {
        val listener = mAnimationListener
        mAnimationListener = null

        clearTargetPos()

        listener?.onCancel()
    }

    fun getAnimCompleteFraction(): Float {
        var f = 1.0f

        if (mAnimPeriod > 0.0f) {
            f = Util.clamp(0.0f, mAnimTime / mAnimPeriod, 1.0f)
        }

        return f
    }

    fun clearTargetPos() {
        // TODO: This probably fires. It can be disabled temporarily if a pain, but should be fixed.
        Util.Assert(mAnimationListener == null, "non-null mAnimationListener")

        endWindowExpansion(mLogicalX, mLogicalY)

        mInitialX = -1
        mInitialY = -1

        mTargetX = mLogicalX
        mTargetY = mLogicalY

        mAnimPeriod = 0.0f
        mAnimTime = 0.0f
    }

    // Grows the real overlay window once to cover the whole [fromX,fromY]-[toX,toY] travel
    // rectangle and switches to translating the view within it, instead of moving the real
    // window (a WindowManager IPC call) on every animation frame. Only worth it for animations
    // long enough to have many frames - short/continuous ones (live touch-drag follows, snap-hover
    // micro adjustments) are cheaper to just move directly, and DistanceProportion specifically is
    // always a live-touch-drag follow, never a discrete transition.
    private fun beginWindowExpansion(fromX: Int, fromY: Int, toX: Int, toY: Int, duration: Float, type: AnimationType) {
        if (!mAlive || type == AnimationType.DistanceProportion || duration < MIN_ANIM_DURATION_FOR_WINDOW_EXPANSION) {
            return
        }

        val left = Math.min(fromX, toX)
        val top = Math.min(fromY, toY)
        mWindowOriginX = left
        mWindowOriginY = top

        mWindowManagerParams.x = left
        mWindowManagerParams.y = top
        mWindowManagerParams.width = Math.max(fromX, toX) - left + mOriginalWidth
        mWindowManagerParams.height = Math.max(fromY, toY) - top + mOriginalHeight
        mWindowManagerParams.flags = mWindowManagerParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        MainController.updateRootWindowLayout(mView, mWindowManagerParams)

        mView.translationX = (fromX - mWindowOriginX).toFloat()
        mView.translationY = (fromY - mWindowOriginY).toFloat()

        mWindowExpanded = true

        mOnWindowedAnimationListener?.onBegin(fromX, fromY, toX, toY)
    }

    // Shrinks the real overlay window back to its normal bubble-sized bounds at (finalX, finalY)
    // and clears the view translation used during an expanded-window animation. Safe to call even
    // when no expansion is active (e.g. from clearTargetPos()/setExactPos() on the common path).
    private fun endWindowExpansion(finalX: Int, finalY: Int) {
        if (!mWindowExpanded) {
            return
        }
        mWindowExpanded = false
        mFollowActive = false

        mView.translationX = 0f
        mView.translationY = 0f

        if (mAlive) {
            mWindowManagerParams.x = finalX
            mWindowManagerParams.y = finalY
            mWindowManagerParams.width = mOriginalWidth
            mWindowManagerParams.height = mOriginalHeight
            mWindowManagerParams.flags = mWindowManagerParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            MainController.updateRootWindowLayout(mView, mWindowManagerParams)
        }

        mOnWindowedAnimationListener?.onEnd()
    }

    // Follow-session: the same grow-once + translate optimization as beginWindowExpansion, but for
    // a window that is repositioned externally every frame via setExactPos() (glued to another
    // window's animation) rather than driven by this helper's own setTargetPos() animation.
    fun beginFollow(fromX: Int, fromY: Int, toX: Int, toY: Int) {
        if (!mAlive || mWindowExpanded) {
            return
        }

        val left = Math.min(fromX, toX)
        val top = Math.min(fromY, toY)
        mWindowOriginX = left
        mWindowOriginY = top

        mWindowManagerParams.x = left
        mWindowManagerParams.y = top
        mWindowManagerParams.width = Math.max(fromX, toX) - left + mOriginalWidth
        mWindowManagerParams.height = Math.max(fromY, toY) - top + mOriginalHeight
        mWindowManagerParams.flags = mWindowManagerParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        MainController.updateRootWindowLayout(mView, mWindowManagerParams)

        mView.translationX = (mLogicalX - left).toFloat()
        mView.translationY = (mLogicalY - top).toFloat()

        mWindowExpanded = true
        mFollowActive = true
    }

    fun endFollow() {
        if (!mFollowActive) {
            return
        }
        endWindowExpansion(mLogicalX, mLogicalY)
    }

    // The window's normal (non-expanded) size is captured at construction, but callers may resize
    // the real window later (e.g. BubbleFlowDraggable.configure() on rotation) - they must refresh
    // the base size here or endWindowExpansion() would restore stale pre-rotation bounds.
    fun setBaseSize(width: Int, height: Int) {
        mOriginalWidth = width
        mOriginalHeight = height
    }

    fun setExactPos(x: Int, y: Int) {
        if (mFollowActive && mWindowExpanded) {
            // Follow-session: glued to another window's animation, repositioned every frame.
            // Translate within the pre-grown window instead of a WindowManager IPC per frame.
            mLogicalX = x
            mLogicalY = y
            mTargetX = x
            mTargetY = y
            mView.translationX = (x - mWindowOriginX).toFloat()
            mView.translationY = (y - mWindowOriginY).toFloat()
            return
        }
        if (mWindowExpanded) {
            endWindowExpansion(x, y)
        }
        mLogicalX = x
        mLogicalY = y

        if (mWindowManagerParams.x == x && mWindowManagerParams.y == y) {
            return
        }
        mWindowManagerParams.x = x
        mWindowManagerParams.y = y
        mTargetX = x
        mTargetY = y

        if (mAlive) {
            MainController.updateRootWindowLayout(mView, mWindowManagerParams)
        }
    }

    fun setTargetPos(x: Int, y: Int, t: Float, type: AnimationType, listener: AnimationEventListener?) {
        var xIn = x
        var yIn = y
        var tIn = t
        var typeIn = type
        try {
            Util.Assert(mAnimationListener == null, "non-null mAnimationListener")
        } catch (exc: AssertionError) {
            CrashTracking.logHandledException(exc)
        }
        mAnimationListener = listener

        if (xIn != mTargetX || yIn != mTargetY) {

            val originalType = typeIn

            if (typeIn == AnimationType.DistanceProportion) {
                // Something > 0.016 will have a high likelihood of causing < 60fps
                val maxTime = 0.005f
                val maxDistance = 50.0f

                val d = Util.distance(xIn.toFloat(), yIn.toFloat(), mLogicalX.toFloat(), mLogicalY.toFloat())
                tIn = maxTime * d / maxDistance
                tIn = maxTime - Util.clamp(0.0f, tIn, maxTime)
                typeIn = AnimationType.Linear
            }

            if (tIn < 0.0001f) {
                clearTargetPos()
                setExactPos(xIn, yIn)
            } else {
                mAnimType = typeIn

                mInitialX = mLogicalX
                mInitialY = mLogicalY

                mTargetX = xIn
                mTargetY = yIn

                mAnimPeriod = tIn
                mAnimTime = 0.0f

                beginWindowExpansion(mInitialX, mInitialY, mTargetX, mTargetY, tIn, originalType)
            }

            MainController.get()?.scheduleUpdate()
        } else if (listener != null) {
            mAnimationListener = null
            listener.onAnimationComplete()
        }
    }

    fun getXPos(): Int {
        return mLogicalX
    }

    fun getYPos(): Int {
        return mLogicalY
    }

    fun getWindowManagerParams(): WindowManager.LayoutParams {
        return mWindowManagerParams
    }

    fun isAlive(): Boolean {
        return mAlive
    }

    fun getView(): View {
        return mView
    }

    fun update(dt: Float): Boolean {
        if (mAnimTime < mAnimPeriod) {
            Util.Assert(mAnimPeriod > 0.0f, "mAnimPeriod:$mAnimPeriod")

            mAnimTime = Util.clamp(0.0f, mAnimTime + dt, mAnimPeriod)

            val tf = mAnimTime / mAnimPeriod
            var interpolatedFraction = 0.0f
            when (mAnimType) {
                AnimationType.Linear ->
                    interpolatedFraction = mLinearInterpolator.getInterpolation(tf)
                AnimationType.SmallOvershoot ->
                    interpolatedFraction = mOvershootInterpolatorSmall.getInterpolation(tf)
                AnimationType.MediumOvershoot ->
                    interpolatedFraction = mOvershootInterpolatorMedium.getInterpolation(tf)
                AnimationType.LargeOvershoot ->
                    interpolatedFraction = mOvershootInterpolatorLarge.getInterpolation(tf)
                else -> {}
            }

            val x = (mInitialX + (mTargetX - mInitialX) * interpolatedFraction).toInt()
            val y = (mInitialY + (mTargetY - mInitialY) * interpolatedFraction).toInt()

            mLogicalX = x
            mLogicalY = y

            if (mWindowExpanded) {
                mView.translationX = (x - mWindowOriginX).toFloat()
                mView.translationY = (y - mWindowOriginY).toFloat()
            } else if (mWindowManagerParams.x != x || mWindowManagerParams.y != y) {
                mWindowManagerParams.x = x
                mWindowManagerParams.y = y
                MainController.updateRootWindowLayout(mView, mWindowManagerParams)
            }

            MainController.get()!!.scheduleUpdate()

            if (mAnimTime >= mAnimPeriod) {
                mAnimTime = 0.0f
                mAnimPeriod = 0.0f
                endWindowExpansion(mTargetX, mTargetY)
                val l = mAnimationListener
                if (l != null) {
                    mAnimationListener = null
                    l.onAnimationComplete()
                }
            }

            return true
        }

        return false
    }

    fun destroy() {
        MainController.removeRootWindow(mView)
        mAlive = false
    }
}
