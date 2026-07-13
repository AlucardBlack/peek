/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.webrender

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.peek.browser.Constant
import com.peek.browser.MainController
import com.peek.browser.R
import com.peek.browser.Settings
import com.peek.browser.articlerender.ArticleContent
import com.peek.browser.util.SourceTag
import com.peek.browser.util.CrashTracking
import com.peek.browser.util.EventBus
import com.peek.browser.util.NetworkConnectivity
import com.peek.browser.util.NetworkReceiver
import com.peek.browser.util.PageInspector
import com.peek.browser.util.Util
import java.net.MalformedURLException
import java.net.URL

class WebViewRenderer(context: Context, controller: Controller, webRendererPlaceholder: View, tag: String) : WebRenderer(context, controller, webRendererPlaceholder) {

    @JvmField
    protected var TAG: String
    private val mHandler: Handler
    @JvmField
    protected var mWebView: CustomWebView
    private var mTouchInterceptorView: View
    private var mLastWebViewTouchUpTime: Long = -1
    private var mLastWebViewTouchDownUrl: String? = null
    private var mHost: String? = null
    private var mJsAlertDialog: AlertDialog? = null
    private var mJsConfirmDialog: AlertDialog? = null
    private var mJsPromptDialog: AlertDialog? = null
    private var mPageInspector: PageInspector
    private var mCheckForEmbedsCount = 0
    private var mRunPageScripts = 0
    private var mCurrentProgress = 0
    private var mPauseOnComplete = false
    private var mIsDestroyed = false
    private var mRegisteredForBus: Boolean
    private var mAdblockEnabled = false

    private var mBuildArticleContentTask: ArticleContent.BuildContentTask? = null
    private var mArticleContent: ArticleContent? = null


    val mOnPageInspectorItemFoundListener: PageInspector.OnItemFoundListener = object : PageInspector.OnItemFoundListener {

        override fun onTouchIconLoaded(bitmap: Bitmap, pageUrl: String?) {
            mController.onPageInspectorTouchIconLoaded(bitmap, pageUrl)
        }

        override fun onFetchHtml(html: String) {
            if (html.isNotEmpty()) {
                if (mBuildArticleContentTask == null) {
                    mBuildArticleContentTask = ArticleContent.fetchArticleContent(getUrl().toString(), html,
                            object : ArticleContent.OnFinishedListener {
                                override fun onFinished(articleContent: ArticleContent?) {
                                    mArticleContent = articleContent
                                    mController.onArticleContentReady(mArticleContent)
                                    mBuildArticleContentTask = null
                                }
                            }
                    )
                }
            }
        }

        override fun onThemeColor(color: Int) {
            mController.onPagedInspectorThemeColorFound(color)
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

    val mOnWebViewLongClickListener: View.OnLongClickListener = View.OnLongClickListener {
        val hitTestResult = mWebView.hitTestResult
        Log.d(TAG, "onLongClick type: " + hitTestResult.type)
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
                    val msg = Message()
                    msg.target = object : Handler() {
                        override fun handleMessage(msg: Message) {
                            val b = msg.data
                            if (b != null && b.getString("url") != null) {
                                mController.onShowBrowserPrompt()
                            }
                        }
                    }
                    mWebView.requestFocusNodeHref(msg)
                }
                true
            }
        }
    }

    val mOnScrollChangedCallback: CustomWebView.OnScrollChangedCallback = object : CustomWebView.OnScrollChangedCallback {
        override fun onScroll(newY: Int, oldY: Int) {
            if (!mWebView.mInterceptScrollChangeCalls && 0 == newY) {
                mController.resetBubblePanelAdjustment()
            } else {
                mController.adjustBubblesPanel(newY, oldY, false)
            }
        }
    }

    private val mWebViewOnTouchListener = View.OnTouchListener { v, event ->
        val action = event.action and MotionEvent.ACTION_MASK
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                mLastWebViewTouchDownUrl = mUrl.toString()
                mWebView.mInterceptScrollChangeCalls = true
                //Log.d(TAG, "[urlstack] WebView - MotionEvent.ACTION_DOWN");
            }

            MotionEvent.ACTION_UP -> {
                mLastWebViewTouchUpTime = System.currentTimeMillis()
                mWebView.mInterceptScrollChangeCalls = false
                //Log.d(TAG, "[urlstack] WebView - MotionEvent.ACTION_UP");
                mController.adjustBubblesPanel(0, 0, true)
            }
        }
        // Forcibly pass along to the WebView. This ensures we receive the ACTION_UP event above.
        mWebView.onTouchEvent(event)
        true
    }

    val mWebViewClient: WebViewClient = object : WebViewClient() {
        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            var hitResult: WebView.HitTestResult? = null
            hitResult = mWebView.hitTestResult
            var extraRes: String? = null
            if (null != hitResult) {
                extraRes = hitResult.extra
                if (null != extraRes) {
                    try {
                        val extraURL = URL(extraRes)
                        extraRes = extraRes.substring(extraURL.protocol.length + "://".length)
                    } catch (exc: MalformedURLException) {
                        exc.printStackTrace()
                    }
                }
            }
            var webViewHitResultType = WebView.HitTestResult.UNKNOWN_TYPE
            if (null != hitResult) {
                webViewHitResultType = hitResult.type
            }
            if (null == extraRes
                    || WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE == webViewHitResultType
                    || url.endsWith(extraRes)) {
                mController.doUpdateVisitedHistory(url, isReload,
                        WebView.HitTestResult.UNKNOWN_TYPE == webViewHitResultType
                                || WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE == webViewHitResultType)
            }
        }

        override fun shouldOverrideUrlLoading(wView: WebView, urlAsString: String): Boolean {
            var viaInput = false
            if (mLastWebViewTouchUpTime > -1) {
                val touchUpTimeDelta = System.currentTimeMillis() - mLastWebViewTouchUpTime
                // this value needs to be largish
                if (touchUpTimeDelta < 1500) {
                    // If the url has changed since the use pressed their finger down, a redirect has likely occurred,
                    // in which case we don't update the Url Stack
                    if (mLastWebViewTouchDownUrl == mUrl.toString()) {
                        viaInput = true
                    }
                    mLastWebViewTouchUpTime = -1
                }
            }

            return mController.shouldOverrideUrlLoading(urlAsString, viaInput)
        }

        private fun interceptTheCall(view: WebView, urlStr: String, filterOption: String): WebResourceResponse? {
            // null signifies allowing the request
            val allowRequest: WebResourceResponse? = null

            // Quickly check to see if no checks are needed because ad blocking is not enabled.
            if (!mAdblockEnabled) {
                return allowRequest
            }

            try {
                URL(urlStr).host
            } catch (e: Exception) {
                return allowRequest
            }

            if (mController.shouldAdBlockUrl(mHost!!, urlStr, filterOption)) {
                return WebResourceResponse("text/html", "UTF-8", 450, "Blocked", null, null)
            }

            return allowRequest
        }

        override fun shouldInterceptRequest(view: WebView, resourceRequest: WebResourceRequest): WebResourceResponse? {
            // That call is for the API level is higher or equal to 21

            val currentUrl = resourceRequest.url.toString()
            // Quickly check to see if no checks are needed because ad blocking is not enabled.
            if (!mAdblockEnabled || mUrl.toString() == currentUrl) {
                return null
            }

            var filterOption = "none"
            val requestHeaders = resourceRequest.requestHeaders
            for (entry in requestHeaders.entries) {
                if (entry.key == "Accept") {
                    if (entry.value.contains("/css")) {
                        filterOption = "/css"
                        break
                    } else if (entry.value.contains("image/")) {
                        filterOption = "image/"
                        break
                    } else if (entry.value.contains("javascript")) {
                        filterOption = "javascript"
                        break
                    }
                }
            }

            return interceptTheCall(view, currentUrl, filterOption)
        }

        override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
            Log.d(TAG, "WebViewRenderer - onReceivedError() - $description - $failingUrl")

            // Reload webviews once we have a connection.
            if (!NetworkConnectivity.isConnected(mContext)) {
                Log.d(TAG, "Not connected, will retry on connection.")

                // We only reload a single webview at a time, so if there is a previous receiver, we unregister it.
                if (mLastNetworkReceiver != null) {
                    try {
                        mContext.unregisterReceiver(mLastNetworkReceiver)
                    } catch (e: Exception) {
                        Log.d(TAG, "Could not unregister existing network receiver.")
                    }
                    mLastNetworkReceiver = null
                }

                val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
                val receiver = NetworkReceiver(this@WebViewRenderer)
                ContextCompat.registerReceiver(mContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                mLastNetworkReceiver = receiver
            }
            mController.onReceivedError()
        }

        override fun onReceivedSslError(webView: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            /*
            AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
            builder.setTitle(mContext.getString(R.string.warning));
            String s = error.toString();
            Log.d("blerg", s);
            URL url;
            try {
                 url = new URL(error.getUrl());
            } catch (MalformedURLException e) {
                e.printStackTrace();
                url = mUrl;
            }
            builder.setMessage(String.format(mContext.getString(R.string.untrusted_certificate), url.getHost()))
                    .setCancelable(true)
                    .setPositiveButton(mContext.getString(R.string.yes),
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int id) {
                                    handler.proceed();
                                }
                            })
                    .setNegativeButton(mContext.getString(R.string.action_no_recommended),
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int id) {
                                    handler.cancel();
                                }
                            });
            if (error.getPrimaryError() == SslError.SSL_UNTRUSTED) {
                AlertDialog alert = builder.create();
                alert.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                Util.showThemedDialog(alert);
            } else {
                handler.proceed();
            }*/
        }

        override fun onPageStarted(view: WebView, urlAsString: String, favIcon: Bitmap?) {
            mController.onPageStarted(urlAsString, favIcon)
        }

        override fun onPageFinished(webView: WebView, urlAsString: String) {
            mController.onPageFinished(urlAsString)
        }
    }

    val mDownloadListener: DownloadListener = DownloadListener { urlAsString, userAgent, contentDisposition, mimetype, contentLength ->
        mController.onDownloadStart(urlAsString)
    }

    val mWebChromeClient: WebChromeClient = object : WebChromeClient() {
        override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>,
                                        fileChooserParams: FileChooserParams): Boolean {
            MainController.get()!!.startFileBrowser(fileChooserParams.acceptTypes, filePathCallback)
            return true
        }

        override fun onReceivedTitle(webView: WebView, title: String) {
            mController.onReceivedTitle(webView.url, title)
        }

        override fun onReceivedIcon(webView: WebView, bitmap: Bitmap) {
            mController.onReceivedIcon(bitmap)
        }

        override fun onProgressChanged(webView: WebView, progress: Int) {
            mCurrentProgress = progress
            mController.onProgressChanged(progress, webView.url)

            // Inject page scripts after there has been some progress, otherwise they get injected into an empty page.
            if (mCurrentProgress >= 60 && mRunPageScripts == 0) {
                mRunPageScripts = 1
                mPageInspector.run(webView)
            }

            if (mCurrentProgress == 100 && mPauseOnComplete) {
                mHandler.postDelayed(mCheckForPauseRunnable, 3000)
            }
        }

        override fun onCloseWindow(window: WebView) {
            mController.onCloseWindow()
        }

        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
            val dialog = AlertDialog.Builder(mContext).create()
            mJsAlertDialog = dialog
            dialog.setMessage(message)
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dialog.setButton(AlertDialog.BUTTON_POSITIVE, mContext.resources.getString(R.string.action_ok),
                    DialogInterface.OnClickListener { _, _ ->
                        result.confirm()
                    })
            dialog.setOnCancelListener(DialogInterface.OnCancelListener {
                result.cancel()
            })
            dialog.setOnDismissListener(DialogInterface.OnDismissListener {
                mJsAlertDialog = null
            })
            Util.showThemedDialog(dialog)
            return true
        }

        override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
            val dialog = AlertDialog.Builder(mContext).create()
            mJsConfirmDialog = dialog
            dialog.setTitle(R.string.confirm_title)
            dialog.setMessage(message)
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dialog.setButton(AlertDialog.BUTTON_POSITIVE, mContext.resources.getString(android.R.string.ok), DialogInterface.OnClickListener { _, _ ->
                result.confirm()
            })
            dialog.setButton(AlertDialog.BUTTON_NEGATIVE, mContext.resources.getString(android.R.string.cancel), DialogInterface.OnClickListener { _, _ ->
                result.cancel()
            })
            dialog.setOnDismissListener(DialogInterface.OnDismissListener {
                mJsConfirmDialog = null
            })
            Util.showThemedDialog(dialog)
            return true
        }

        override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: JsPromptResult): Boolean {
            val v = LayoutInflater.from(mContext).inflate(R.layout.view_javascript_prompt, null)

            (v.findViewById<View>(R.id.prompt_message_text) as TextView).text = message
            (v.findViewById<View>(R.id.prompt_input_field) as EditText).setText(defaultValue)

            val dialog = AlertDialog.Builder(mContext).create()
            mJsPromptDialog = dialog
            dialog.setView(v)
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dialog.setButton(AlertDialog.BUTTON_POSITIVE, mContext.resources.getString(android.R.string.ok), DialogInterface.OnClickListener { _, _ ->
                val value = (v.findViewById<View>(R.id.prompt_input_field) as EditText).text.toString()
                result.confirm(value)
            })
            dialog.setButton(AlertDialog.BUTTON_NEGATIVE, mContext.resources.getString(android.R.string.cancel), DialogInterface.OnClickListener { _, _ ->
                result.cancel()
            })
            dialog.setOnCancelListener(DialogInterface.OnCancelListener {
                result.cancel()
            })
            dialog.setOnDismissListener(DialogInterface.OnDismissListener {
                mJsPromptDialog = null
            })
            Util.showThemedDialog(dialog)

            return true
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            // Call the old version of this function for backwards compatability.
            //onConsoleMessage(consoleMessage.message(), consoleMessage.lineNumber(),
            //        consoleMessage.sourceId());
            var message: String? = consoleMessage.message()
            if (message == null) {
                message = "(null)"
            }
            Log.e("Console", message)

            return false
        }

        override fun onCreateWindow(view: WebView, dialog: Boolean, userGesture: Boolean, resultMsg: Message): Boolean {
            val tabView = MainController.get()!!.openUrl(Constant.NEW_TAB_URL, System.currentTimeMillis(), false, SourceTag.OPENED_URL_FROM_NEW_WINDOW)
            if (tabView != null) {
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = tabView.getContentView()!!.getWebRenderer().getView() as WebView
                resultMsg.sendToTarget()
                return true
            }

            return false
        }
    }

    init {
        mHandler = Handler()
        TAG = tag

        mWebView = WebViewPreloader.obtain(context) ?: CustomWebView(mContext)
        mWebView.layoutParams = webRendererPlaceholder.layoutParams
        Util.replaceViewAtPosition(webRendererPlaceholder, mWebView)

        mTouchInterceptorView = View(mContext)
        mTouchInterceptorView.layoutParams = webRendererPlaceholder.layoutParams
        mTouchInterceptorView.setWillNotDraw(true)
        mTouchInterceptorView.setOnTouchListener(mWebViewOnTouchListener)

        val parent = mWebView.parent as ViewGroup
        val index = parent.indexOfChild(mWebView)
        parent.addView(mTouchInterceptorView, index + 1)

        mWebView.isLongClickable = true
        mWebView.webChromeClient = mWebChromeClient
        mWebView.webViewClient = mWebViewClient
        mWebView.setDownloadListener(mDownloadListener)
        mWebView.setOnLongClickListener(mOnWebViewLongClickListener)
        mWebView.setOnKeyListener(mOnKeyListener)
        mWebView.setOnScrollChangedCallback(mOnScrollChangedCallback)

        val webSettings = mWebView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.setSupportZoom(true)
        webSettings.textZoom = Settings.get().getWebViewTextZoom()
        webSettings.builtInZoomControls = true
        webSettings.displayZoomControls = false
        webSettings.loadWithOverviewMode = true
        webSettings.useWideViewPort = true
        webSettings.setSupportMultipleWindows(true)
        webSettings.savePassword = false

        val userAgentString = Settings.get().getUserAgentString()
        if (userAgentString != null) {
            webSettings.userAgentString = userAgentString
        }

        mPageInspector = PageInspector(mContext, mWebView, mOnPageInspectorItemFoundListener)

        EventBus.subscribe(this, MainController.UserPresentEvent::class.java, ::onUserPresentEvent)
        EventBus.subscribe(this, MainController.ScreenOffEvent::class.java, ::onScreenOffEvent)
        EventBus.subscribe(this, MainController.BeginCollapseTransitionEvent::class.java, ::onBeginCollapseTransitionEvent)
        EventBus.subscribe(this, MainController.BeginExpandTransitionEvent::class.java, ::onBeginExpandTransitionEvent)
        EventBus.subscribe(this, MainController.HideContentEvent::class.java, ::onHideContentEvent)
        EventBus.subscribe(this, MainController.UnhideContentEvent::class.java, ::onUnhideContentEvent)
        mRegisteredForBus = true
    }

    override fun destroy() {
        if (mRegisteredForBus) {
            EventBus.unsubscribeAll(this)
            mRegisteredForBus = false
        }
        cancelBuildArticleContentTask()
        mIsDestroyed = true
        try {
            // The exception sometimes here is possible related to how we create our WebView. We use an application context,
            // but seems like should use an activity. That is the possible fix for the crash. It should gone when we have WebView
            // inside an Activity
            mWebView.stopLoading()
            mWebView.removeAllViews()
            mWebView.clearCache(true)
            mWebView.destroyDrawingCache()
            mWebView.destroy()
        } catch (exc: IllegalArgumentException) {
            exc.printStackTrace()
        } catch (exc: Exception) {
            exc.printStackTrace()
        }
        Log.d("Article", "WebViewRenderer.destroy()")
    }

    override fun getView(): View {
        return mWebView
    }

    override fun updateIncognitoMode(incognito: Boolean) {
        if (incognito) {
            mWebView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            mWebView.clearHistory()
            mWebView.clearCache(true)

            mWebView.clearFormData()
            mWebView.settings.saveFormData = false
        } else {
            mWebView.settings.cacheMode = WebSettings.LOAD_DEFAULT

            mWebView.settings.saveFormData = true
        }
    }

    private fun cancelBuildArticleContentTask() {
        if (mBuildArticleContentTask != null) {
            mBuildArticleContentTask!!.cancel(true)
            Log.d("Article", "BuildContentTask().cancel()")
            mBuildArticleContentTask = null
        }
    }

    override fun getUserAgentString(context: Context): String? {
        if (mWebView.settings == null) {
            return Util.getDefaultUserAgentString(context)
        }
        return mWebView.settings.userAgentString
    }

    override fun setUserAgentString(userAgentString: String) {
        val webSettings = mWebView.settings
        if (null != webSettings) {
            webSettings.userAgentString = userAgentString
        }
    }

    override fun loadUrl(url: URL, mode: Mode) {
        var host = url.host
        if (host.startsWith("www.")) {
            host = host.substring(4)
        }
        mHost = host
        mAdblockEnabled = Settings.get().isAdBlockEnabled
        refresh3PCookieSetting()

        val urlAsString = url.toString()
        Log.d(TAG, "loadUrl() - $urlAsString")

        cancelBuildArticleContentTask()
        mArticleContent = null

        mMode = mode

        when (mMode!!) {
            Mode.Article ->
                //mGetArticleContentTask = new GetArticleContentTask();
                //mGetArticleContentTask.execute(urlAsString);

                // This is only called by Snacktory renderer so that the loading animations start at the point the page HTML commences.
                // Not needed for other Renderers given onPageStarted() will be called.
                mController.onLoadUrl(urlAsString)

            Mode.Web ->
                mWebView.loadUrl(url.toString())
        }
    }

    private fun refresh3PCookieSetting() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(mWebView, !Settings.get().isBlock3PCookiesEnabled())
        }
    }

    override fun reload() {
        when (mMode!!) {
            Mode.Article ->
                loadUrl(getUrl()!!, mMode!!)

            Mode.Web -> {
                // In case the user changes adblock settings and reloads the current bubble
                mAdblockEnabled = Settings.get().isAdBlockEnabled
                refresh3PCookieSetting()
                mWebView.reload()
            }
        }
    }

    override fun stopLoading() {
        cancelBuildArticleContentTask()
        mArticleContent = null

        try {
            mWebView.stopLoading()

            // Ensure the loading indicators cease when stop is pressed.
            mWebChromeClient.onProgressChanged(mWebView, 100)
        } catch (exc: NullPointerException) {
            CrashTracking.logHandledException(exc)
        }
    }

    override fun hidePopups() {
        if (mJsAlertDialog != null) {
            mJsAlertDialog!!.dismiss()
            mJsAlertDialog = null
        }
        if (mJsConfirmDialog != null) {
            mJsConfirmDialog!!.dismiss()
            mJsConfirmDialog = null
        }
        if (mJsPromptDialog != null) {
            mJsPromptDialog!!.dismiss()
            mJsPromptDialog = null
        }
    }

    override fun onPageLoadComplete() {
        super.onPageLoadComplete()
    }

    override fun resetPageInspector() {
        mPageInspector.reset()
    }

    override fun runPageInspector() {
        mPageInspector.run(mWebView)
    }


    override val articleContent: ArticleContent?
        get() = mArticleContent

    private val mCheckForPauseRunnable = Runnable {
        when (Settings.get().getWebViewBatterySaveMode()) {
            Settings.WebViewBatterySaveMode.Default, Settings.WebViewBatterySaveMode.Aggressive -> {
                if (mPauseOnComplete) {
                    mPauseOnComplete = false
                    webviewPause("runnable")
                }
            }
            else -> {}
        }
    }

    private fun webviewPause(via: String) {
        var msg = "PAUSE ($via) "
        if (!mIsDestroyed) {
            if (mCurrentProgress == 100) {
                mWebView.onPause()
                mPauseOnComplete = false
            } else {
                msg += " **IGNORE** ($mCurrentProgress)"
                mPauseOnComplete = true
            }
        }
        Log.d(BATTERY_SAVE_TAG, "$msg, url:" + getUrl()!!.host)
    }

    private fun webviewResume(via: String) {
        mPauseOnComplete = false
        val msg = "RESUME ($via) "
        if (!mIsDestroyed) {
            mWebView.onResume()
        }
        Log.d(BATTERY_SAVE_TAG, "$msg, url:" + getUrl()!!.host)
    }

    override fun resumeOnSetActive() {
        // Nothing happens if we call resume on resumed WebView but
        // we should resume if we had Aggressive mode before and paused it and set it to Default or Off in Settings
        webviewResume("setActive")
    }

    override fun pauseOnSetInactive() {
        when (Settings.get().getWebViewBatterySaveMode()) {
            Settings.WebViewBatterySaveMode.Aggressive -> webviewPause("setInactive")
            else -> {}
        }
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
        // Do nothing here for now as we Resume current active Tab on resumeOnSetActive
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
        private const val BATTERY_SAVE_TAG = "BatterySaveWebView"

        private var mLastNetworkReceiver: NetworkReceiver? = null
    }
}
