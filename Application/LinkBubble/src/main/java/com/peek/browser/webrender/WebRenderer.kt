/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.webrender

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.webkit.WebView
import com.peek.browser.articlerender.ArticleContent
import java.net.MalformedURLException
import java.net.URL

abstract class WebRenderer(context: Context, controller: WebRenderer.Controller, webRendererPlaceholder: View) {

    interface Controller {
        fun resetBubblePanelAdjustment()
        fun adjustBubblesPanel(newY: Int, oldY: Int, afterTouchAdjust: Boolean)
        fun shouldAdBlockUrl(baseHost: String, urlStr: String, filterOption: String): Boolean
        fun shouldOverrideUrlLoading(urlAsString: String, viaUserInput: Boolean): Boolean
        fun doUpdateVisitedHistory(url: String, isReload: Boolean, unknownClick: Boolean)
        fun onLoadUrl(urlAsString: String)      // may or may not be called
        fun onReceivedError()
        fun onPageStarted(urlAsString: String, favIcon: Bitmap?)
        fun onPageFinished(urlAsString: String)
        fun onDownloadStart(urlAsString: String)
        fun onReceivedTitle(url: String?, title: String)
        fun onReceivedIcon(bitmap: Bitmap)
        fun onProgressChanged(progress: Int, urlAsString: String?)
        fun onBackPressed(): Boolean
        fun onUrlLongClick(webView: WebView, url: String, type: Int)
        fun onShowBrowserPrompt()
        fun onCloseWindow()
        fun onPageInspectorTouchIconLoaded(bitmap: Bitmap, pageUrl: String?)
        fun onPageInspectorDropDownWarningClick()
        fun onPagedInspectorThemeColorFound(color: Int)
        fun onArticleContentReady(articleContent: ArticleContent?)
    }

    enum class Type {
        Stub,
        WebView,
    }

    enum class Mode {
        Web,
        Article,
    }

    @JvmField
    protected var mMode: Mode? = null

    @JvmField
    protected val mController: Controller = controller

    @JvmField
    protected var mUrl: URL? = null

    @JvmField
    val mContext: WebRendererContextWrapper = WebRendererContextWrapper(context)

    abstract fun destroy()

    abstract fun getView(): View?

    abstract fun updateIncognitoMode(incognito: Boolean)

    abstract fun loadUrl(url: URL, mode: Mode)

    abstract fun reload()

    abstract fun stopLoading()

    abstract fun hidePopups()

    abstract fun resetPageInspector()

    open fun runPageInspector() {}

    abstract fun getUserAgentString(context: Context): String?

    abstract fun setUserAgentString(userAgentString: String)

    abstract fun resumeOnSetActive()

    abstract fun pauseOnSetInactive()

    open fun onPageLoadComplete() {}

    fun getUrl(): URL? {
        return mUrl
    }

    @Throws(MalformedURLException::class)
    fun setUrl(urlAsString: String) {
        mUrl = URL(urlAsString)
    }

    fun setUrl(url: URL) {
        mUrl = url
    }

    fun getMode(): Mode? {
        return mMode
    }

    open val articleContent: ArticleContent?
        get() = null

    companion object {
        @JvmStatic
        fun create(type: Type, context: Context, controller: Controller, webRendererPlaceholder: View, TAG: String): WebRenderer {
            when (type) {
                Type.Stub ->
                    return StubRenderer(context, controller, webRendererPlaceholder, TAG)

                Type.WebView ->
                    return WebViewRenderer(context, controller, webRendererPlaceholder, TAG)
            }
        }
    }
}
