/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.articlerender

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.DownloadListener
import android.webkit.WebView
import android.webkit.WebViewClient
import com.peek.browser.Constant
import com.peek.browser.MainController
import com.peek.browser.Settings
import com.peek.browser.util.EventBus
import com.peek.browser.util.Util

class ArticleRenderer(context: Context, controller: Controller, articleContent: ArticleContent, articleRendererPlaceholder: View) {

    interface Controller {
        fun onUrlLongClick(webView: WebView, url: String, type: Int)
        fun onDownloadStart(urlAsString: String)
        fun onBackPressed(): Boolean
        fun onShowBrowserPrompt()
        fun onFirstPageLoadStarted()
    }

    private val mContext: Context = context
    private val mWebView: WebView
    private var mIsDestroyed = false
    private val mFirstPageLoadTriggered = false
    private val mController: Controller = controller
    private var mRegisteredForBus: Boolean

    val mWebViewClient: WebViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView, urlAsString: String, favIcon: Bitmap?) {
            if (!mFirstPageLoadTriggered) {
                mController.onFirstPageLoadStarted()
            }
        }
    }

    val mDownloadListener: DownloadListener = DownloadListener { urlAsString, userAgent, contentDisposition, mimetype, contentLength ->
        mController.onDownloadStart(urlAsString)
    }

    val mOnWebViewLongClickListener: View.OnLongClickListener = View.OnLongClickListener {
        val hitTestResult = mWebView.hitTestResult
        //Log.d(TAG, "onLongClick type: " + hitTestResult.getType());
        when (hitTestResult.type) {
            WebView.HitTestResult.IMAGE_TYPE, WebView.HitTestResult.SRC_ANCHOR_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val url = hitTestResult.extra
                if (url == null) {
                    return@OnLongClickListener false
                }

                mController.onUrlLongClick(mWebView, url, hitTestResult.type)
                true
            }

            else -> {
                if (!Constant.ACTIVITY_WEBVIEW_RENDERING) {
                    mController.onShowBrowserPrompt()
                }
                false
            }
        }
    }

    val mOnKeyListener: View.OnKeyListener = View.OnKeyListener { v, keyCode, event ->
        if (event.action == KeyEvent.ACTION_DOWN && !mIsDestroyed) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    return@OnKeyListener mController.onBackPressed()
                }
            }
        }

        false
    }

    init {
        Log.e(BATTERY_SAVE_TAG, "create: " + this.javaClass.simpleName)

        mWebView = WebView(context)
        mWebView.layoutParams = articleRendererPlaceholder.layoutParams
        Util.replaceViewAtPosition(articleRendererPlaceholder, mWebView)

        mWebView.setDownloadListener(mDownloadListener)
        mWebView.setOnLongClickListener(mOnWebViewLongClickListener)
        mWebView.setOnKeyListener(mOnKeyListener)

        mWebView.webViewClient = mWebViewClient

        val webSettings = mWebView.settings
        webSettings.setSupportZoom(true)
        webSettings.textZoom = Settings.get().getWebViewTextZoom()
        webSettings.textZoom = Settings.get().getWebViewTextZoom()
        webSettings.builtInZoomControls = true
        webSettings.displayZoomControls = false

        display(articleContent, false)

        Log.d("Article", "ArticleRenderer()")
        EventBus.subscribe(this, MainController.UserPresentEvent::class.java, ::onUserPresentEvent)
        EventBus.subscribe(this, MainController.ScreenOffEvent::class.java, ::onScreenOffEvent)
        EventBus.subscribe(this, MainController.BeginCollapseTransitionEvent::class.java, ::onBeginCollapseTransitionEvent)
        EventBus.subscribe(this, MainController.BeginExpandTransitionEvent::class.java, ::onBeginExpandTransitionEvent)
        EventBus.subscribe(this, MainController.HideContentEvent::class.java, ::onHideContentEvent)
        EventBus.subscribe(this, MainController.UnhideContentEvent::class.java, ::onUnhideContentEvent)
        mRegisteredForBus = true
    }

    fun display(articleContent: ArticleContent) {
        display(articleContent, true)
    }

    private fun display(articleContent: ArticleContent, reuse: Boolean) {
        mWebView.stopLoading()
        val urlAsString = articleContent.mUrl.toString()
        mWebView.loadDataWithBaseURL(urlAsString, articleContent.mPageHtml!!, "text/html", "utf-8", urlAsString)
        Log.d("Article", ".display() - " + (if (reuse) "REUSE" else "NEW") + ", url:" + articleContent.mUrl.toString())
    }

    fun destroy() {
        if (mRegisteredForBus) {
            EventBus.unsubscribeAll(this)
            mRegisteredForBus = false
        }
        mIsDestroyed = true
        mWebView.destroy()
        Log.d("Article", "ArticleRenderer.destroy()")
    }

    fun getView(): View {
        return mWebView
    }

    fun stopLoading() {
        mWebView.stopLoading()
    }

    private fun webviewPause(via: String) {
        val msg = "PAUSE ($via) "
        if (!mIsDestroyed) {
            mWebView.onPause()
        }
        Log.d(BATTERY_SAVE_TAG, msg)
    }

    private fun webviewResume(via: String) {
        val msg = "RESUME ($via) "
        if (!mIsDestroyed) {
            mWebView.onResume()
        }
        Log.d(BATTERY_SAVE_TAG, msg)
    }

    fun onUserPresentEvent(event: MainController.UserPresentEvent) {
        when (Settings.get().getWebViewBatterySaveMode()) {
            Settings.WebViewBatterySaveMode.Default -> webviewResume("userPresent")
            else -> {}
        }
    }

    fun onScreenOffEvent(event: MainController.ScreenOffEvent) {
        when (Settings.get().getWebViewBatterySaveMode()) {
            Settings.WebViewBatterySaveMode.Aggressive, Settings.WebViewBatterySaveMode.Default -> webviewPause("screenOff")
            else -> {}
        }
    }

    fun onBeginCollapseTransitionEvent(event: MainController.BeginCollapseTransitionEvent) {
        when (Settings.get().getWebViewBatterySaveMode()) {
            Settings.WebViewBatterySaveMode.Aggressive -> webviewPause("beginCollapse")
            else -> {}
        }
    }

    fun onBeginExpandTransitionEvent(event: MainController.BeginExpandTransitionEvent) {
        when (Settings.get().getWebViewBatterySaveMode()) {
            Settings.WebViewBatterySaveMode.Aggressive -> webviewResume("beginExpand")
            else -> {}
        }
    }

    fun onHideContentEvent(event: MainController.HideContentEvent) {
        when (Settings.get().getWebViewBatterySaveMode()) {
            Settings.WebViewBatterySaveMode.Aggressive, Settings.WebViewBatterySaveMode.Default -> webviewPause("hide event")
            else -> {}
        }
    }

    fun onUnhideContentEvent(event: MainController.UnhideContentEvent) {
        when (Settings.get().getWebViewBatterySaveMode()) {
            Settings.WebViewBatterySaveMode.Default -> webviewResume("unhide event")
            else -> {}
        }
    }

    companion object {
        private const val BATTERY_SAVE_TAG = "BatterySaveArticleRenderer"
    }
}
