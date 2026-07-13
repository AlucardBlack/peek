/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.ImageView
import androidx.palette.graphics.Palette
import com.peek.browser.Constant
import com.peek.browser.R
import org.mozilla.gecko.favicons.Favicons

/**
 * Special version of ImageView for favicons.
 * Displays solid colour background around Favicon to fill space not occupied by the icon. Colour
 * selected is the dominant colour of the provided Favicon.
 */
class FaviconView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : ImageView(context, attrs, defStyle) {

    interface OnPaletteChangeListener {
        fun onPaletteChange(palette: Palette)
    }

    private var mIconBitmap: Bitmap? = null

    // Reference to the unscaled bitmap, if any, to prevent repeated assignments of the same bitmap
    // to the view from causing repeated rescalings (Some of the callers do this)
    private var mUnscaledBitmap: Bitmap? = null

    // Key into the Favicon dominant colour cache. Should be the Favicon URL if the image displayed
    // here is a Favicon managed by the caching system. If not, any appropriately unique-to-this-image
    // string is acceptable.
    private var mIconKey: String? = null

    private var mActualWidth = 0
    private var mActualHeight = 0

    // Flag indicating if the most recently assigned image is considered likely to need scaling.
    private var mScalingExpected = false

    // Dominant color of the favicon.
    private var mDominantColor = 0

    // If true, add a border around the image. If false, draw the bitmap as is.
    private var mDrawOutline = false

    // Size of the stroke rectangle.
    private val mStrokeRect: RectF = RectF()

    // Size of the background rectangle.
    private val mBackgroundRect: RectF = RectF()

    @JvmField
    var mFavicons: Favicons? = null

    private var mOnPaletteChangeListener: OnPaletteChangeListener? = null

    init {
        scaleType = ScaleType.CENTER

        if (sStrokeWidth == 0f) {
            sStrokeWidth = 1f//getResources().getDisplayMetrics().density;
            sStrokePaint.strokeWidth = sStrokeWidth
        }

        mStrokeRect.left = sStrokeWidth
        mStrokeRect.top = sStrokeWidth
        mBackgroundRect.left = sStrokeWidth * 2.0f
        mBackgroundRect.top = sStrokeWidth * 2.0f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        // No point rechecking the image if there hasn't really been any change.
        if (w == mActualWidth && h == mActualHeight) {
            return
        }

        mActualWidth = w
        mActualHeight = h

        mStrokeRect.right = w - sStrokeWidth
        mStrokeRect.bottom = h - sStrokeWidth
        mBackgroundRect.right = mStrokeRect.right - sStrokeWidth
        mBackgroundRect.bottom = mStrokeRect.bottom - sStrokeWidth

        formatImage()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!mDrawOutline) {
            return
        }

        // 27.5% transparent dominant color.
        sBackgroundPaint.color = mDominantColor and 0x46FFFFFF
        canvas.drawRect(mStrokeRect, sBackgroundPaint)

        sStrokePaint.color = mDominantColor
        canvas.drawRect(mStrokeRect, sStrokePaint)
    }

    /**
     * Formats the image for display, if the prerequisite data are available. Upscales tiny Favicons to
     * normal sized ones, replaces null bitmaps with the default Favicon, and fills all remaining space
     * in this view with the coloured background.
     */
    private fun formatImage() {
        if (isInEditMode || !mDrawOutline) {
            return
        }

        val favicons = mFavicons ?: throw RuntimeException("Must set mFavicons")

        val iconBitmap = mIconBitmap
        // If we're called before bitmap is set, or before size is set, show blank.
        if (iconBitmap == null || mActualWidth == 0 || mActualHeight == 0) {
            showNoImage()
            return
        }

        if (mScalingExpected && mActualWidth != iconBitmap.width) {
            scaleBitmap()
            // Don't scale the image every time something changes.
            mScalingExpected = false
        }

        setImageBitmap(iconBitmap)

        // After scaling, determine if we have empty space around the scaled image which we need to
        // fill with the coloured background. If applicable, show it.
        // We assume Favicons are still squares and only bother with the background if more than 3px
        // of it would be displayed.
        if (Math.abs(iconBitmap.width - mActualWidth) > 3) {
            mDominantColor = favicons.getFaviconColor(mIconKey)
            if (mDominantColor == -1) {
                mDominantColor = 0
            }
        } else {
            mDominantColor = 0
        }
    }

    private fun scaleBitmap() {
        // If the Favicon can be resized to fill the view exactly without an enlargment of more than
        // a factor of two, do so.
        /*
        int doubledSize = mIconBitmap.getWidth()*2;
        if (mActualWidth > doubledSize) {
            // If the view is more than twice the size of the image, just double the image size
            // and do the rest with padding.
            mIconBitmap = Bitmap.createScaledBitmap(mIconBitmap, doubledSize, doubledSize, true);
        } else {
            // Otherwise, scale the image to fill the view.
            mIconBitmap = Bitmap.createScaledBitmap(mIconBitmap, mActualWidth, mActualWidth, true);
        }*/
    }

    /**
     * Sets the icon displayed in this Favicon view to the bitmap provided. If the size of the view
     * has been set, the display will be updated right away, otherwise the update will be deferred
     * until then. The key provided is used to cache the result of the calculation of the dominant
     * colour of the provided image - this value is used to draw the coloured background in this view
     * if the icon is not large enough to fill it.
     *
     * @param bitmap favicon image
     * @param key string used as a key to cache the dominant color of this image
     * @param allowScaling If true, allows the provided bitmap to be scaled by this FaviconView.
     *                     Typically, you should prefer using Favicons obtained via the caching system
     *                     (Favicons class), so as to exploit caching.
     */
    private fun updateImageInternal(bitmap: Bitmap?, key: String?, allowScaling: Boolean) {
        if (bitmap == null) {
            showDefaultFavicon()
            return
        }

        // Reassigning the same bitmap? Don't bother.
        if (mUnscaledBitmap === bitmap && mIconKey === key || !mDrawOutline) {
            return
        }
        mUnscaledBitmap = bitmap
        mIconBitmap = bitmap
        mIconKey = key
        mScalingExpected = allowScaling

        // Possibly update the display.
        formatImage()
    }

    fun showDefaultFavicon() {
        mDrawOutline = false
        scaleType = ScaleType.CENTER_INSIDE
        setImageResource(R.drawable.fallback_favicon)
        mDominantColor = 0
    }

    private fun showNoImage() {
        setImageDrawable(null)
        mDominantColor = 0
    }

    /**
     * Clear image and background shown by this view.
     */
    fun clearImage() {
        showNoImage()
        mUnscaledBitmap = null
        mIconBitmap = null
        mIconKey = null
        mScalingExpected = false
    }

    /**
     * Update the displayed image and apply the scaling logic.
     * The scaling logic will attempt to resize the image to fit correctly inside the view in a way
     * that avoids unreasonable levels of loss of quality.
     * Scaling is necessary only when the icon being provided is not drawn from the Favicon cache
     * introduced in Bug 914296.
     *
     * Due to Bug 913746, icons bundled for search engines are not available to the cache, so must
     * always have the scaling logic applied here. At the time of writing, this is the only case in
     * which the scaling logic here is applied.
     *
     * @param bitmap The bitmap to display in this favicon view.
     * @param key The key to use into the dominant colours cache when selecting a background colour.
     */
    /*
    public void updateAndScaleImage(Bitmap bitmap, String key) {
        updateImageInternal(bitmap, key, true);
    }*/

    /**
     * Update the image displayed in the Favicon view without scaling. Images larger than the view
     * will be centrally cropped. Images smaller than the view will be placed centrally and the
     * extra space filled with the dominant colour of the provided image.
     *
     * @param bitmap The bitmap to display in this favicon view.
     * @param key The key to use into the dominant colours cache when selecting a background colour.
     */
    /*
    public void updateImage(Bitmap bitmap, String key) {
        updateImageInternal(bitmap, key, false);
    }*/

    /**
     *
     * @param bitmap
     * @param key
     */
    fun updateImage(bitmap: Bitmap, key: String?, updatePalette: Boolean) {
        if (bitmap.width < Constant.DESIRED_FAVICON_SIZE) {
            mDrawOutline = true
            scaleType = ScaleType.CENTER
            updateImageInternal(bitmap, key, true)
        } else {
            mDrawOutline = false
            mDominantColor = 0
            mIconKey = null
            scaleType = ScaleType.CENTER_INSIDE
            setImageBitmap(bitmap)
        }
        if (updatePalette) {
            Palette.generateAsync(bitmap, Palette.PaletteAsyncListener { palette ->
                mOnPaletteChangeListener?.onPaletteChange(palette!!)
            })
        }
    }

    fun getBitmap(): Bitmap? {
        return mIconBitmap
    }

    fun setOnPaletteChangeListener(onPaletteChangeListener: OnPaletteChangeListener?) {
        mOnPaletteChangeListener = onPaletteChangeListener
    }

    companion object {
        // Stroke width for the border.
        private var sStrokeWidth: Float = 0f

        // Paint for drawing the stroke.
        private val sStrokePaint: Paint

        // Paint for drawing the background.
        private val sBackgroundPaint: Paint

        // Initializing the static paints.
        init {
            sStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
            sStrokePaint.style = Paint.Style.STROKE

            sBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            sBackgroundPaint.style = Paint.Style.FILL
        }
    }
}
