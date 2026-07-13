/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.webrender

import android.content.Context
import android.os.Looper
import android.util.Log

/**
 * Keeps a single blank CustomWebView pre-created so opening a new tab doesn't pay
 * WebView construction on the tap-critical path (the first WebView in the process
 * additionally pays Chromium startup, typically 100-400ms on the main thread).
 *
 * Main-thread only. The warm instance is created from an idle handler so warming
 * never competes with the tap or animation work itself, and it holds a reference
 * to the service context, so MainController.destroy() must call shutdown().
 */
object WebViewPreloader {
    private const val TAG = "WebViewPreloader"

    private var mWebView: CustomWebView? = null
    private var mBaseContext: Context? = null
    private var mPreloadPending = false

    fun preload(context: Context) {
        if (mPreloadPending || (mWebView != null && mBaseContext === context)) {
            return
        }
        mPreloadPending = true
        Looper.myQueue().addIdleHandler {
            mPreloadPending = false
            if (mBaseContext !== context) {
                mWebView?.destroy()
                mWebView = null
            }
            if (mWebView == null) {
                mWebView = CustomWebView(WebRendererContextWrapper(context))
                mBaseContext = context
            }
            false
        }
    }

    /**
     * Returns the warm instance, or null if none exists or it was created for a
     * different base context (in which case it is destroyed rather than reused).
     */
    fun obtain(context: Context): CustomWebView? {
        val webView = mWebView ?: return null
        val base = mBaseContext
        mWebView = null
        mBaseContext = null
        if (base !== context) {
            webView.destroy()
            Log.d(TAG, "obtain: context mismatch, discarded warm WebView")
            return null
        }
        Log.d(TAG, "obtain: HIT")
        return webView
    }

    fun shutdown() {
        mWebView?.destroy()
        mWebView = null
        mBaseContext = null
    }
}
