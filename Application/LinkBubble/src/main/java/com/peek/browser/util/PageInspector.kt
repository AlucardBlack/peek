/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.util

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Bitmap
import android.os.Handler
import android.util.Log
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.peek.browser.Constant
import com.peek.browser.MainController
import com.peek.browser.Settings
import com.peek.browser.articlerender.ArticleContent
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.net.MalformedURLException
import java.net.URL
import org.json.JSONArray
import org.json.JSONException

class PageInspector(
        private val mContext: Context,
        webView: WebView,
        private val mOnItemFoundListener: OnItemFoundListener?
) {

    private var mScriptCache: String? = null

    private var mWebViewUrl: String? = null
    private val mWebView: WebView = webView
    private val mHandler: Handler = Handler()
    private val mJSEmbedHandler: JSEmbedHandler = JSEmbedHandler()

    private var mTouchIconEntries = arrayOfNulls<TouchIconEntry>(MAX_FAVICON_ENTRIES)
    private var mTouchIconEntryCount = 0
    private var mLastTouchIconResultString: String? = null
    private var sTouchIconTransformation: TouchIconTransformation? = null

    interface OnItemFoundListener {
        fun onTouchIconLoaded(bitmap: Bitmap, pageUrl: String?)
        fun onFetchHtml(html: String)
        fun onThemeColor(color: Int)
    }

    init {
        webView.addJavascriptInterface(mJSEmbedHandler, JS_VARIABLE)
    }

    fun run(webView: WebView) {
        mWebViewUrl = webView.url

        if (mScriptCache == null) {
            var scriptCache = "javascript:(function() {\n"

            if (MainController.get() == null || !MainController.get()!!.hasStableWebViewForSelects(mContext)) {
                scriptCache += getFileContents("SelectElements")
            }

            scriptCache += getFileContents("TouchIcon")

            scriptCache += getFileContents("FetchContent")

            scriptCache += getFileContents("ThemeColor")

            scriptCache += getFileContents("HideBrokenImages")

            mScriptCache = scriptCache
        }

        var scriptToExecute = mScriptCache!!

        scriptToExecute += "}());"

        webView.loadUrl(scriptToExecute)
    }

    fun getFileContents(pageScript: String): String {
        val fullPageScript = "pagescripts/$pageScript.js"
        val assetManager = mContext.resources.assets
        val stringBuilder = StringBuilder()

        try {
            val inputStream = assetManager.open(fullPageScript)

            val reader = BufferedReader(InputStreamReader(inputStream))

            var done = false

            while (!done) {
                val line = reader.readLine()
                done = (line == null)

                if (line != null) {
                    stringBuilder.append(line)
                }
            }

            reader.close()
            inputStream.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }

        return stringBuilder.toString()
    }

    fun reset() {
    }

    private class TouchIconEntry {
        var mRel: String? = null
        var mUrl: URL? = null
        var mSize = 0

        override fun toString(): String {
            return "rel:$mRel, url:${mUrl.toString()}, size:$mSize"
        }
    }

    // For security reasons, all callbacks should be in a self contained class
    private inner class JSEmbedHandler {

        @JavascriptInterface
        fun onTouchIconLinks(string: String?) {
            if (mLastTouchIconResultString != null && mLastTouchIconResultString == string) {
                return
            }
            mLastTouchIconResultString = string

            if (string == null || string.isEmpty()) {
                return
            }

            Log.d(TAG, "onFaviconLinks() - $string")

            mTouchIconEntryCount = 0

            val items = string.split("@@@")
            for (item in items) {
                if (mTouchIconEntryCount == MAX_FAVICON_ENTRIES) {
                    break
                }
                val s = item.replace("###", "")
                if (s.isNotEmpty()) {
                    val vars = s.split(",")
                    if (vars.size < 2) {
                        continue
                    }

                    val rel = vars[0]
                    if (rel == UNKNOWN_TAG) {
                        continue
                    }
                    val href = vars[1]
                    if (href == UNKNOWN_TAG) {
                        continue
                    }
                    var size = -1
                    val sizes = if (vars.size > 2) vars[2] else null
                    if (sizes != null && sizes != UNKNOWN_TAG) {
                        val splitSizes = sizes.split("x")     // specified as 'sizes="128x128"' (http://goo.gl/tGV50j)
                        if (splitSizes.isNotEmpty()) {
                            try {
                                // just pick the first one...
                                size = Integer.valueOf(splitSizes[0])
                            } catch (e: NumberFormatException) {
                                // do nothing...
                            }
                        }
                    }

                    try {
                        var touchIconEntry = mTouchIconEntries[mTouchIconEntryCount]
                        if (touchIconEntry == null) {
                            touchIconEntry = TouchIconEntry()
                            mTouchIconEntries[mTouchIconEntryCount] = touchIconEntry
                        }
                        touchIconEntry.mRel = rel
                        touchIconEntry.mUrl = URL(href)
                        touchIconEntry.mSize = size
                        mTouchIconEntryCount++
                    } catch (e: MalformedURLException) {
                        e.printStackTrace()
                    }
                }
            }

            if (mTouchIconEntryCount > 0) {
                // pick the first one for now
                val touchIconEntry = mTouchIconEntries[0]
                val url = touchIconEntry?.mUrl ?: return
                var transformation = sTouchIconTransformation
                if (transformation == null) {
                    transformation = TouchIconTransformation()
                    sTouchIconTransformation = transformation
                }
                transformation.setListener(mOnItemFoundListener)
                transformation.mTouchIconPageUrl = mWebViewUrl
                val request = ImageRequest.Builder(mContext)
                        .data(url.toString())
                        .transformations(transformation)
                        .build()
                mContext.imageLoader.enqueue(request)
            }

        }

        @JavascriptInterface
        fun onSelectElementInteract(optionString: String?) {
            if (optionString == null || optionString.isEmpty()) {
                return
            }

            Log.d(TAG, "onSelectElementInteract() - $optionString")

            val optionList = ArrayList<String>()
            var selectedIndex = 0
            var optionArray: JSONArray? = null
            try {
                optionArray = JSONArray(optionString)
                val len = optionArray.length()
                var i = 1
                while (i < len) {
                    optionList.add(optionArray.get(i).toString())
                    i += 2
                }
                selectedIndex = Integer.parseInt(optionArray.get(0).toString())
            } catch (e: JSONException) {
                Log.d(TAG, "error parsing json")
            }

            val builder = AlertDialog.Builder(mContext)
            builder.setSingleChoiceItems(optionList.toTypedArray(), selectedIndex, DialogInterface.OnClickListener { dialog, position ->
                Log.d(TAG, "click position is - $position")
                dialog.dismiss()

                mHandler.postDelayed({
                    mWebView.loadUrl("javascript:Peek.selectOption($position)")
                }, 1)
            })
            val dialog = builder.create()
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dialog.show()
        }

        @JavascriptInterface
        fun onThemeColor(string: String?) {
            Log.e("themecolor", "onThemeColor():$string")
            if (string != null) {
                val colorString = string.replace("#", "")
                if (mOnItemFoundListener != null) {
                    try {
                        var color = Integer.parseInt(colorString, 16)
                        color = color or -0x1000000
                        mOnItemFoundListener.onThemeColor(color)
                    } catch (ignored: NumberFormatException) {
                    }
                }
            }
        }

        @JavascriptInterface
        fun fetchHtml(html: String, windowUrl: String) {
            //Log.d(TAG, "fetchHtml() - " + html);

            var fetchPageHtml = false
            try {
                val currentUrl = URL(windowUrl)
                fetchPageHtml = (Settings.get().getArticleModeEnabled() || Settings.get().getArticleModeOnWearEnabled())
                        && ArticleContent.tryForArticleContent(currentUrl)
            } catch (e: MalformedURLException) {
            }

            if (fetchPageHtml && mOnItemFoundListener != null) {
                mOnItemFoundListener.onFetchHtml(html)
            }
        }
    }

    private class TouchIconTransformation : Transformation {

        private var mListener: WeakReference<OnItemFoundListener>? = null
        var mTouchIconPageUrl: String? = null

        fun setListener(listener: OnItemFoundListener?) {
            if (mListener == null || mListener?.get() !== listener) {
                mListener = WeakReference(listener)
            }
        }

        override val cacheKey: String = "faviconTransformation()"

        override suspend fun transform(input: Bitmap, size: Size): Bitmap {
            val w = input.width

            var result = input
            if (w > Constant.TOUCH_ICON_MAX_SIZE) {
                try {
                    result = Bitmap.createScaledBitmap(input, Constant.TOUCH_ICON_MAX_SIZE, Constant.TOUCH_ICON_MAX_SIZE, true)
                } catch (e: OutOfMemoryError) {
                }
            }

            val listener = mListener?.get()
            listener?.onTouchIconLoaded(result, mTouchIconPageUrl)

            return result
        }
    }

    companion object {
        private const val TAG = "PageInspector"

        private const val JS_VARIABLE = "Peek"
        private const val UNKNOWN_TAG = "unknown"        // the tag Chrome/WebView uses for unknown elements

        private const val MAX_FAVICON_ENTRIES = 4
    }
}
