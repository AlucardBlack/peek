package com.peek.browser.ui

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Camera
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.Transformation
import android.widget.FrameLayout
import com.peek.browser.R

/**
 * Created with IntelliJ IDEA. User: castorflex Date: 30/12/12 Time: 16:25
 */
class FlipView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle), View.OnClickListener, Animation.AnimationListener {

    interface OnFlipListener {
        fun onClick(view: FlipView)
        fun onFlipStart(view: FlipView)
        fun onFlipEnd(view: FlipView)
    }

    private var mListener: OnFlipListener? = null

    private var mIsFlipped: Boolean = false

    private var mIsDefaultAnimated: Boolean = false

    private lateinit var mDefaultView: View
    private lateinit var mFlippedView: View

    private lateinit var mAnimation: FlipAnimator

    private var mIsRotationXEnabled: Boolean = false

    private var mIsRotationYEnabled: Boolean = false

    private var mIsRotationZEnabled: Boolean = false

    private var mIsFlipping: Boolean = false

    private var mIsRotationReversed: Boolean = false

    init {
        init(context, attrs, defStyle)
    }

    private fun init(context: Context, attrs: AttributeSet?, defStyle: Int) {
        if (isInEditMode) {
            return
        }

        sDefaultDuration = context.resources.getInteger(R.integer.default_fiv_duration)
        sDefaultRotations = context.resources.getInteger(R.integer.default_fiv_rotations)
        sDefaultAnimated = context.resources.getBoolean(R.bool.default_fiv_isAnimated)
        sDefaultFlipped = context.resources.getBoolean(R.bool.default_fiv_isFlipped)
        sDefaultIsRotationReversed = context.resources.getBoolean(R.bool.default_fiv_isRotationReversed)

        val a = context.obtainStyledAttributes(attrs, R.styleable.FlipImageView, defStyle, 0)
        mIsDefaultAnimated = a.getBoolean(R.styleable.FlipImageView_isAnimated, sDefaultAnimated)
        mIsFlipped = a.getBoolean(R.styleable.FlipImageView_isFlipped, sDefaultFlipped)

        mDefaultView = View.inflate(context, a.getResourceId(R.styleable.FlipImageView_defaultView, 0), null)
        mFlippedView = View.inflate(context, a.getResourceId(R.styleable.FlipImageView_flipView, 0), null)

        val duration = a.getInt(R.styleable.FlipImageView_flipDuration, sDefaultDuration)
        val interpolatorResId = a.getResourceId(R.styleable.FlipImageView_flipInterpolator, 0)
        val interpolator = if (interpolatorResId > 0) AnimationUtils
                .loadInterpolator(context, interpolatorResId) else fDefaultInterpolator
        val rotations = a.getInteger(R.styleable.FlipImageView_flipRotations, sDefaultRotations)
        mIsRotationXEnabled = (rotations and FLAG_ROTATION_X) != 0
        mIsRotationYEnabled = (rotations and FLAG_ROTATION_Y) != 0
        mIsRotationZEnabled = (rotations and FLAG_ROTATION_Z) != 0

        mIsRotationReversed = a.getBoolean(R.styleable.FlipImageView_reverseRotation, sDefaultIsRotationReversed)

        mAnimation = FlipAnimator()
        mAnimation.setAnimationListener(this)
        mAnimation.interpolator = interpolator
        mAnimation.duration = duration.toLong()

        setOnClickListener(this)

        addView(if (mIsFlipped) mFlippedView else mDefaultView)
        mIsFlipping = false

        a.recycle()
    }

    fun setFlippedView(flippedView: View) {
        mFlippedView = flippedView
        removeAllViews()
        addView(mFlippedView)
    }

    fun getFlippedView(): View {
        return mFlippedView
    }

    fun setDefaultView(view: View) {
        mDefaultView = view
        removeAllViews()
        addView(mDefaultView)
    }

    fun getDefaultView(): View {
        return mDefaultView
    }

    fun isRotationXEnabled(): Boolean {
        return mIsRotationXEnabled
    }

    fun setRotationXEnabled(enabled: Boolean) {
        mIsRotationXEnabled = enabled
    }

    fun isRotationYEnabled(): Boolean {
        return mIsRotationYEnabled
    }

    fun setRotationYEnabled(enabled: Boolean) {
        mIsRotationYEnabled = enabled
    }

    fun isRotationZEnabled(): Boolean {
        return mIsRotationZEnabled
    }

    fun setRotationZEnabled(enabled: Boolean) {
        mIsRotationZEnabled = enabled
    }

    fun getFlipAnimation(): FlipAnimator {
        return mAnimation
    }

    fun setInterpolator(interpolator: Interpolator) {
        mAnimation.interpolator = interpolator
    }

    fun setDuration(duration: Int) {
        mAnimation.duration = duration.toLong()
    }

    fun isFlipped(): Boolean {
        return mIsFlipped
    }

    fun isFlipping(): Boolean {
        return mIsFlipping
    }

    fun isRotationReversed(): Boolean {
        return mIsRotationReversed
    }

    fun setRotationReversed(rotationReversed: Boolean) {
        mIsRotationReversed = rotationReversed
    }

    fun isAnimated(): Boolean {
        return mIsDefaultAnimated
    }

    fun setAnimated(animated: Boolean) {
        mIsDefaultAnimated = animated
    }

    fun setFlipped(flipped: Boolean) {
        setFlipped(flipped, mIsDefaultAnimated)
    }

    fun setFlipped(flipped: Boolean, animated: Boolean) {
        if (flipped != mIsFlipped) {
            toggleFlip(animated)
        }
    }

    fun toggleFlip() {
        toggleFlip(mIsDefaultAnimated)
    }

    fun toggleFlip(animated: Boolean) {
        if (animated) {
            mAnimation.setToView(if (mIsFlipped) mDefaultView else mFlippedView)
            startAnimation(mAnimation)
        } else {
            removeAllViews()
            addView(if (mIsFlipped) mDefaultView else mFlippedView)
        }
        mIsFlipped = !mIsFlipped
    }

    fun setOnFlipListener(listener: OnFlipListener) {
        mListener = listener
    }

    override fun onClick(v: View) {
        toggleFlip()
        if (mListener != null) {
            mListener!!.onClick(this)
        }
    }

    override fun onAnimationStart(animation: Animation) {
        if (mListener != null) {
            mListener!!.onFlipStart(this)
        }
        mIsFlipping = true
    }

    override fun onAnimationEnd(animation: Animation) {
        if (mListener != null) {
            mListener!!.onFlipEnd(this)
        }
        mIsFlipping = false
    }

    override fun onAnimationRepeat(animation: Animation) {
    }

    /**
     * Animation part All credits goes to coomar
     */
    inner class FlipAnimator : Animation() {

        private lateinit var camera: Camera
        private var mToView: View? = null
        private var centerX: Float = 0f
        private var centerY: Float = 0f
        private var visibilitySwapped: Boolean = false

        fun setToView(to: View) {
            mToView = to
            visibilitySwapped = false
        }

        init {
            fillAfter = true
        }

        override fun initialize(width: Int, height: Int, parentWidth: Int, parentHeight: Int) {
            super.initialize(width, height, parentWidth, parentHeight)
            camera = Camera()
            this.centerX = (width / 2).toFloat()
            this.centerY = (height / 2).toFloat()
        }

        override fun applyTransformation(interpolatedTime: Float, t: Transformation) {
            // Angle around the y-axis of the rotation at the given time. It is
            // calculated both in radians and in the equivalent degrees.
            val radians = Math.PI * interpolatedTime
            var degrees = (180.0 * radians / Math.PI).toFloat()

            if (mIsRotationReversed) {
                degrees = -degrees
            }

            // Once we reach the midpoint in the animation, we need to hide the
            // source view and show the destination view. We also need to change
            // the angle by 180 degrees so that the destination does not come in
            // flipped around. This is the main problem with SDK sample, it does not
            // do this.
            if (interpolatedTime >= 0.5f) {
                if (mIsRotationReversed) {
                    degrees += 180f
                } else {
                    degrees -= 180f
                }

                if (!visibilitySwapped) {
                    removeAllViews()
                    addView(mToView)
                    visibilitySwapped = true
                }
            }

            val matrix = t.matrix

            camera.save()
            camera.translate(0.0f, 0.0f, (150.0 * Math.sin(radians)).toFloat())
            camera.rotateX(if (mIsRotationXEnabled) degrees else 0f)
            camera.rotateY(if (mIsRotationYEnabled) degrees else 0f)
            camera.rotateZ(if (mIsRotationZEnabled) degrees else 0f)
            camera.getMatrix(matrix)
            camera.restore()

            matrix.preTranslate(-centerX, -centerY)
            matrix.postTranslate(centerX, centerY)
        }
    }

    companion object {
        private const val FLAG_ROTATION_X = 1 shl 0

        private const val FLAG_ROTATION_Y = 1 shl 1

        private const val FLAG_ROTATION_Z = 1 shl 2

        private val fDefaultInterpolator: Interpolator = DecelerateInterpolator()

        private var sDefaultDuration: Int = 0

        private var sDefaultRotations: Int = 0

        private var sDefaultAnimated: Boolean = false

        private var sDefaultFlipped: Boolean = false

        private var sDefaultIsRotationReversed: Boolean = false
    }
}
