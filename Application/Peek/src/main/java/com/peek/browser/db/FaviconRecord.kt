/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.db

import android.graphics.Bitmap

class FaviconRecord {

    private var mId = 0
    private var mUrl: String? = null
    private var mPageUrl: String? = null
    private var mFavicon: Bitmap? = null
    private var mTime: Long = 0

    constructor()

    constructor(url: String?, pageUrl: String?, favicon: Bitmap?, time: Long) : super() {
        mUrl = url
        mPageUrl = pageUrl
        mFavicon = favicon
        mTime = time
    }

    fun getId(): Int {
        return mId
    }

    fun setId(id: Int) {
        mId = id
    }

    fun getUrl(): String? {
        return mUrl
    }

    fun setUrl(url: String?) {
        mUrl = url
    }

    fun getPageUrl(): String? {
        return mPageUrl
    }

    fun setPageUrl(pageUrl: String?) {
        mPageUrl = pageUrl
    }

    fun getFavicon(): Bitmap? {
        return mFavicon
    }

    fun setFavicon(bitmap: Bitmap?) {
        mFavicon = bitmap
    }

    fun getTime(): Long {
        return mTime
    }

    fun setTime(time: Long) {
        mTime = time
    }

    override fun toString(): String {
        return "HistoryRecord [id=$mId, mUrl=$mUrl, mPageUrl=$mPageUrl]"
    }
}
