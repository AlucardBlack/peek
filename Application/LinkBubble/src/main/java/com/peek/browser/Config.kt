/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser

import android.content.Context
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.WindowManager

object Config {

    @JvmField
    var mDm = DisplayMetrics()

    @JvmField var mScreenCenterX = 0
    @JvmField var mScreenHeight = 0
    @JvmField var mScreenWidth = 0

    @JvmField var mBubbleSnapLeftX = 0
    @JvmField var mBubbleSnapRightX = 0
    @JvmField var mBubbleMinY = 0
    @JvmField var mBubbleMaxY = 0

    @JvmField var mBubbleWidth = 0f
    @JvmField var mBubbleHeight = 0f

    @JvmField var mContentViewBubbleY = 0
    @JvmField var mContentViewBubbleX = 0

    @JvmField var mContentOffset = 0

    @JvmField var sDensityDpi = 0

    @JvmField var sIsTablet = false

    const val ANIMATE_TO_SNAP_TIME = 0.1f
    const val CLOSE_ALL_BUBBLES_DELAY = 0.67f

    @JvmStatic
    fun init(context: Context) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.getMetrics(mDm)

        mBubbleWidth = context.resources.getDimensionPixelSize(R.dimen.bubble_size).toFloat()
        mBubbleHeight = mBubbleWidth

        mScreenCenterX = (mDm.widthPixels * 0.5f).toInt()
        mScreenHeight = mDm.heightPixels - getStatusBarHeight(context)
        mScreenWidth = mDm.widthPixels

        mBubbleSnapLeftX = (-mBubbleWidth * 0.2f).toInt()
        mBubbleSnapRightX = (mDm.widthPixels - mBubbleWidth * 0.8f).toInt()
        mBubbleMinY = 0 //(mContentOffset + mBubbleHeight * 0.15f);
        mBubbleMaxY = (mDm.heightPixels - mBubbleHeight).toInt() //(mDm.heightPixels - 1.15f * mBubbleHeight);

        mContentViewBubbleX = (mDm.widthPixels - mBubbleWidth - mBubbleWidth * 0.5f).toInt()
        mContentViewBubbleY = context.resources.getDimensionPixelSize(R.dimen.content_bubble_y_offset)

        mContentOffset = context.resources.getDimensionPixelSize(R.dimen.content_offset)

        sDensityDpi = mDm.densityDpi

        sIsTablet = context.resources.getBoolean(R.bool.is_tablet)
    }

    @JvmStatic
    fun getStatusBarHeight(context: Context): Int {
        var result = 33        // Guess 33 if we can't find the resource as this is what the value is on a N7.
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = context.resources.getDimensionPixelSize(resourceId)
        }
        return result
    }

    @JvmStatic
    fun getContentViewX(bubbleIndex: Int, bubbleCount: Int): Float {
        val spaceUsed = bubbleCount * mBubbleWidth + (bubbleCount - 1) * mBubbleWidth * 0.2f
        val x0 = mScreenCenterX - spaceUsed * 0.5f
        return x0 + bubbleIndex * mBubbleWidth * 1.2f

        /*
        if (bubbleIndex == 0) {
            return Config.mScreenCenterX;
        } else if ((bubbleIndex & 1) == 0) {
            return Config.mScreenCenterX + (bubbleIndex/2) * Config.mBubbleWidth * 1.2f;
        } else {
            return Config.mScreenCenterX - (1+bubbleIndex/2) * Config.mBubbleWidth * 1.2f;
        }*/
    }

    @JvmStatic
    fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, mDm).toInt()
    }

    const val SET_DEFAULT_BROWSER_URL = "https://example.com"

    const val YOUTUBE_WATCH_PREFIX = "http://www.youtube.com/watch?v="
    const val YOUTUBE_EMBED_PATH_SUFFIX = "embed/"
    const val YOUTUBE_EMBED_PREFIX = "//www.youtube.com/" + YOUTUBE_EMBED_PATH_SUFFIX
    const val YOUTUBE_API_THUMBNAILS_LOW_QUALITY = "thumbnails(default)"
    const val YOUTUBE_API_THUMBNAILS_HIGH_QUALITY = "thumbnails(default,medium)"

    private var sMaxMemory: Long = -1

    @JvmStatic
    fun isLowMemoryDevice(): Boolean {
        if (sMaxMemory == -1L) {
            sMaxMemory = Runtime.getRuntime().maxMemory()
            Log.d("Peek", "maxMemory=" + sMaxMemory / 1024 / 1024 + "MB")
        }

        return sMaxMemory <= 32 * 1024 * 1024
    }
}
