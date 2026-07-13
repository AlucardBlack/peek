/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.animation.Animator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.peek.browser.Config
import com.peek.browser.MainController
import com.peek.browser.R
import java.util.Locale

class CloseTabTargetView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : BubbleTargetView(context, attrs, defStyle) {

    private var mCloseAllView: CloseAllView? = null
    private val mInterpolator = LinearInterpolator()

    override fun onFinishInflate() {
        super.onFinishInflate()

        mCloseAllView = findViewById(R.id.close_all_view)
        mCloseAllView!!.visibility = View.INVISIBLE
    }

    override fun getRadius(): Float {
        val closeTabSize = resources.getDimensionPixelSize(R.dimen.close_tab_target_size)
        return closeTabSize * 0.5f
    }

    override fun beginLongHovering() {
        super.beginLongHovering()

        mCloseAllView!!.alpha = 0f
        mCloseAllView!!.visibility = View.VISIBLE
        mCloseAllView!!.scaleX = MIN_SCALE
        mCloseAllView!!.scaleY = MIN_SCALE
        mCloseAllView!!.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(ANIM_DURATION.toLong())
                .setInterpolator(mInterpolator)
                .setListener(null)
    }

    override fun endLongHovering() {
        mCloseAllView!!.animate()
                .alpha(0f)
                .scaleX(MIN_SCALE)
                .scaleY(MIN_SCALE)
                .setDuration(ANIM_DURATION.toLong())
                .setInterpolator(mInterpolator)
                .setListener(mHideCloseAllViewListener)

        super.endLongHovering()
    }

    private val mHideCloseAllViewListener = object : Animator.AnimatorListener {
        override fun onAnimationStart(animation: Animator) {
        }

        override fun onAnimationEnd(animation: Animator) {
            mCloseAllView!!.visibility = View.INVISIBLE
        }

        override fun onAnimationCancel(animation: Animator) {

        }

        override fun onAnimationRepeat(animation: Animator) {

        }
    }

    override fun onBeginBubbleDrag(e: MainController.BeginBubbleDragEvent) {
        super.onBeginBubbleDrag(e)
    }

    override fun onEndBubbleDragEvent(e: MainController.EndBubbleDragEvent) {
        super.onEndBubbleDragEvent(e)
    }

    override fun onDraggableBubbleMovedEvent(e: MainController.DraggableBubbleMovedEvent) {
        super.onDraggableBubbleMovedEvent(e)
    }

    class CloseAllView @JvmOverloads constructor(
            context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
    ) : View(context, attrs, defStyle) {

        private val mPath: Path
        private val mPaint: Paint
        private val mText: String

        init {
            mPaint = Paint()
            mPaint.isAntiAlias = true
            mPaint.textSize = Config.dpToPx(12f).toFloat()
            mPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            mPaint.textAlign = Paint.Align.CENTER
            mPaint.color = Color.WHITE

            val textCircleSize = resources.getDimensionPixelSize(R.dimen.close_tab_text_circle_size)
            val closeTabSize = resources.getDimensionPixelSize(R.dimen.close_tab_target_size)
            val start = (closeTabSize - textCircleSize) / 2

            val circle = RectF()
            circle.set(start.toFloat(), start.toFloat(), (start + textCircleSize).toFloat(), (start + textCircleSize).toFloat())

            mPath = Path()
            mPath.addArc(circle, 180f, 180f)

            mText = resources.getString(R.string.action_close_all).uppercase(Locale.getDefault())
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            canvas.drawTextOnPath(mText, mPath, 0f, 0f, mPaint)
        }
    }

    companion object {
        private const val ANIM_DURATION = 100
        private const val MIN_SCALE = .7f
    }
}
