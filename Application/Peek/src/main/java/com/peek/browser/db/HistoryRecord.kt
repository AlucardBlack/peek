/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.db

class HistoryRecord {

    private var mId = 0
    private var mUrl: String? = null
    private var mHost: String? = null
    private var mTitle: String? = null
    private var mTime: Long = 0

    constructor()

    constructor(title: String?, url: String?, host: String?, time: Long) : super() {
        mTitle = title
        mHost = host
        mUrl = url
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

    fun getHost(): String? {
        return mHost
    }

    fun setHost(host: String?) {
        mHost = host
    }

    fun getTitle(): String? {
        return mTitle
    }

    fun setTitle(title: String?) {
        mTitle = title
    }

    fun getTime(): Long {
        return mTime
    }

    fun setTime(time: Long) {
        mTime = time
    }

    override fun toString(): String {
        return "HistoryRecord [id=$mId, title=$mTitle, url=$mUrl]"
    }

    class ChangedEvent(@JvmField val mHistoryRecord: HistoryRecord)
}
