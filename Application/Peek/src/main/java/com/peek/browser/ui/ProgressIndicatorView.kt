/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import com.peek.browser.R
import com.peek.browser.Settings
import java.net.URL

class ProgressIndicatorView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : ImageView(context, attrs, defStyle) {

    private lateinit var mProgressDrawable: ProgressIndicatorDrawable
    private var mUrl: URL? = null
    private val mMaxProgress = 100f

    init {
        if (!isInEditMode) {
            mProgressDrawable = ProgressIndicatorDrawable(Settings.get().getThemedDefaultProgressColor(),
                    resources.getDimensionPixelSize(R.dimen.bubble_progress_size).toFloat(),
                    resources.getDimensionPixelSize(R.dimen.bubble_progress_stroke).toFloat())
            setImageDrawable(mProgressDrawable)
        }
    }

    fun setColor(rgb: Int) {
        mProgressDrawable.setColor(rgb)
    }

    fun getProgress(): Int {
        return (mProgressDrawable.getProgress() * 100).toInt()
    }

    fun setProgress(progress: Int, url: URL) {
        val progressN = progress / mMaxProgress
        val currentProgress = mProgressDrawable.getProgress()

        // If the url is the same, and currently we're at 100%, and this progress is < 100%,
        // don't change the visual arc as it just looks messy.
        if (progress != 0 && currentProgress >= .999f
                && progressN < .999f
                && (mUrl != null && mUrl.toString() == url.toString())) {
            return
        }

        if (mUrl == null || mUrl!!.host.equals(url.host) == false) {
            // ensure color is set back to default when the url host changes
            mProgressDrawable.setColor(null)
        }

        mUrl = url
        mProgressDrawable.setProgress(progress)
    }
}
