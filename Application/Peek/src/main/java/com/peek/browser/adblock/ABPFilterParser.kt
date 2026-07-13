/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.adblock

import android.content.Context
import com.peek.browser.R
import java.nio.charset.StandardCharsets

/**
 * Created by bbondy on 2015-10-13.
 *
 * Wrapper for native library
 */
class ABPFilterParser(context: Context) {

    private val mBuffer: ByteArray?

    init {
        // One time load and parse of the raw EasyList text filter list (ad blocking).
        val verNumber = ADBlockUtils.getDataVerNumber(context.getString(R.string.adblock_url))
        mBuffer = ADBlockUtils.readData(context, context.getString(R.string.adblock_localfilename),
                context.getString(R.string.adblock_url), ETAG_PREPEND, verNumber, false)
        if (mBuffer != null) {
            parseList(String(mBuffer, StandardCharsets.UTF_8))
        }

        // EasyPrivacy is the same ABP filter syntax as EasyList, just targeting
        // trackers/analytics instead of ads - parse() is additive (see native
        // ABPFilterParser::parse()), so this simply adds tracking-protection
        // coverage to the same engine and the same "Ad Block" toggle, rather than
        // needing a separate parser/hashset/setting.
        val privacyVerNumber = ADBlockUtils.getDataVerNumber(context.getString(R.string.adprivacy_url))
        val privacyBuffer = ADBlockUtils.readData(context, context.getString(R.string.adprivacy_localfilename),
                context.getString(R.string.adprivacy_url), PRIVACY_ETAG_PREPEND, privacyVerNumber, false)
        if (privacyBuffer != null) {
            parseList(String(privacyBuffer, StandardCharsets.UTF_8))
        }
    }

    fun shouldBlockJava(baseHost: String, url: String, filterOption: String): Boolean {
        if (null == mBuffer) {
            return false
        }

        return shouldBlock(baseHost, url, filterOption)
    }

    external fun parseList(data: String)
    external fun shouldBlock(baseHost: String, url: String, filterOption: String): Boolean

    companion object {
        init {
            System.loadLibrary("Peek")
        }

        private const val ETAG_PREPEND = "abp"
        private const val PRIVACY_ETAG_PREPEND = "abp_privacy"
    }
}
