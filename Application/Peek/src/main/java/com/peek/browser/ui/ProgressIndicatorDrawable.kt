/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.peek.browser.Settings

class ProgressIndicatorDrawable(color: Int, width: Float, borderWidth: Float) : Drawable() {

    private val mBounds = RectF()
    private val mPaint: Paint

    private var mBorderWidth: Float = borderWidth
    private var mWidth: Float = width
    private var mColor: Int = color
    private var mProgress: Float = 0f

    init {
        mPaint = Paint()
        mPaint.isAntiAlias = true
        mPaint.style = Paint.Style.STROKE
        mPaint.strokeWidth = borderWidth
        mPaint.strokeCap = Paint.Cap.ROUND
        mPaint.color = color
    }

    override fun draw(canvas: Canvas) {
        mPaint.color = 0x77000000 + mColor
        canvas.drawArc(mBounds, 0f, 360f, false, mPaint)

        val sweep = 360f * mProgress
        mPaint.color = mColor
        canvas.drawArc(mBounds, -90f, sweep, false, mPaint)
    }

    override fun setAlpha(alpha: Int) {
        mPaint.alpha = alpha
    }

    override fun setColorFilter(cf: ColorFilter?) {
        mPaint.colorFilter = cf
    }

    override fun getOpacity(): Int {
        return PixelFormat.TRANSPARENT
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)

        val xOffset = (bounds.right.toFloat() - mWidth) / 2f
        val yOffset = (bounds.bottom.toFloat() - mWidth) / 2f

        mBounds.left = xOffset + mBorderWidth / 2f
        mBounds.right = xOffset + mWidth - mBorderWidth / 2f
        mBounds.top = yOffset + mBorderWidth / 2f
        mBounds.bottom = yOffset + mWidth - mBorderWidth / 2f
    }

    fun setColor(rgbIn: Int?) {
        var rgb = rgbIn
        if (rgb == null || Settings.get().getColoredProgressIndicator() == false) {
            rgb = Settings.get().getThemedDefaultProgressColor()
        }
        //Log.d("blerg", "setColor():" + rgb);

        mColor = rgb
        mPaint.color = rgb
        invalidateSelf()
    }

    fun setProgress(progress: Int) {
        mProgress = progress / 100f
        invalidateSelf()
    }

    fun getProgress(): Float {
        return mProgress
    }
}
