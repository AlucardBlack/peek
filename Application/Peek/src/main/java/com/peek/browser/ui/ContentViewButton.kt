/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import androidx.core.graphics.drawable.DrawableCompat
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.peek.browser.R
import com.peek.browser.Settings

open class ContentViewButton @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    @JvmField
    var mIsTouched: Boolean = false
    private var mMaxIconSize: Int = 0
    private val mImageView: ImageView
    private val mRippleDrawable = RippleDrawable(ColorStateList.valueOf(sTouchedColor), null, null)

    private val mButtonOnTouchListener = OnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                setIsTouched(true)
            }
            MotionEvent.ACTION_UP -> {
                setIsTouched(false)
            }
        }
        false
    }

    init {
        setOnTouchListener(mButtonOnTouchListener)

        mImageView = ImageView(context)
        mImageView.scaleType = ImageView.ScaleType.CENTER
        addView(mImageView)

        // Real ripple instead of an instant flat-color overlay swap.
        background = mRippleDrawable
    }

    fun setIsTouched(isTouched: Boolean) {
        if (mIsTouched != isTouched) {
            // Drive the ripple's own drawable state directly rather than View.isPressed -
            // setting isPressed from an OnTouchListener (which runs before the view's own
            // onTouchEvent) fights the framework's click-detection state machine and can
            // silently swallow the click on ACTION_UP.
            mRippleDrawable.state = if (isTouched)
                intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled)
            else
                intArrayOf()
        }

        mIsTouched = isTouched
    }

    fun getMaxIconSize(): Int {
        if (mMaxIconSize == 0) {
            mMaxIconSize = resources.getDimensionPixelSize(R.dimen.content_view_button_max_height)
        }
        return mMaxIconSize
    }

    fun setImageDrawable(drawableIn: Drawable?) {
        var drawable = drawableIn

        if (drawable is BitmapDrawable) {
            val maxIconSize = getMaxIconSize()

            val bitmapDrawable = drawable
            val width = bitmapDrawable.bitmap.width
            val height = bitmapDrawable.bitmap.height
            if (width > 0 && height > 0 && (width > maxIconSize || height > maxIconSize)) {
                var newHeight: Int
                var newWidth: Int
                if (width > height) {
                    newWidth = maxIconSize
                    newHeight = ((height / width) * maxIconSize)
                } else if (width < height) {
                    newHeight = maxIconSize
                    newWidth = ((width / height) * maxIconSize)
                    if (0 == newWidth) {
                        newWidth = newHeight
                    }
                } else {
                    newHeight = maxIconSize
                    newWidth = maxIconSize
                }

                // Potential fix for user exceptions below saying that width and height must be > 0
                newWidth = Math.max(1, newWidth)
                newHeight = Math.max(1, newHeight)

                try {
                    val resizedBitmap = Bitmap.createScaledBitmap(bitmapDrawable.bitmap, newWidth, newHeight, true)
                    drawable = BitmapDrawable(resources, resizedBitmap)
                } catch (ex: OutOfMemoryError) {
                }
            }
        }

        mImageView.setImageDrawable(drawable)
    }

    fun updateTheme(color: Int?) {
        val d = mImageView.drawable
        if (d != null) {
            val textColor: Int
            if (color == null || !Settings.get().getThemeToolbar()) {
                textColor = Settings.get().getThemedTextColor()
            } else {
                textColor = Settings.COLOR_WHITE
            }
            DrawableCompat.setTint(d, textColor)
        }
    }

    companion object {
        const val sTouchedColor = 0x555d5d5e
    }
}
