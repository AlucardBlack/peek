/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.util

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Browser
import android.text.SpannableString
import android.text.style.URLSpan
import android.view.View
import android.widget.TextView

class SafeUrlSpan(url: String) : URLSpan(url) {

    override fun onClick(widget: View) {
        try {
            val uri = Uri.parse(url)
            val context = widget.context
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.putExtra(Browser.EXTRA_APPLICATION_ID, context.packageName)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
        }
    }

    companion object {
        @JvmStatic
        fun fixUrlSpans(tv: TextView) {
            val current = tv.text as SpannableString
            val spans = current.getSpans(0, current.length, URLSpan::class.java)

            for (span in spans) {
                val start = current.getSpanStart(span)
                val end = current.getSpanEnd(span)

                current.removeSpan(span)
                current.setSpan(SafeUrlSpan(span.url), start, end, 0)
            }
        }
    }
}
