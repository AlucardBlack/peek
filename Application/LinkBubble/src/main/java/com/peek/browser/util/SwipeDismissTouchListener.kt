package com.peek.browser.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A [android.view.View.OnTouchListener] that makes any [android.view.View] dismissible
 * when the user swipes (drags her finger) horizontally across the view.
 *
 * @author Roman Nurik
 */
class SwipeDismissTouchListener(
        private val mView: View,
        private val mToken: Any?,
        private val mCallbacks: DismissCallbacks
) : View.OnTouchListener {
    // Cached ViewConfiguration and system-wide constant values
    private val mSlop: Int
    private val mMinFlingVelocity: Int
    private val mMaxFlingVelocity: Int
    private val mAnimationTime: Long

    // Fixed properties
    private var mViewWidth = 1 // 1 and not 0 to prevent dividing by zero

    // Transient properties
    private var mDownX = 0f
    private var mDownY = 0f
    private var mSwiping = false
    private var mSwipingSlop = 0
    private var mVelocityTracker: VelocityTracker? = null
    private var mTranslationX = 0f

    /**
     * The callback interface used by [SwipeDismissTouchListener] to inform its client
     * about a successful dismissal of the view for which it was created.
     */
    interface DismissCallbacks {
        /**
         * Called to determine whether the view can be dismissed.
         */
        fun canDismiss(token: Any?): Boolean

        /**
         * Called when the user has indicated they she would like to dismiss the view.
         *
         * @param view  The originating [android.view.View] to be dismissed.
         * @param token The optional token passed to this object's constructor.
         */
        fun onDismiss(view: View, token: Any?)

        /**
         * Called when we have returned to our default resting place
         */
        fun onReturn()
    }

    init {
        val vc = ViewConfiguration.get(mView.context)
        mSlop = vc.scaledTouchSlop
        mMinFlingVelocity = vc.scaledMinimumFlingVelocity * 16
        mMaxFlingVelocity = vc.scaledMaximumFlingVelocity
        mAnimationTime = mView.context.resources.getInteger(
                android.R.integer.config_shortAnimTime).toLong()
    }

    override fun onTouch(view: View, motionEvent: MotionEvent): Boolean {
        // offset because the view is translated during swipe
        motionEvent.offsetLocation(mTranslationX, 0f)

        if (mViewWidth < 2) {
            mViewWidth = mView.width
        }

        when (motionEvent.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // TODO: ensure this is a finger, and set a flag
                mDownX = motionEvent.rawX
                mDownY = motionEvent.rawY
                if (mCallbacks.canDismiss(mToken)) {
                    mVelocityTracker = VelocityTracker.obtain()
                    mVelocityTracker?.addMovement(motionEvent)
                }
                return false
            }

            MotionEvent.ACTION_UP -> {
                val velocityTracker = mVelocityTracker ?: return false

                val deltaX = motionEvent.rawX - mDownX
                velocityTracker.addMovement(motionEvent)
                velocityTracker.computeCurrentVelocity(1000)
                val velocityX = velocityTracker.xVelocity
                val absVelocityX = abs(velocityX)
                val absVelocityY = abs(velocityTracker.yVelocity)
                var dismiss = false
                var dismissRight = false
                if (abs(deltaX) > mViewWidth / 2 && mSwiping) {
                    dismiss = true
                    dismissRight = deltaX > 0
                } else if (mMinFlingVelocity <= absVelocityX && absVelocityX <= mMaxFlingVelocity
                        && absVelocityY < absVelocityX
                        && absVelocityY < absVelocityX && mSwiping) {
                    // dismiss only if flinging in the same direction as dragging
                    dismiss = (velocityX < 0) == (deltaX < 0)
                    dismissRight = velocityTracker.xVelocity > 0
                }
                if (dismiss) {
                    // dismiss
                    mView.animate()
                            .translationX((if (dismissRight) mViewWidth else -mViewWidth).toFloat())
                            .alpha(0f)
                            .setDuration(mAnimationTime)
                            .setListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: Animator) {
                                    performDismiss()
                                }
                            })
                } else if (mSwiping) {
                    // cancel
                    mView.animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(mAnimationTime)
                            .setListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: Animator) {
                                    mCallbacks.onReturn()
                                }
                            })
                }
                velocityTracker.recycle()
                mVelocityTracker = null
                mTranslationX = 0f
                mDownX = 0f
                mDownY = 0f
                mSwiping = false
            }

            MotionEvent.ACTION_CANCEL -> {
                val velocityTracker = mVelocityTracker ?: return false

                mView.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(mAnimationTime)
                        .setListener(null)
                velocityTracker.recycle()
                mVelocityTracker = null
                mTranslationX = 0f
                mDownX = 0f
                mDownY = 0f
                mSwiping = false
            }

            MotionEvent.ACTION_MOVE -> {
                val velocityTracker = mVelocityTracker ?: return false

                velocityTracker.addMovement(motionEvent)
                val deltaX = motionEvent.rawX - mDownX
                val deltaY = motionEvent.rawY - mDownY
                if (abs(deltaX) > mSlop && abs(deltaY) < abs(deltaX) / 2) {
                    mSwiping = true
                    mSwipingSlop = if (deltaX > 0) mSlop else -mSlop

                    mView.parent?.requestDisallowInterceptTouchEvent(true)

                    // Cancel listview's touch
                    val cancelEvent = MotionEvent.obtain(motionEvent)
                    cancelEvent.action = MotionEvent.ACTION_CANCEL or
                            (motionEvent.actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                    mView.onTouchEvent(cancelEvent)
                    cancelEvent.recycle()
                }

                if (mSwiping) {
                    mTranslationX = deltaX
                    mView.translationX = deltaX - mSwipingSlop
                    // TODO: use an ease-out interpolator or such
                    mView.alpha = max(0f, min(1f,
                            1f - 2f * abs(deltaX) / mViewWidth))
                    return true
                }
            }
        }
        return false
    }

    private fun performDismiss() {
        // Animate the dismissed view to zero-height and then fire the dismiss callback.
        // This triggers layout on each animation frame; in the future we may want to do something
        // smarter and more performant.

        val lp: ViewGroup.LayoutParams = mView.layoutParams
        val originalHeight = mView.height

        val animator = ValueAnimator.ofInt(originalHeight, 1).setDuration(mAnimationTime)

        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                mCallbacks.onDismiss(mView, mToken)
                // Reset view presentation
                mView.alpha = 1f
                mView.translationX = 0f
                lp.height = originalHeight
                mView.layoutParams = lp
            }
        })

        animator.addUpdateListener(ValueAnimator.AnimatorUpdateListener { valueAnimator ->
            lp.height = valueAnimator.animatedValue as Int
            mView.layoutParams = lp
        })

        animator.start()
    }
}
