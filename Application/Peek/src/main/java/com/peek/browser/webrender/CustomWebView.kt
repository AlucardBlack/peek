/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.webrender

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView

class CustomWebView : WebView {
    private var mOnScrollChangedCallback: OnScrollChangedCallback? = null
    @JvmField
    var mInterceptScrollChangeCalls = false

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    override fun onScrollChanged(newX: Int, newY: Int, oldX: Int, oldY: Int) {
        super.onScrollChanged(newX, newY, oldX, oldY)
        //Log.d("My", "newX = " + newX + ", newY = " + newY + ", oldX = " + oldX + ", oldY = " + oldY);
        if ((mInterceptScrollChangeCalls || 0 == newY) && mOnScrollChangedCallback != null) {
            mOnScrollChangedCallback!!.onScroll(newY, oldY)
        }
    }

    fun setOnScrollChangedCallback(onScrollChangedCallback: OnScrollChangedCallback) {
        mOnScrollChangedCallback = onScrollChangedCallback
    }

    interface OnScrollChangedCallback {
        fun onScroll(newY: Int, oldY: Int)
    }
}
