/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
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

open class BubbleTargetView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    private var mImage: ImageView? = null
    private var mCanvasView: CanvasView? = null

    enum class Interpolator {
        Linear,
        Overshoot
    }

    private lateinit var mHAnchor: HorizontalAnchor
    private lateinit var mVAnchor: VerticalAnchor
    private var mDefaultX = 0
    private var mDefaultY = 0
    private var mMaxOffsetX = 0
    private var mMaxOffsetY = 0
    private var mTractorOffsetX = 0
    private var mTractorOffsetY = 0
    private var mSnapWidth = 0f
    private var mSnapHeight = 0f
    lateinit var mSnapCircle: Circle
    lateinit var mDefaultCircle: Circle
    private lateinit var mAction: Constant.BubbleAction

    private val mCanvasLayoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private var mHomeX = 0
    private var mHomeY = 0

    enum class HorizontalAnchor {
        Left,
        Center,
        Right
    }

    enum class VerticalAnchor {
        Top,
        Bottom
    }

    private val mLinearInterpolator = LinearInterpolator()
    private val mOvershootInterpolator = OvershootInterpolator(1.5f)
    private var mIsSnapping = false
    private var mIsLongHovering = false
    private var mTimeSinceSnapping = 0f

    private val TRANSITION_TIME = 0.15f

    override fun onFinishInflate() {
        super.onFinishInflate()

        mImage = findViewById(R.id.image_view)
    }

    protected open fun getRadius(): Float {
        val tabSize = resources.getDimensionPixelSize(R.dimen.bubble_icon_size)
        return tabSize * 0.5f
    }

    private fun getXPos(): Int {
        when (mHAnchor) {
            HorizontalAnchor.Left -> return mDefaultX
            HorizontalAnchor.Right -> return Config.mScreenWidth - mDefaultX
            HorizontalAnchor.Center -> return (Config.mScreenWidth * 0.5f + mDefaultX).toInt()
        }

        @Suppress("UNREACHABLE_CODE")
        Util.Assert(false, "Anchor not handled - $mHAnchor")
        @Suppress("UNREACHABLE_CODE")
        return 0
    }

    private fun getYPos(): Int {
        when (mVAnchor) {
            VerticalAnchor.Top -> return mDefaultY
            VerticalAnchor.Bottom -> return Config.mScreenHeight - mDefaultY
        }

        @Suppress("UNREACHABLE_CODE")
        Util.Assert(false, "Anchor not handled - $mVAnchor")
        @Suppress("UNREACHABLE_CODE")
        return 0
    }

    fun setTargetCenter(x: Int, y: Int) {
        setTargetPos((x - mSnapWidth * 0.5f).toInt(), (y - mSnapHeight * 0.5f).toInt())
    }

    fun setTargetPos(x: Int, y: Int) {
        mCanvasLayoutParams.leftMargin = x
        mCanvasLayoutParams.topMargin = y
    }

    fun onConsumeBubblesChanged() {
        var d: Drawable? = null

        when (mAction) {
            Constant.BubbleAction.ConsumeLeft, Constant.BubbleAction.ConsumeRight ->
                d = Settings.get().getConsumeBubbleIcon(mAction)
            else -> {}
        }

        if (d != null) {
            mImage!!.setImageDrawable(d)
        }
    }

    fun configure(canvasView: CanvasView, context: Context, d: Drawable?, action: Constant.BubbleAction, defaultX: Int, hAnchor: HorizontalAnchor,
                  defaultY: Int, vAnchor: VerticalAnchor, maxOffsetX: Int, maxOffsetY: Int,
                  tractorOffsetX: Int, tractorOffsetY: Int) {
        mCanvasView = canvasView
        mAction = action

        mHAnchor = hAnchor
        mVAnchor = vAnchor
        mDefaultX = defaultX
        mDefaultY = defaultY
        mMaxOffsetX = maxOffsetX
        mMaxOffsetY = maxOffsetY
        mTractorOffsetX = tractorOffsetX
        mTractorOffsetY = tractorOffsetY

        registerForBus()

        if (d != null && mImage != null) {
            mImage!!.setImageDrawable(d)
        }

        val bubbleIconSize = resources.getDimensionPixelSize(R.dimen.bubble_icon_size)
        mSnapWidth = bubbleIconSize.toFloat()
        mSnapHeight = bubbleIconSize.toFloat()
        Util.Assert(mSnapWidth > 0 && mSnapHeight > 0 && mSnapWidth == mSnapHeight, "mSnapWidth:$mSnapWidth, mSnapHeight:$mSnapHeight")
        mSnapCircle = Circle(getXPos().toFloat(), getYPos().toFloat(), mSnapWidth * 0.5f)

        val r = getRadius()
        Util.Assert(r > 0.0f, "r:$r")
        mDefaultCircle = Circle(getXPos().toFloat(), getYPos().toFloat(), r)

        when (action) {
            Constant.BubbleAction.ConsumeLeft -> {
                mHomeX = -mSnapWidth.toInt()
                mHomeY = -mSnapHeight.toInt()
            }
            Constant.BubbleAction.ConsumeRight -> {
                mHomeX = Config.mScreenWidth + mSnapWidth.toInt()
                mHomeY = -mSnapHeight.toInt()
            }
            Constant.BubbleAction.Close -> {
                mHomeX = Config.mScreenCenterX //mSnapWidth;
                mHomeY = Config.mScreenHeight + mSnapHeight.toInt()
            }
            else -> {}
        }

        // Add main relative layout to canvasView
        mCanvasLayoutParams.leftMargin = mHomeX
        mCanvasLayoutParams.topMargin = mHomeY
        mCanvasLayoutParams.rightMargin = -100
        mCanvasLayoutParams.bottomMargin = -100
        mCanvasView!!.addView(this, mCanvasLayoutParams)
        visibility = GONE
    }

    fun getOffsetDebugRegion(r: Rect) {
        var xMaxOffset = mMaxOffsetX
        var yMaxOffset = mMaxOffsetY

        if (sEnableTractor) {
            xMaxOffset = mTractorOffsetX
            yMaxOffset = mTractorOffsetY
        }

        val x0 = (0.5f + getXPos() - xMaxOffset - Config.mBubbleWidth * 0.5f).toInt()
        val x1 = (0.5f + getXPos() + xMaxOffset + Config.mBubbleWidth * 0.5f).toInt()

        val y0 = (0.5f + getYPos() - yMaxOffset - Config.mBubbleHeight * 0.5f).toInt()
        val y1 = (0.5f + getYPos() + yMaxOffset + Config.mBubbleHeight * 0.5f).toInt()

        r.left = x0
        r.right = x1
        r.top = y0
        r.bottom = y1
    }

    fun getTractorDebugRegion(r: Rect) {
        val xMaxOffset = mTractorOffsetX
        val yMaxOffset = mTractorOffsetY

        val x0 = (0.5f + getXPos() - xMaxOffset - Config.mBubbleWidth * 0.5f).toInt()
        val x1 = (0.5f + getXPos() + xMaxOffset + Config.mBubbleWidth * 0.5f).toInt()

        val y0 = (0.5f + getYPos() - yMaxOffset - Config.mBubbleHeight * 0.5f).toInt()
        val y1 = (0.5f + getYPos() + yMaxOffset + Config.mBubbleHeight * 0.5f).toInt()

        r.left = x0
        r.right = x1
        r.top = y0
        r.bottom = y1
    }

    fun destroy() {
        unregisterForBus()
    }

    protected open fun registerForBus() {
        EventBus.subscribe(this, MainController.BeginBubbleDragEvent::class.java, ::onBeginBubbleDrag)
        EventBus.subscribe(this, MainController.EndBubbleDragEvent::class.java, ::onEndBubbleDragEvent)
        EventBus.subscribe(this, MainController.DraggableBubbleMovedEvent::class.java, ::onDraggableBubbleMovedEvent)
    }

    protected open fun unregisterForBus() {
        EventBus.unsubscribeAll(this)
    }

    fun shouldSnap(bubbleCircle: Circle, radiusScaler: Float): Boolean {
        if (mTimeSinceSnapping > 0.5f) {
            val snapCircle = GetSnapCircle()

            if (bubbleCircle.Intersects(snapCircle, radiusScaler)) {
                return true
            }
        }

        return false
    }

    open fun beginSnapping() {
        mIsSnapping = true
    }

    open fun endSnapping() {
        mIsSnapping = false
        mTimeSinceSnapping = 0.0f
        setTargetPos(mCanvasLayoutParams.leftMargin, mCanvasLayoutParams.topMargin)
    }

    open fun beginLongHovering() {
        mIsLongHovering = true
    }

    open fun endLongHovering() {
        mIsLongHovering = false
    }

    fun isLongHovering(): Boolean {
        return mIsLongHovering
    }

    fun getAction(): Constant.BubbleAction {
        return mAction
    }

    open fun onBeginBubbleDrag(e: MainController.BeginBubbleDragEvent) {
        postDelayed({
            visibility = VISIBLE
        }, Constant.TARGET_BUBBLE_APPEAR_TIME.toLong())

        mIsSnapping = false
        mTimeSinceSnapping = 1000.0f

        mSnapCircle.mX = (0.5f + getXPos())
        mSnapCircle.mY = Util.clamp(0f, 0.5f + getYPos() + mMaxOffsetY, Config.mScreenHeight - mDefaultCircle.mRadius)

        mDefaultCircle.mX = mSnapCircle.mX
        mDefaultCircle.mY = mSnapCircle.mY

        val x = (0.5f + mDefaultCircle.mX - mDefaultCircle.mRadius).toInt()
        val y = (0.5f + mDefaultCircle.mY - mDefaultCircle.mRadius).toInt()

        setTargetPos(x, y)
        MainController.get()!!.scheduleUpdate()
    }

    open fun onEndBubbleDragEvent(e: MainController.EndBubbleDragEvent) {
        postDelayed({
            visibility = GONE
        }, Constant.TARGET_BUBBLE_APPEAR_TIME.toLong())

        mIsSnapping = false
        setTargetPos(mHomeX, mHomeY)
    }

    open fun onDraggableBubbleMovedEvent(e: MainController.DraggableBubbleMovedEvent) {
    }

    fun update(dt: Float) {
        if (!mIsSnapping) {
            mTimeSinceSnapping += dt
        }
    }

    fun OnOrientationChanged() {
        mSnapCircle.mX = getXPos().toFloat()
        mSnapCircle.mY = getYPos().toFloat()

        mDefaultCircle.mX = mSnapCircle.mX
        mDefaultCircle.mY = mSnapCircle.mY

        when (mAction) {
            Constant.BubbleAction.ConsumeLeft -> {
                mHomeX = -mSnapWidth.toInt()
                mHomeY = -mSnapHeight.toInt()
            }
            Constant.BubbleAction.ConsumeRight -> {
                mHomeX = Config.mScreenWidth + mSnapWidth.toInt()
                mHomeY = -mSnapHeight.toInt()
            }
            Constant.BubbleAction.Close -> {
                mHomeX = Config.mScreenCenterX
                mHomeY = Config.mScreenHeight + mSnapHeight.toInt()
            }
            else -> {}
        }

        mCanvasLayoutParams.leftMargin = mHomeX
        mCanvasLayoutParams.topMargin = mHomeY
        mCanvasView!!.updateViewLayout(this, mCanvasLayoutParams)
    }

    fun GetSnapCircle(): Circle {
        return mSnapCircle
    }

    fun GetDefaultCircle(): Circle {
        return mDefaultCircle
    }

    companion object {
        private var sEnableTractor: Boolean = false

        @JvmStatic
        fun enableTractor() {
            sEnableTractor = true
        }

        @JvmStatic
        fun disableTractor() {
            sEnableTractor = false
        }
    }
}
