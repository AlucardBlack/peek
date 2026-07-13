/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.peek.browser.R
import java.net.URL

class ProgressIndicator @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    private var mMax: Int = 0
    private var mProgress: Int = 0
    private var mUrl: URL? = null

    private var mProgressArcView: ProgressArcView? = null

    init {
        init()
    }

    private fun init() {
        if (!isInEditMode) {
            mMax = 100
            mProgress = 0

            val progressArcView = ProgressArcView(context)
            mProgressArcView = progressArcView
            val bubbleProgressSize = resources.getDimensionPixelSize(R.dimen.bubble_progress_size)
            val arcLP = LayoutParams(bubbleProgressSize, bubbleProgressSize)
            arcLP.gravity = Gravity.CENTER
            addView(progressArcView, arcLP)
        }
    }

    fun getMax(): Int {
        return mMax
    }

    fun setMax(max: Int) {
        mMax = max
        invalidate()
    }

    fun getUrl(): URL? {
        return mUrl
    }

    fun getProgress(): Int {
        return mProgress
    }

    fun setProgress(progress: Int, url: URL) {
        mUrl = url
        mProgress = progress
        mProgressArcView!!.setProgress(progress, mMax, url)
    }

    fun setColor(color: Int) {
        mProgressArcView!!.setColor(color)
    }

    private class ProgressArcView(context: Context) : View(context) {
        private val mPaint: Paint
        private val mOval: RectF
        private var mProgress: Float = 0f
        private var mUrl: String? = null

        private var mColor: Int = 0
        fun setColor(color: Int) {
            mColor = color
            mPaint.color = mColor
        }

        init {
            val resources = context.resources
            val strokeWidth = resources.getDimensionPixelSize(R.dimen.bubble_progress_stroke)

            mPaint = Paint()
            mPaint.isAntiAlias = true
            mPaint.color = mColor
            mPaint.strokeWidth = strokeWidth.toFloat()

            val size = resources.getDimensionPixelSize(R.dimen.bubble_progress_size) - strokeWidth
            val offset = strokeWidth / 2f
            mOval = RectF(offset, offset, size + offset, size + offset)

            mPaint.style = Paint.Style.STROKE
        }

        fun setProgress(progress: Int, maxProgress: Int, url: URL) {
            val progressN = progress.toFloat() / maxProgress.toFloat()
            val urlAsString = url.toString()

            // If the url is the same, and currently we're at 100%, and this progress is < 100%,
            // don't change the visual arc as it just looks messy.
            if (progress != 0 && mProgress >= .999f && progressN < .999f && mUrl == urlAsString) {
                return
            }

            mUrl = urlAsString

            mProgress = progressN
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val sweep = 360f * mProgress
            canvas.drawArc(mOval, -90f, sweep, false, mPaint)
        }
    }
}
