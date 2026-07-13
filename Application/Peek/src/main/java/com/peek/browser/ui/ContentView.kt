/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.database.DataSetObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Message
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.DrawableCompat
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.util.Log
import android.util.Patterns
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import com.peek.browser.BuildConfig
import com.peek.browser.Constant
import com.peek.browser.Constant.BubbleAction
import com.peek.browser.MainApplication
import com.peek.browser.MainController
import com.peek.browser.R
import com.peek.browser.Settings
import com.peek.browser.adblock.ABPFilterParser
import com.peek.browser.adblock.WhiteListCollector
import com.peek.browser.articlerender.ArticleContent
import com.peek.browser.articlerender.ArticleRenderer
import com.peek.browser.util.ActionItem
import com.peek.browser.util.SourceTag
import com.peek.browser.util.CrashTracking
import com.peek.browser.util.DownloadImage
import com.peek.browser.util.Util
import com.peek.browser.webrender.WebRenderer
import java.io.IOException
import java.net.MalformedURLException
import java.net.URL
import java.net.URLEncoder
import java.util.ArrayList
import java.util.Collections
import java.util.HashSet
import java.util.Stack

class ContentView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    private enum class LifeState {
        Init,
        Alive,
        Removed,
        Destroyed
    }

    private lateinit var mWebRenderer: WebRenderer
    private var mArticleRenderer: ArticleRenderer? = null
    private var mArticleNotificationId = -1
    private var mOwnerTabView: TabView? = null

    private lateinit var mCaretView: View
    private lateinit var mTitleTextView: CondensedTextView
    private lateinit var mUrlTextView: CondensedTextView
    private lateinit var mShareButton: ContentViewButton
    private lateinit var mReloadButton: ContentViewButton
    private lateinit var mArticleModeButton: ArticleModeButton
    private lateinit var mOpenInAppButton: OpenInAppButton
    private lateinit var mOverflowButton: ContentViewButton
    private lateinit var mToolbarLayout: LinearLayout
    private lateinit var mEventHandler: EventHandler
    private var mCurrentProgress = 0

    // Search URL functionality
    private lateinit var metUrl: AutoCompleteTextView
    private lateinit var mbtUrlClear: ImageButton
    private lateinit var mContentEditUrl: FrameLayout
    private var mPreviousMetUrl: String? = null
    private var mPreviousAppendedString: String? = null

    private var mPageFinishedLoading: Boolean = false
    private var mLifeState = LifeState.Init
    private val mAppPickersUrls: MutableSet<String> = HashSet()

    private val mAppsForUrl: MutableList<AppForUrl> = ArrayList()
    private val mTempAppsForUrl: MutableList<ResolveInfo> = ArrayList()

    private var mOverflowPopupMenu: PopupMenu? = null
    private var mLongPressAlertDialog: AlertDialog? = null
    private var mInitialUrlLoadStartTime: Long = 0
    private var mInitialUrlAsString: String? = null
    private val mLoadingString: String
    private val mContext: Context

    private val mUrlStack: Stack<URL> = Stack()
    // We only want to handle this once per link. This prevents 3+ dialogs appearing for some links, which is a bad experience. #224
    private var mHandledAppPickerForCurrentUrl = false
    private var mUsingPeekAsDefaultForCurrentUrl = false

    private lateinit var mAdapter: SearchURLCustomAdapter
    private var mFirstSuggestedItem: SearchURLSuggestions? = null

    private val mOneRowAutoSuggestionsSize = 53f
    private val mRowsToShowOnAutoSuggestions = 5

    private var mApplyAutoSuggestionToUrlString = true
    private var mSetTheRealUrlString = true
    private var mFirstTimeUrlTyped = true
    private var mHostInWhiteList = false

    init {
        mContext = context
        mLoadingString = resources.getString(R.string.loading)
    }

    fun getTotalTrackedLoadTime(): Long {
        if (mInitialUrlLoadStartTime > -1) {
            return System.currentTimeMillis() - mInitialUrlLoadStartTime
        }
        return -1
    }

    fun getWebRenderer(): WebRenderer {
        return mWebRenderer
    }

    class AppForUrl(@JvmField var mResolveInfo: ResolveInfo?, @JvmField var mUrl: URL) {
        @JvmField
        var mIcon: Drawable? = null

        fun getIcon(context: Context): Drawable? {
            if (mIcon == null) {
                // TODO: Handle OutOfMemory error
                if (mResolveInfo != null) {
                    mIcon = mResolveInfo!!.loadIcon(context.packageManager)
                }
            }

            return mIcon
        }
    }

    interface EventHandler {
        fun onPageLoading(url: URL)
        fun onProgressChanged(progress: Int)
        fun onPageLoaded(withError: Boolean)
        fun onReceivedIcon(bitmap: Bitmap?): Boolean
        fun setDefaultFavicon()
        fun onCanGoBackChanged(canGoBack: Boolean)
        fun hasHighQualityFavicon(): Boolean
        fun onThemeColor(color: Int?)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)

        if (isInEditMode) {
            return
        }

        /*
        float centerX = Config.mScreenCenterX;
        float indicatorEndY = 2.f;
        float indicatorStartX = centerX - mHeaderHeight + indicatorEndY;
        float indicatorEndX = centerX + mHeaderHeight - indicatorEndY;

        mTempPath.reset();
        mTempPath.moveTo(indicatorStartX, mHeaderHeight);
        mTempPath.lineTo(centerX, indicatorEndY);
        mTempPath.lineTo(indicatorEndX, mHeaderHeight);
        canvas.drawPath(mTempPath, sIndicatorPaint);

        canvas.drawLine(indicatorEndY, mHeaderHeight, indicatorStartX, mHeaderHeight, sBorderPaint);
        canvas.drawLine(indicatorStartX, mHeaderHeight, centerX, 0, sBorderPaint);
        canvas.drawLine(centerX, indicatorEndY, indicatorEndX, mHeaderHeight, sBorderPaint);
        canvas.drawLine(indicatorEndX, mHeaderHeight, Config.mScreenWidth, mHeaderHeight, sBorderPaint);
        */
    }

    fun destroy() {
        Log.d(TAG, "*** destroy() - url" + (if (mWebRenderer.getUrl() != null) mWebRenderer.getUrl().toString() else "<null>"))
        mLifeState = LifeState.Destroyed
        removeView(mWebRenderer.getView())
        mWebRenderer.destroy()

        if (mArticleRenderer != null) {
            removeView(mArticleRenderer!!.getView())
            mArticleRenderer!!.destroy()
        }

        //if (mDelayedAutoContentDisplayLinkLoadedScheduled) {
        //    mDelayedAutoContentDisplayLinkLoadedScheduled = false;
        //    Log.e(TAG, "*** set mDelayedAutoContentDisplayLinkLoadedScheduled=" + mDelayedAutoContentDisplayLinkLoadedScheduled);
        //}
        removeCallbacks(mDelayedAutoContentDisplayLinkLoadedRunnable)
    }

    fun onRemoved() {
        mLifeState = LifeState.Removed
        cancelWearNotification()
    }

    fun onRestored() {
        mLifeState = LifeState.Alive
        // If we need to re-add the notification, do so here
        configureArticleModeButton()
    }

    fun updateIncognitoMode(incognito: Boolean) {
        mWebRenderer.updateIncognitoMode(incognito)
    }

    private fun showSelectShareMethod(urlAsString: String, closeBubbleOnShare: Boolean) {

        val alertDialog = ActionItem.getShareAlert(context, false, object : ActionItem.OnActionItemSelectedListener {
            override fun onSelected(actionItem: ActionItem) {
                val intent = Util.getSendIntent(actionItem.mPackageName, actionItem.mActivityClassName, urlAsString)
                context.startActivity(intent)

                val isCopyToClipboardAction = actionItem.mPackageName == "com.google.android.apps.docs"
                        && actionItem.mActivityClassName == "com.google.android.apps.docs.app.SendTextToClipboardActivity"

                // L_WATCH: L currently lacks getRecentTasks(), so minimize here
                if (isCopyToClipboardAction == false && Build.VERSION.SDK_INT > Build.VERSION_CODES.KITKAT) {
                    MainController.get()!!.switchToBubbleView(false)
                }

                //if (closeBubbleOnShare && isCopyToClipboardAction == false && MainController.get() != null) {
                //    MainController.get().closeTab(mOwnerTabView, true);
                //}
            }
        })
        Util.showThemedDialog(alertDialog)
    }

    private fun saveImage(urlAsString: String) {
        DownloadImage(mContext, urlAsString).download()
    }

    var mTintableDrawables: ArrayList<Drawable> = ArrayList()

    private fun getTintableDrawable(@DrawableRes resId: Int): Drawable {
        return getTintableDrawable(resId, true)
    }

    private fun getTintableDrawable(@DrawableRes resId: Int, addToList: Boolean): Drawable {
        val d = Util.getTintableDrawable(this.context, resId)
        if (addToList) {
            mTintableDrawables.add(d)
        }
        return d
    }

    private fun HostInWhiteListCheck(url: String) {
        val app = mContext.applicationContext as MainApplication

        val whiteListCollector = app.getWhiteListCollector()
        if (null == whiteListCollector) {
            mHostInWhiteList = false

            return
        }

        var host = ""
        try {
            host = URL(url).host
        } catch (exc: MalformedURLException) {
        }

        mHostInWhiteList = whiteListCollector.isInWhiteList(host)
    }

    private fun AddRemoveHostFromWhiteList(url: String, add: Boolean) {
        val app = mContext.applicationContext as MainApplication

        val whiteListCollector = app.getWhiteListCollector()
        if (null == whiteListCollector) {
            mHostInWhiteList = false

            return
        }

        var host = ""
        try {
            host = URL(url).host
        } catch (exc: MalformedURLException) {
        }

        if (add) {
            whiteListCollector.addHostToWhiteList(host)
        } else {
            whiteListCollector.removeHostFromWhiteList(host)
        }
    }

    fun toolbarHeight(): Int {
        val currentUrlBarParams = mContentEditUrl.layoutParams as FrameLayout.LayoutParams
        val currentShadowParams = findViewById<View>(R.id.actionbar_shadow).layoutParams as FrameLayout.LayoutParams
        return currentUrlBarParams.height + currentShadowParams.height
    }

    // The function configures the urlBar
    private fun configureUrlBar(urlAsString: String) {
        // Set the current URL to the search URL
        metUrl = findViewById(R.id.autocomplete_top500websites)
        mContentEditUrl = findViewById(R.id.content_edit_url)

        metUrl.setDropDownWidth(resources.displayMetrics.widthPixels)
        metUrl.setText(urlAsString)
        mFirstTimeUrlTyped = true
        metUrl.addTextChangedListener(murlTextWatcher)
        metUrl.onFocusChangeListener = murlOnFocusChangeListener
        metUrl.onItemClickListener = murlOnItemClickListener
        metUrl.setOnEditorActionListener(murlActionListener)
        metUrl.imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        metUrl.setOnKeyListener(murlKeyListener)

        mAdapter = SearchURLCustomAdapter(context, android.R.layout.simple_list_item_1, resources,
                resources.displayMetrics.widthPixels)
        mAdapter.mRealUrlBarConstraint = urlAsString
        metUrl.setAdapter(mAdapter)

        mAdapter.registerDataSetObserver(mDataSetObserver)

        mbtUrlClear = findViewById(R.id.search_url_clear)
        mbtUrlClear.setOnClickListener(mbtClearUrlClicked)
    }

    fun setTabAsActive() {
        try {
            if (mUrlTextView.text.toString() == context.getString(R.string.empty_bubble_page)) {
                mTitleTextView.performClick()
            } else {
                val view = mWebRenderer.getView()
                if (null != view) {
                    view.requestFocus()
                }
            }
        } catch (exc: NullPointerException) {
            // We have that exception sometimes inside Android SDK on requestFocus,
            // we would better to not get focus than crash
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Throws(MalformedURLException::class)
    fun configure(urlAsString: String, ownerTabView: TabView, urlLoadStartTime: Long, hasShownAppPicker: Boolean, eventHandler: EventHandler) {
        mLifeState = LifeState.Alive
        mTintableDrawables.clear()


        HostInWhiteListCheck(urlAsString)
        val webRendererPlaceholder = findViewById<View>(R.id.web_renderer_placeholder)
        mWebRenderer = WebRenderer.create(WebRenderer.Type.WebView, context, mWebRendererController, webRendererPlaceholder, TAG)
        mWebRenderer.setUrl(urlAsString)

        // Generates 1000 history links
        /*for (int i = 0; i < 1000; i++) {
            URL currentUrl = mWebRenderer.getUrl();
            MainApplication.saveUrlInHistory(getContext(), null, currentUrl.toString() + String.valueOf(i), currentUrl.getHost(), "111_test");
        }*/
        //

        if (mArticleRenderer != null) {
            mArticleRenderer!!.destroy()
            mArticleRenderer = null
        }

        mOwnerTabView = ownerTabView
        mHandledAppPickerForCurrentUrl = hasShownAppPicker
        mUsingPeekAsDefaultForCurrentUrl = false

        if (hasShownAppPicker) {
            mAppPickersUrls.add(urlAsString)
        }

        mToolbarLayout = findViewById(R.id.content_toolbar)
        mTitleTextView = findViewById(R.id.title_text)
        mUrlTextView = findViewById(R.id.url_text)

        // Set on click listeners to show the search URL control
        mTitleTextView.setOnClickListener(mOnURLEnterClicked)
        mUrlTextView.setOnClickListener(mOnURLEnterClicked)

        findViewById<View>(R.id.content_text_container).setOnTouchListener(mOnTextContainerTouchListener)

        configureUrlBar(urlAsString)

        mCaretView = findViewById(R.id.caret)

        mShareButton = findViewById(R.id.share_button)
        mShareButton.setImageDrawable(getTintableDrawable(R.drawable.ic_share_white_24dp))
        mShareButton.setOnClickListener(mOnShareButtonClickListener)

        mOpenInAppButton = findViewById(R.id.open_in_app_button)
        mOpenInAppButton.setOnOpenInAppClickListener(mOnOpenInAppButtonClickListener)

        mReloadButton = findViewById(R.id.reload_button)
        mReloadButton.setImageDrawable(getTintableDrawable(R.drawable.ic_refresh_white_24dp))
        mReloadButton.setOnClickListener(mOnReloadButtonClickListener)

        mArticleModeButton = findViewById(R.id.article_mode_button)
        mArticleModeButton.setState(ArticleModeButton.State.Article)
        mArticleModeButton.setOnClickListener(mOnArticleModeButtonClickListener)

        mOverflowButton = mToolbarLayout.findViewById(R.id.overflow_button)
        mOverflowButton.setImageDrawable(getTintableDrawable(R.drawable.ic_more_vert_white_24dp))
        mOverflowButton.setOnClickListener(mOnOverflowButtonClickListener)

        mEventHandler = eventHandler
        mEventHandler.onCanGoBackChanged(false)
        mPageFinishedLoading = false

        updateIncognitoMode(Settings.get().isIncognitoMode)

        mInitialUrlLoadStartTime = urlLoadStartTime
        mInitialUrlAsString = urlAsString

        updateAndLoadUrl(urlAsString)
        updateAppsForUrl(mWebRenderer.getUrl()!!)
        Log.d(TAG, "load url: $urlAsString")
        updateUrlTitleAndText(urlAsString)

        updateColors(null)
    }

    private fun WorkWithURL(strUrlIn: String, selectedSearchEngine: SearchURLSuggestions.SearchEngine, fromGoAction: Boolean) {

        metUrl.dismissDropDown()

        val strUrl = strUrlIn.trim()
        var strUrlWithPrefix = strUrl
        if (!strUrl.startsWith(context.getString(R.string.http_prefix)) &&
                !strUrl.startsWith(context.getString(R.string.https_prefix)))
            strUrlWithPrefix = context.getString(R.string.http_prefix) + strUrl

        if (SearchURLSuggestions.SearchEngine.NONE == selectedSearchEngine && Patterns.WEB_URL.matcher(strUrlWithPrefix).matches()) {
            LoadWebPage(strUrlWithPrefix)
        } else if (SearchURLSuggestions.SearchEngine.NONE == selectedSearchEngine && fromGoAction) {
            if (null != mFirstSuggestedItem) {
                WorkWithURL(strUrl, mFirstSuggestedItem!!.EngineToUse, false)
            }
        } else if (SearchURLSuggestions.SearchEngine.DUCKDUCKGO == selectedSearchEngine) {
            // Make the search using duck duck go
            try {
                val strQuery = String.format(context.getString(R.string.duckduckgo_search_engine), URLEncoder.encode(strUrl, "UTF-8"))
                LoadWebPage(strQuery)
            } catch (ioe: IOException) {
                Log.e(TAG, ioe.message, ioe)
            }
        } else if (SearchURLSuggestions.SearchEngine.GOOGLE == selectedSearchEngine) {
            // Make the search using google
            try {
                val strQuery = context.getString(R.string.google_search_engine) + URLEncoder.encode(strUrl, "UTF-8")
                LoadWebPage(strQuery)
            } catch (ioe: IOException) {
                Log.e(TAG, ioe.message, ioe)
            }
        } else if (SearchURLSuggestions.SearchEngine.YAHOO == selectedSearchEngine) {
            // Make the search using yahoo
            try {
                val strQuery = context.getString(R.string.yahoo_search_engine) + URLEncoder.encode(strUrl, "UTF-8")
                LoadWebPage(strQuery)
            } catch (ioe: IOException) {
                Log.e(TAG, ioe.message, ioe)
            }
        } else if (SearchURLSuggestions.SearchEngine.AMAZON == selectedSearchEngine) {
            // Make the search using amazon
            try {
                val strQuery = context.getString(R.string.amazon_search_engine) + URLEncoder.encode(strUrl, "UTF-8")
                LoadWebPage(strQuery)
            } catch (ioe: IOException) {
                Log.e(TAG, ioe.message, ioe)
            }
        }

        mToolbarLayout.bringToFront()
    }

    private fun LoadWebPage(strUrl: String) {
        updateAndLoadUrl(strUrl)
        mWebRendererController.resetBubblePanelAdjustment()
    }


    var themeColor: Int? = null
    fun updateColors(color: Int?) {
        themeColor = color
        val textColor: Int
        val bgColor: Int
        if (color == null || !Settings.get().getThemeToolbar()) {
            textColor = Settings.get().getThemedTextColor()
            bgColor = Settings.get().getThemedContentViewColor()
            mCaretView.background = resources.getDrawable(if (Settings.get().darkThemeEnabled)
                R.drawable.content_view_caret_dark else R.drawable.content_view_caret_white)
        } else {
            // Calculate text color based on contrast with background:
            // https://24ways.org/2010/calculating-color-contrast/
            val yiq = (Color.red(color) * 299 +
                    Color.green(color) * 587 + Color.blue(color) * 114) / 1000
            textColor = if (yiq >= 128) Settings.COLOR_BLACK else Settings.COLOR_WHITE

            bgColor = color
            val d = getTintableDrawable(R.drawable.content_view_caret_white, false)
            DrawableCompat.setTint(d, color)
            mCaretView.background = d
        }

        mToolbarLayout.setBackgroundColor(bgColor)
        mTitleTextView.setTextColor(textColor)
        mUrlTextView.setTextColor(textColor)
        metUrl.setBackgroundColor(bgColor)
        metUrl.setTextColor(textColor)
        mContentEditUrl.setBackgroundColor(bgColor)

        for (d in mTintableDrawables) {
            DrawableCompat.setTint(d, textColor)
        }

        mArticleModeButton.updateTheme(color)
    }

    fun setFaviconColor(color: Int?) {
        updateColors(color)
    }

    var mWebRendererController: WebRenderer.Controller = object : WebRenderer.Controller {

        override fun resetBubblePanelAdjustment() {
            val mainController = MainController.get()
            if (null != mainController) {
                mainController.adjustBubblesPanel(0, 0, false, true)
            }
        }

        override fun adjustBubblesPanel(newY: Int, oldY: Int, afterTouchAdjust: Boolean) {
            val mainController = MainController.get()
            if (null != mainController) {
                mainController.adjustBubblesPanel(newY, oldY, afterTouchAdjust, false)
            }
        }

        override fun shouldAdBlockUrl(baseHost: String, urlStr: String, filterOption: String): Boolean {
            if (mHostInWhiteList) {
                return false
            }

            val currentUrl = mWebRenderer.getUrl()
            if (currentUrl != null) {
                if (currentUrl.toString() == urlStr) {
                    return false
                }
            }

            val app = mContext.applicationContext as MainApplication
            val parser = app.getABPParser()
            if (null == parser) {
                return false
            }

            return parser.shouldBlockJava(baseHost, urlStr, filterOption)
        }

        private var mConsecutiveRedirectCount = 0

        override fun doUpdateVisitedHistory(url: String, isReload: Boolean, unknownClick: Boolean) {
            var peekUrl = ""
            if (mUrlStack.size > 0) {
                peekUrl = mUrlStack.peek().toString()
            }
            // We need isReload check when click on links from twitter. It usually has some internal links which are not the same
            // as real link and isReload is true in that case, so we need to skip them if it is a top website
            if ((isReload && 0 == mUrlStack.size) || url == "file:///android_asset/blank.html" ||
                    mUrlStack.size > 0 && peekUrl == url) {
                return
            }

            try {
                val historyUrl = URL(url)
                if (unknownClick) {
                    // Here we check on anchors change without clicking on them
                    val ref = historyUrl.ref
                    if (null != ref && 0 != ref.length
                            && (ref.indexOf("/") == -1 || ref == "/" || ref.length >= 2 && ref.indexOf("/", 1) == -1)) {
                        val originalUrl = url.substring(0, url.length - ref.length - 1)
                        if (peekUrl == originalUrl) {
                            return
                        } else if (0 != peekUrl.length) {
                            val peekURLForRef = URL(peekUrl)
                            val peekRef = peekURLForRef.ref
                            if (null != peekRef && 0 != peekRef.length
                                    && (peekRef.indexOf("/") == -1 || peekRef == "/"
                                            || peekRef.length >= 2 && peekRef.indexOf("/", 1) == -1)) {
                                val originalPeekUrl = peekUrl.substring(0, peekUrl.length - peekRef.length - 1)
                                if (originalPeekUrl == originalUrl) {
                                    return
                                }
                            }
                        }
                    }
                }
                Log.d(TAG, "[urlstack] push:$url, urlStack.size():${mUrlStack.size}")
                mUrlStack.push(historyUrl)
                mEventHandler.onCanGoBackChanged(mUrlStack.size > 1)
            } catch (e: MalformedURLException) {
            }
        }

        override fun shouldOverrideUrlLoading(urlAsString: String, viaUserInput: Boolean): Boolean {
            if (mLifeState != LifeState.Alive) {
                mConsecutiveRedirectCount = 0
                return true
            }

            if (urlAsString.startsWith("tel:")) {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse(urlAsString))
                if (MainApplication.loadIntent(context, intent, urlAsString, mInitialUrlLoadStartTime)) {
                    MainController.get()!!.switchToBubbleView(false)
                }
                mConsecutiveRedirectCount = 0
                return true
            }

            val updatedUrl = getUpdatedUrl(urlAsString, !viaUserInput)
            if (updatedUrl == null) {
                Log.d(TAG, "ignore unsupported URI scheme: $urlAsString")
                showOpenInBrowserPrompt(R.string.unsupported_scheme_default_browser,
                        R.string.unsupported_scheme_no_default_browser, mWebRenderer.getUrl().toString())
                mConsecutiveRedirectCount = 0
                return true        // true because we've handled the link ourselves
            }

            Log.d(TAG, "shouldOverrideUrlLoading() - url:$urlAsString")
            if (viaUserInput) {
                mHandledAppPickerForCurrentUrl = false
                mUsingPeekAsDefaultForCurrentUrl = false
            }

            //if (mDelayedAutoContentDisplayLinkLoadedScheduled) {
            //    mDelayedAutoContentDisplayLinkLoadedScheduled = false;
            //    Log.e(TAG, "*** set mDelayedAutoContentDisplayLinkLoadedScheduled=" + mDelayedAutoContentDisplayLinkLoadedScheduled);
            //}
            removeCallbacks(mDelayedAutoContentDisplayLinkLoadedRunnable)

            val host: String
            try {
                val url = URL(urlAsString)
                host = url.host
                if (host == "www.forbes.com") {
                    CookieManager.getInstance().setCookie("http://www.forbes.com", "forbes_ab=true")
                    CookieManager.getInstance().setCookie("http://www.forbes.com", "welcomeAd=true")
                    CookieManager.getInstance().setCookie("http://www.forbes.com", "adblock_session=Off")
                    CookieManager.getInstance().setCookie("http://www.forbes.com", "dailyWelcomeCookie=true")
                    if (url.path == "/forbes/welcome/" && mConsecutiveRedirectCount < 5) {
                        mConsecutiveRedirectCount++
                        updateAndLoadUrl("http://www.forbes.com/")
                        return true
                    }
                }
            } catch (e: Exception) {
            }

            mConsecutiveRedirectCount = 0
            updateAndLoadUrl(urlAsString)
            mWebRendererController.resetBubblePanelAdjustment()
            return true
        }

        override fun onLoadUrl(urlAsString: String) {
            try {
                val url = URL(urlAsString)
                mEventHandler.onPageLoading(url)
                updateUrlTitleAndText(urlAsString)
            } catch (e: MalformedURLException) {
                e.printStackTrace()
            }
        }

        override fun onReceivedError() {
            Log.d(TAG, "onReceivedError()")
            mEventHandler.onPageLoaded(true)
            mReloadButton.visibility = VISIBLE
            mShareButton.visibility = GONE
            mArticleModeButton.visibility = GONE
        }

        override fun onPageStarted(urlAsString: String, favIcon: Bitmap?) {
            Log.d(TAG, "onPageStarted() - $urlAsString")
            try {
                CrashTracking.log("onPageStarted(), " + urlAsString + ", index:" + MainController.get()!!.getTabIndex(mOwnerTabView!!))
            } catch (npe: NullPointerException) {
                CrashTracking.log("onPageStarted(), $urlAsString, index: no current MainController")
                Log.e(TAG, npe.localizedMessage, npe)
            }

            if (mLifeState != LifeState.Alive) {
                return
            }

            mPageFinishedLoading = false

            val oldUrl = mWebRenderer.getUrl().toString()

            if (urlAsString == Constant.ABOUT_BLANK_URI) {
                Log.d(TAG, "ignore $urlAsString")
            } else if (updateUrl(urlAsString) == false) {
                var tempResolveInfos: List<ResolveInfo>? = ArrayList()
                if (urlAsString != mContext.getString(R.string.empty_bubble_page)) {
                    tempResolveInfos = Settings.get().getAppsThatHandleUrl(urlAsString, context.packageManager)
                }
                val apps = tempResolveInfos

                val openedInApp = if (apps != null && apps.size > 0) openInApp(apps[0], urlAsString) else false
                if (openedInApp == false) {
                    CrashTracking.log("ContentView.onPageStarted() - openedInApp == false")
                    openInBrowser(urlAsString, false)
                }
                return
            }

            if (oldUrl == Constant.NEW_TAB_URL) {
                MainController.get()!!.saveCurrentTabs()
            }

            mWebRenderer.resetPageInspector()

            val context = context
            val packageManager = context.packageManager

            val currentUrl = mWebRenderer.getUrl()!!

            var tempResolveInfos: List<ResolveInfo>? = ArrayList()
            if (currentUrl.toString() != mContext.getString(R.string.empty_bubble_page)) {
                tempResolveInfos = Settings.get().getAppsThatHandleUrl(currentUrl.toString(), context.packageManager)
            }

            updateAppsForUrl(tempResolveInfos, currentUrl)
            if (Settings.get().redirectUrlToBrowser(currentUrl)) {
                CrashTracking.log("ContentView.onPageStarted() - url redirects to browser")
                if (openInBrowser(urlAsString, false)) {
                    val title = String.format(context.getString(R.string.link_redirected), Settings.get().getDefaultBrowserLabel())
                    MainApplication.saveUrlInHistory(context, null, urlAsString, title)
                    return
                }
            }

            if (mHandledAppPickerForCurrentUrl == false
                    && mUsingPeekAsDefaultForCurrentUrl == false
                    && mAppsForUrl.size > 0
                    && Settings.get().didRecentlyRedirectToApp(urlAsString) == false) {

                val defaultAppForUrl = getDefaultAppForUrl()
                if (defaultAppForUrl != null) {
                    if (Util.isPeekResolveInfo(defaultAppForUrl.mResolveInfo)) {
                        mUsingPeekAsDefaultForCurrentUrl = true
                    } else {
                        if (openInApp(defaultAppForUrl.mResolveInfo!!, urlAsString)) {
                            return
                        }
                    }
                } else {
                    var isPeekPresent = false
                    //boolean isPeekPresent = mAppsForUrl.size() == 1 ? Util.isPeekResolveInfo(mAppsForUrl.get(0).mResolveInfo) : false;
                    for (info in mAppsForUrl) {

                        // Handle crash: mResolveInfo/activityInfo can be null for a disabled/uninstalled entry.
                        if (info.mResolveInfo == null || info.mResolveInfo!!.activityInfo == null) {
                            CrashTracking.log("onPageStarted() Null resolveInfo when getting default for app: $info")
                            continue
                        }

                        if (info.mResolveInfo!!.activityInfo.packageName.startsWith("com.peek.browser.playstore")) {
                            isPeekPresent = true
                            break
                        }
                    }

                    if (isPeekPresent == false && MainApplication.sShowingAppPickerDialog == false &&
                            mHandledAppPickerForCurrentUrl == false && mAppPickersUrls.contains(urlAsString) == false) {
                        val resolveInfos = ArrayList<ResolveInfo>()
                        for (appForUrl in mAppsForUrl) {
                            if (appForUrl.mResolveInfo != null) {
                                resolveInfos.add(appForUrl.mResolveInfo!!)
                            }
                        }
                        if (0 != resolveInfos.size) {
                            val dialog = ActionItem.getActionItemPickerAlert(context, resolveInfos, R.string.pick_default_app,
                                    object : ActionItem.OnActionItemDefaultSelectedListener {
                                        override fun onSelected(actionItem: ActionItem, always: Boolean) {
                                            CrashTracking.log("onPageStarted(): OnActionItemDefaultSelectedListener.onSelected()")
                                            var loaded = false
                                            val appPackageName = context.packageName
                                            for (resolveInfo in resolveInfos) {
                                                if (resolveInfo.activityInfo.packageName == actionItem.mPackageName
                                                        && resolveInfo.activityInfo.name == actionItem.mActivityClassName) {
                                                    if (always) {
                                                        Settings.get().setDefaultApp(urlAsString, resolveInfo)
                                                    }

                                                    // Jump out of the loop and load directly via a BubbleView below
                                                    if (resolveInfo.activityInfo.packageName == appPackageName) {
                                                        break
                                                    }

                                                    mInitialUrlLoadStartTime = -1
                                                    loaded = MainApplication.loadIntent(context, actionItem.mPackageName,
                                                            actionItem.mActivityClassName, urlAsString, -1, true)
                                                    break
                                                }
                                            }

                                            if (loaded) {
                                                if (MainController.get() != null) {
                                                    MainController.get()!!.closeTab(mOwnerTabView, MainController.get()!!.contentViewShowing(), false)
                                                }
                                                Settings.get().addRedirectToApp(urlAsString)
                                            }
                                            // NOTE: no need to call loadUrl(urlAsString) or anything in the event the link is to be handled by
                                            // Link Bubble. The flow already assumes that will happen by continuing the load when the Dialog displays. #244
                                        }
                                    })

                            dialog.setOnDismissListener {
                                MainApplication.sShowingAppPickerDialog = false
                            }

                            dialog.window!!.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                            dialog.show()
                            MainApplication.sShowingAppPickerDialog = true
                            mHandledAppPickerForCurrentUrl = true
                            mAppPickersUrls.add(urlAsString)
                        }
                    }
                }
            }

            configureOpenInAppButton()
            configureArticleModeButton()
            Log.d(TAG, "redirect to url: $urlAsString")
            mEventHandler.onPageLoading(mWebRenderer.getUrl()!!)
            updateUrlTitleAndText(urlAsString)

            if (mShareButton.visibility == GONE) {
                mShareButton.visibility = VISIBLE
            }

            if (urlAsString == context.getString(R.string.empty_bubble_page) && MainController.get() != null) {
                MainController.get()!!.displayTab(mOwnerTabView!!)
            }
        }

        override fun onPageFinished(urlAsString: String) {
            if (mLifeState != LifeState.Alive) {
                return
            }
            if (mIgnoreNextOnPageFinished) {
                mIgnoreNextOnPageFinished = false
                Log.d(TAG, "onPageFinished() - ignoring because of mIgnoreNextOnPageFinished...")
                return
            }

            val debugIndex = if (MainController.get() != null) MainController.get()!!.getTabIndex(mOwnerTabView!!) else null
            CrashTracking.log("onPageFinished(), " + (if (debugIndex != null) "index:$debugIndex" else "<MainController.get() == null>"))

            // This should not be necessary, but unfortunately is.
            // Often when pressing Back, onPageFinished() is mistakenly called when progress is 0. #245
            if (mCurrentProgress != 100) {
                mPageFinishedIgnoredUrl = urlAsString
                return
            }

            onPageLoadComplete(urlAsString)
            if (null != MainController.get() && MainController.get()!!.currentTab !== mOwnerTabView) {
                mWebRenderer.pauseOnSetInactive()
            }
            if (mUrlTextView.text.toString() == context.getString(R.string.empty_bubble_page)) {
                mTitleTextView.performClick()
            }
        }

        override fun onDownloadStart(urlAsString: String) {
            this@ContentView.onDownloadStart(urlAsString)
        }

        override fun onReceivedTitle(url: String?, title: String) {
            if (title == null || title.isEmpty()) {
                return
            }

            if (url != null && url == context.getString(R.string.empty_bubble_page)) {
                mTitleTextView.setTextColor(0xFFFFFFFF.toInt())
            }
            mTitleTextView.text = title
            if (MainApplication.sTitleHashMap != null && url != null) {
                MainApplication.sTitleHashMap!![url] = title
            }
        }

        override fun onReceivedIcon(bitmap: Bitmap) {

            // Only pass this along if the page has finished loading.
            // This is to prevent passing a stale icon along when a redirect has already occurred. This shouldn't cause
            // too many ill-effects, because BitmapView attempts to load host/favicon.ico automatically anyway.
            if (mPageFinishedLoading) {
                if (mEventHandler.onReceivedIcon(bitmap)) {
                    val faviconUrl = Util.getDefaultFaviconUrl(mWebRenderer.getUrl()!!)
                    MainApplication.sFavicons!!.putFaviconInMemCache(faviconUrl, bitmap)
                }
            }
        }

        // Hacky variables to get around version 40 of Android System WebView returning "about:blank"
        // urls.
        // * mIgnoreNextOnProgressChanged is necessary to ignore the 100 progress that comes in with a
        //      null urlAsString.
        // * mIgnoreNextOnPageFinished is necessary to ignore the ensuing onPageFinished() call.
        //
        // Both of these hacks combine to allow links to load correctly using WebView, and have the
        // progress indicator display as expected.
        var mIgnoreNextOnProgressChanged = false
        var mIgnoreNextOnPageFinished = false
        override fun onProgressChanged(progress: Int, urlAsString: String?) {
            if (urlAsString == null) {
                Log.d(TAG, "onProgressChanged(): ignore, no url")
                mIgnoreNextOnProgressChanged = true
                return
            } else if (mIgnoreNextOnProgressChanged) {
                Log.d(TAG, "onProgressChanged(): ignoring next value...")
                mIgnoreNextOnProgressChanged = false
                mIgnoreNextOnPageFinished = true
                return
            }

            Log.d(TAG, "onProgressChanged() - progress:$progress, $urlAsString")

            mCurrentProgress = progress

            // Note: annoyingly, onProgressChanged() can be called with values from a previous url.
            // Eg, "http://t.co/fR9bzpvyLW" redirects to "http://on.recode.net/1eOqNVq" which redirects to
            // "http://recode.net/2014/01/20/...", and after the "on.recode.net" redirect, progress is 100 for a moment.
            mEventHandler.onProgressChanged(progress)

            if (progress == 100 && mPageFinishedIgnoredUrl != null && mPageFinishedIgnoredUrl == urlAsString) {
                onPageLoadComplete(urlAsString)
            }
        }

        override fun onBackPressed(): Boolean {
            return this@ContentView.onBackPressed()
        }

        override fun onUrlLongClick(webView: WebView, url: String, type: Int) {
            this@ContentView.onUrlLongClick(webView, url, type)
        }

        override fun onShowBrowserPrompt() {
            this@ContentView.onShowBrowserPrompt()
        }

        override fun onCloseWindow() {
            CrashTracking.log("WebRenderer.Controller.onCloseWindow()")
            if (MainController.get() != null) {
                MainController.get()!!.closeTab(mOwnerTabView, true, true)
            }
        }

        private val mHandler = Handler()

        override fun onPageInspectorTouchIconLoaded(bitmap: Bitmap, pageUrl: String?) {
            if (bitmap == null || pageUrl == null) {
                return
            }

            mHandler.post {
                val url = mWebRenderer.getUrl()
                if (url != null && url.toString() == pageUrl) {
                    mEventHandler.onReceivedIcon(bitmap)

                    val faviconUrl = Util.getDefaultFaviconUrl(url)
                    MainApplication.sFavicons!!.putFaviconInMemCache(faviconUrl, bitmap)
                }
            }
        }

        override fun onPageInspectorDropDownWarningClick() {
            mHandler.post {
                showOpenInBrowserPrompt(R.string.unsupported_drop_down_default_browser,
                        R.string.unsupported_drop_down_no_default_browser, mWebRenderer.getUrl().toString())
            }
        }

        override fun onPagedInspectorThemeColorFound(color: Int) {
            mHandler.post {
                updateColors(color)
                mEventHandler.onThemeColor(color)
            }
        }

        override fun onArticleContentReady(articleContent: ArticleContent?) {
            Log.d("Article", "onArticleContentReady() - " + (if (articleContent == null) "<null>" else "valid"))
            configureArticleModeButton()
        }

    }

    private var mPageFinishedIgnoredUrl: String? = null

    fun onPageLoadComplete(urlAsString: String) {

        mPageFinishedLoading = true

        // NOTE: *don't* call updateUrl() here. Turns out, this function is called after a redirect has occurred.
        // Eg, urlAsString "t.co/xyz" even after the next redirect is starting to load

        // Check exact equality first for common case to avoid an allocation.
        val currentUrl = mWebRenderer.getUrl()!!
        var equalUrl = currentUrl.toString() == urlAsString

        if (!equalUrl) {
            try {
                val url = URL(urlAsString)

                if (url.protocol == currentUrl.protocol &&
                        url.host == currentUrl.host &&
                        url.path == currentUrl.path) {
                    equalUrl = true
                }
            } catch (e: MalformedURLException) {
            }
        }

        mWebRenderer.runPageInspector()

        if (equalUrl) {
            updateAppsForUrl(currentUrl)
            configureOpenInAppButton()
            configureArticleModeButton()

            mEventHandler.onPageLoaded(false)
            Log.e(TAG, "onPageLoadComplete() - url: $urlAsString")

            var title: String? = if (MainApplication.sTitleHashMap != null) MainApplication.sTitleHashMap!![urlAsString] else ""
            if (TextUtils.isEmpty(title)) {
                // Note: it's possible for title == null above, but there be a valid title in the following case:
                // * title set for http://url.com/page?arg=1
                // * urlAsString now changed to http://url.com/page, which isn't in sTitleHashMap
                // In this case, if there's a valid title, keep using it.
                if (mTitleTextView.text != null) {
                    val currentTitle = mTitleTextView.text.toString()
                    if (currentTitle == mLoadingString == false) {
                        title = currentTitle
                    }
                }

                // If no title is set, display nothing rather than "Loading..." #265
                if (title == null) {
                    mTitleTextView.text = null
                }
            }

            if (currentUrl.toString() != context.getString(R.string.empty_bubble_page)) {
                val settings = Settings.get()
                if (!settings.isIncognitoMode) {
                    // Adding the URL to the auto suggestions list
                    mAdapter.addUrlToAutoSuggestion(currentUrl.toString())
                    MainApplication.saveUrlInHistory(context, null, currentUrl.toString(), currentUrl.host, title)
                }

            } else {
                mTitleTextView.performClick()
            }
            //mDelayedAutoContentDisplayLinkLoadedScheduled = true;
            //Log.d(TAG, "set mDelayedAutoContentDisplayLinkLoadedScheduled=" + mDelayedAutoContentDisplayLinkLoadedScheduled);
            postDelayed(mDelayedAutoContentDisplayLinkLoadedRunnable, Constant.AUTO_CONTENT_DISPLAY_DELAY.toLong())

            mWebRenderer.onPageLoadComplete()
            mWebRenderer.getView()!!.requestFocus()
        }

        mPageFinishedIgnoredUrl = null
    }

    //boolean mDelayedAutoContentDisplayLinkLoadedScheduled = false;

    // Call autoContentDisplayLinkLoaded() via a delay so as to fix #412
    val mDelayedAutoContentDisplayLinkLoadedRunnable: Runnable = Runnable {
        if (mLifeState == LifeState.Alive && MainController.get() != null) {
            //Log.e(TAG, "*** set mDelayedAutoContentDisplayLinkLoadedScheduled=" + mDelayedAutoContentDisplayLinkLoadedScheduled);
            MainController.get()!!.autoContentDisplayLinkLoaded(mOwnerTabView!!)
            saveLoadTime()
        }
    }

    val mOnShareButtonClickListener = OnClickListener {
        showSelectShareMethod(mWebRenderer.getUrl().toString(), true)
    }

    val mbtClearUrlClicked = OnClickListener {
        metUrl.setText("")
        mbtUrlClear.isEnabled = false
        mbtUrlClear.background.alpha = 50
    }

    val murlOnFocusChangeListener = OnFocusChangeListener { view, b ->
        if (!b) {
            // Show the toolbar again if lost focus and hide the soft keyboard
            findViewById<View>(R.id.content_toolbar).bringToFront()

            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(metUrl.windowToken,
                    InputMethodManager.RESULT_UNCHANGED_SHOWN)
        }
    }

    val murlOnItemClickListener = AdapterView.OnItemClickListener { adapterView, view, i, l ->
        // Hide the soft keyboard
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(metUrl.windowToken,
                InputMethodManager.RESULT_UNCHANGED_SHOWN)

        val urlSuggestion = adapterView.getItemAtPosition(i) as SearchURLSuggestions

        WorkWithURL(urlSuggestion.Name!!, urlSuggestion.EngineToUse, false)
    }

    val murlActionListener = TextView.OnEditorActionListener { v, actionId, event ->
        if (actionId == EditorInfo.IME_ACTION_GO) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(metUrl.windowToken,
                    InputMethodManager.RESULT_UNCHANGED_SHOWN)

            val urlText = metUrl.text.toString()
            var urlToCheck = urlText
            if (!urlToCheck.startsWith(context.getString(R.string.http_prefix)) &&
                    !urlToCheck.startsWith(context.getString(R.string.https_prefix)))
                urlToCheck = context.getString(R.string.http_prefix) + urlToCheck
            if (Util.isValidURL(context, urlToCheck)) {
                WorkWithURL(urlText, SearchURLSuggestions.SearchEngine.NONE, true)
            } else if (null != mFirstSuggestedItem) {
                val strUrl = mFirstSuggestedItem!!.Name!!

                WorkWithURL(strUrl, SearchURLSuggestions.SearchEngine.NONE, true)
            }
        }

        false
    }

    val murlKeyListener = View.OnKeyListener { v, keyCode, event ->
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                return@OnKeyListener onBackPressed()
            }
        }
        false
    }

    val mDataSetObserver: DataSetObserver = object : DataSetObserver() {
        override fun onChanged() {
            super.onChanged()
            if (mAdapter.count > 0) {
                mFirstSuggestedItem = mAdapter.getItem(0) as SearchURLSuggestions
            }
            val metrics = context.resources.displayMetrics
            var pixels: Int
            if (mAdapter.count > mRowsToShowOnAutoSuggestions) {
                val dp = mOneRowAutoSuggestionsSize * mRowsToShowOnAutoSuggestions
                val fpixels = metrics.density * dp
                pixels = (fpixels + 0.5f).toInt()
            } else {
                val dp = mOneRowAutoSuggestionsSize
                val fpixels = metrics.density * dp
                pixels = (fpixels + 0.5f).toInt() * mAdapter.count
            }

            metUrl.dropDownHeight = pixels

            // Set an autosuggestion
            val urlText = metUrl.text.toString()
            if (mApplyAutoSuggestionToUrlString && 0 != urlText.length && null != mFirstSuggestedItem &&
                    SearchURLSuggestions.SearchEngine.NONE == mFirstSuggestedItem!!.EngineToUse) {
                val suggestedString = mFirstSuggestedItem!!.Name!!
                var stringToAppend = ""
                if (suggestedString.length > urlText.length) {
                    stringToAppend = suggestedString.substring(urlText.length)
                    val toCompare = suggestedString.substring(0, urlText.length)
                    if (toCompare == urlText) {
                        mSetTheRealUrlString = false
                        mPreviousMetUrl = metUrl.text.toString()
                        mPreviousAppendedString = stringToAppend
                        metUrl.setText(urlText + stringToAppend)
                        mSetTheRealUrlString = true
                        metUrl.setSelection(urlText.length, urlText.length + stringToAppend.length)
                    }
                }
            }
        }
    }

    val murlTextWatcher: TextWatcher = object : TextWatcher {
        private var mBeforeTextString: String? = null
        private var mApplyAutoSuggestion = true

        override fun beforeTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {
            mBeforeTextString = metUrl.text.toString()
        }

        override fun onTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {
            val urlText = metUrl.text.toString()
            if (mPreviousMetUrl != null && mPreviousAppendedString != null) {
                // Small hack to get around the autocomplete bug when using ZenUI keyboard
                val previousUrlText = mPreviousMetUrl + mPreviousAppendedString
                val difference = urlText.length - previousUrlText.length
                if (difference == 1) {
                    val urtlTextStart = urlText.substring(0, mPreviousMetUrl!!.length)
                    val urtlTextEnd = urlText.substring(mPreviousMetUrl!!.length + 1, urlText.length)
                    if (urtlTextStart == mPreviousMetUrl && urtlTextEnd == mPreviousAppendedString) {
                        val textToSet = urlText.substring(0, mPreviousMetUrl!!.length + 1)
                        metUrl.setText(textToSet)
                        metUrl.setSelection(textToSet.length)
                    }
                }
            }
            mApplyAutoSuggestion = if (!mFirstTimeUrlTyped &&
                    (urlText == mAdapter.mRealUrlBarConstraint || mAdapter.mRealUrlBarConstraint.length > urlText.length)) {
                false
            } else {
                true
            }
            if (mSetTheRealUrlString) {
                mAdapter.mRealUrlBarConstraint = urlText
            }
            mFirstTimeUrlTyped = false
        }

        override fun afterTextChanged(editable: Editable) {
            val urlText = metUrl.text.toString()
            mApplyAutoSuggestionToUrlString = if (!mApplyAutoSuggestion) {
                false
            } else {
                true
            }
            if (urlText.length != 0) {
                mbtUrlClear.isEnabled = true
                mbtUrlClear.background.alpha = 255
            } else {
                mbtUrlClear.isEnabled = false
                mbtUrlClear.background.alpha = 50
            }
        }
    }

    val mOnURLEnterClicked = OnClickListener { view ->
        if (mWebRenderer.getUrl().toString() != context.getString(R.string.empty_bubble_page)) {
            metUrl.setText(mWebRenderer.getUrl().toString())
        } else {
            metUrl.setText("")
        }
        mFirstTimeUrlTyped = true
        // Bring the search URL layout on top
        findViewById<View>(R.id.content_edit_url).bringToFront()

        // Request the focus for the search URL control
        metUrl.requestFocus()
        metUrl.selectAll()
        // Show the soft keyboard
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY)
        imm.showSoftInput(metUrl, InputMethodManager.SHOW_FORCED)
    }

    val mOnOpenInAppButtonClickListener = object : OpenInAppButton.OnOpenInAppClickListener {

        override fun onAppOpened() {
            CrashTracking.log("mOnOpenInAppButtonClickListener.onAppOpened()")
            if (MainController.get() != null) {
                MainController.get()!!.closeTab(mOwnerTabView, true, false)
                // L_WATCH: L currently lacks getRecentTasks(), so minimize here
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.KITKAT) {
                    MainController.get()!!.switchToBubbleView(false)
                }
            }
        }

    }

    val mOnReloadButtonClickListener = OnClickListener {
        mReloadButton.visibility = GONE
        mWebRenderer.reload()
    }

    val mArticleModeController: ArticleRenderer.Controller = object : ArticleRenderer.Controller {

        override fun onUrlLongClick(webView: WebView, url: String, type: Int) {
            this@ContentView.onUrlLongClick(webView, url, type)
        }

        override fun onDownloadStart(urlAsString: String) {
            this@ContentView.onDownloadStart(urlAsString)
        }

        override fun onBackPressed(): Boolean {
            return this@ContentView.onBackPressed()
        }

        override fun onShowBrowserPrompt() {
            this@ContentView.onShowBrowserPrompt()
        }

        override fun onFirstPageLoadStarted() {
            // Ugly hack to get ensure the Back button works in Article mode
            if (mArticleModeButton.getState() == ArticleModeButton.State.Web) {
                mWebRenderer.getView()!!.visibility = View.INVISIBLE
            }
        }
    }

    val mOnArticleModeButtonClickListener = OnClickListener {
        mArticleModeButton.toggleState()
        mArticleModeButton.updateTheme(themeColor)

        val articleContent = mWebRenderer.articleContent

        when (mArticleModeButton.getState()!!) {
            ArticleModeButton.State.Article -> {
                if (mArticleRenderer != null && mArticleRenderer!!.getView() != null) {
                    mArticleRenderer!!.getView().visibility = View.INVISIBLE
                }
                mWebRenderer.getView()!!.visibility = View.VISIBLE
            }

            ArticleModeButton.State.Web -> {
                if (mArticleRenderer == null) {
                    val articleRendererPlaceholder = findViewById<View>(R.id.article_renderer_placeholder)
                    mArticleRenderer = ArticleRenderer(context, mArticleModeController, articleContent!!, articleRendererPlaceholder)
                } else {
                    mArticleRenderer!!.display(articleContent!!)
                    mWebRenderer.getView()!!.visibility = View.INVISIBLE
                }
                mArticleRenderer!!.getView().visibility = VISIBLE
            }
        }
    }

    val mOnOverflowButtonClickListener = OnClickListener {
        val context = context
        mOverflowPopupMenu = PopupMenu(context, mOverflowButton)
        val resources = context.resources
        val siteProtection = mOverflowPopupMenu!!.menu.add(Menu.NONE, R.id.item_site_protection, Menu.NONE, resources.getString(R.string.action_site_protection))
                .setCheckable(true)
                .setChecked(!mHostInWhiteList)
        if (mCurrentProgress != 100) {
            mOverflowPopupMenu!!.menu.add(Menu.NONE, R.id.item_stop, Menu.NONE, resources.getString(R.string.action_stop))
        }
        mOverflowPopupMenu!!.menu.add(Menu.NONE, R.id.item_reload_page, Menu.NONE, resources.getString(R.string.action_reload_page))

        val defaultBrowserLabel = Settings.get().getDefaultBrowserLabel()
        if (defaultBrowserLabel != null) {
            mOverflowPopupMenu!!.menu.add(Menu.NONE, R.id.item_open_in_browser, Menu.NONE,
                    String.format(resources.getString(R.string.action_open_in_browser), defaultBrowserLabel))
        }

        val requestDesktopSite = mOverflowPopupMenu!!.menu.add(Menu.NONE, R.id.item_request_desktop_site, Menu.NONE, resources.getString(R.string.action_request_desktop_site))
                .setCheckable(true)
                .setChecked(mWebRenderer.getUserAgentString(mContext) == Constant.USER_AGENT_CHROME_DESKTOP)

        mOverflowPopupMenu!!.menu.add(Menu.NONE, R.id.item_copy_link, Menu.NONE, resources.getString(R.string.action_copy_to_clipboard))
        mOverflowPopupMenu!!.menu.add(Menu.NONE, R.id.item_new_bubble, Menu.NONE, resources.getString(R.string.action_new_bubble))
        mOverflowPopupMenu!!.menu.add(Menu.NONE, R.id.item_close_tab, Menu.NONE, resources.getString(R.string.action_close_tab))
        mOverflowPopupMenu!!.menu.add(Menu.NONE, R.id.item_settings, Menu.NONE, resources.getString(R.string.action_settings))
        mOverflowPopupMenu!!.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.item_site_protection -> {
                    CrashTracking.log("R.id.item_site_protection")
                    // This looks backwards, but is correct, as isChecked() isn't true until
                    // after onMenuItemClick() is called.
                    AddRemoveHostFromWhiteList(mWebRenderer.getUrl().toString(), siteProtection.isChecked)
                    HostInWhiteListCheck(mWebRenderer.getUrl().toString())
                    // We need to go to reload page case.
                    mWebRenderer.resetPageInspector()
                    var currentUrl = mWebRenderer.getUrl()!!
                    mEventHandler.onPageLoading(currentUrl)
                    mWebRenderer.stopLoading()
                    mWebRenderer.reload()
                    var urlAsString = currentUrl.toString()
                    updateAppsForUrl(currentUrl)
                    configureOpenInAppButton()
                    configureArticleModeButton()
                    Log.d(TAG, "reload url: $urlAsString")
                    mInitialUrlLoadStartTime = System.currentTimeMillis()
                    updateUrlTitleAndText(urlAsString)
                }

                R.id.item_reload_page -> {
                    CrashTracking.log("R.id.item_reload_page")
                    mWebRenderer.resetPageInspector()
                    val currentUrl = mWebRenderer.getUrl()!!
                    mEventHandler.onPageLoading(currentUrl)
                    mWebRenderer.stopLoading()
                    mWebRenderer.reload()
                    val urlAsString = currentUrl.toString()
                    updateAppsForUrl(currentUrl)
                    configureOpenInAppButton()
                    configureArticleModeButton()
                    Log.d(TAG, "reload url: $urlAsString")
                    mInitialUrlLoadStartTime = System.currentTimeMillis()
                    updateUrlTitleAndText(urlAsString)
                }

                R.id.item_open_in_browser -> {
                    CrashTracking.log("ContentView.setOnMenuItemClickListener() - open in browser clicked")
                    openInBrowser(mWebRenderer.getUrl().toString(), true)
                }

                R.id.item_request_desktop_site -> {
                    val newUserAgentString: String
                    // This looks backwards, but is correct, as isChecked() isn't true until
                    // after onMenuItemClick() is called.
                    if (!requestDesktopSite.isChecked) {
                        newUserAgentString = Constant.USER_AGENT_CHROME_DESKTOP
                    } else {
                        val defaultUserAgentString = Settings.get().getUserAgentString()
                        newUserAgentString = if (defaultUserAgentString != null
                                && defaultUserAgentString != Constant.USER_AGENT_CHROME_DESKTOP) {
                            defaultUserAgentString
                        } else {
                            Util.getDefaultUserAgentString(context)
                        }
                    }

                    mWebRenderer.setUserAgentString(newUserAgentString)
                    mWebRenderer.reload()
                }

                R.id.item_copy_link -> {
                    MainApplication.copyLinkToClipboard(context, mWebRenderer.getUrl().toString(), R.string.bubble_link_copied_to_clipboard)
                }

                R.id.item_stop -> {
                    mWebRenderer.stopLoading()
                }

                R.id.item_new_bubble -> {
                    MainApplication.openLink(context, context.getString(R.string.empty_bubble_page),
                            SourceTag.OPENED_URL_FROM_NEW_TAB)
                }

                R.id.item_close_tab -> {
                    CrashTracking.log("R.id.item_close_tab")
                    if (MainController.get() != null) {
                        MainController.get()!!.closeTab(mOwnerTabView, MainController.get()!!.contentViewShowing(), true)
                    }
                }

                R.id.item_settings -> {
                    val intent = Intent(context, SettingsActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    context.startActivity(intent)
                    MainController.get()!!.switchToBubbleView(false)
                }
            }
            mOverflowPopupMenu = null
            false
        }
        mOverflowPopupMenu!!.setOnDismissListener { menu ->
            if (mOverflowPopupMenu === menu) {
                mOverflowPopupMenu = null
            }
        }
        mOverflowPopupMenu!!.show()
    }

    val mOnTextContainerTouchListener: OnSwipeTouchListener = object : OnSwipeTouchListener() {
        override fun onSwipeRight() {
            MainController.get()!!.showPreviousBubble()
        }

        override fun onSwipeLeft() {
            MainController.get()!!.showNextBubble()
        }
    }

    private fun onDownloadStart(urlAsString: String) {
        CrashTracking.log("onDownloadStart()")
        openInBrowser(urlAsString, false)
        if (MainController.get() != null) {
            MainController.get()!!.closeTab(mOwnerTabView, true, false)
            // L_WATCH: L currently lacks getRecentTasks(), so minimize here
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.KITKAT) {
                MainController.get()!!.switchToBubbleView(false)
            }
        }
    }

    private fun onBackPressed(): Boolean {
        if (mUrlStack.size <= 1) {
            CrashTracking.log("onBackPressed() - closeTab()")
            if (MainController.get() != null) {
                MainController.get()!!.closeTab(mOwnerTabView, BubbleAction.BackButton, true, true)
            }
            return true
        } else {
            CrashTracking.log("onBackPressed() - go back")
            mWebRenderer.stopLoading()
            val urlBefore = mWebRenderer.getUrl().toString()

            mUrlStack.pop()
            val previousUrl = mUrlStack.peek()
            val previousUrlAsString = previousUrl.toString()
            mEventHandler.onCanGoBackChanged(mUrlStack.size > 1)
            mHandledAppPickerForCurrentUrl = false
            mUsingPeekAsDefaultForCurrentUrl = false
            Log.d(TAG, "[urlstack] Go back: $urlBefore -> ${mWebRenderer.getUrl()}, urlStack.size():${mUrlStack.size}")
            updateAndLoadUrl(previousUrlAsString)
            updateUrlTitleAndText(previousUrlAsString)

            mEventHandler.onPageLoading(mWebRenderer.getUrl()!!)

            updateAppsForUrl(null, previousUrl)
            configureOpenInAppButton()
            configureArticleModeButton()

            mWebRenderer.resetPageInspector()
            // The WebView doesn't reload on all pages correctly if call only loadUrl, seems like there is some kind of cache as
            // it loads fast on back but doesn't load pictures for thestar.com website. clearCache method doesn't work also. Only
            // reload works nice here. Perhaps it is some bu in API as lots of people say that problem with loadUrl method
            // That is the temp fix, thestar.com has a new beta website and it works great with it without reloading
            if (previousUrlAsString.endsWith("m.thestar.com/#/?referrer=")) {
                mWebRenderer.reload()
            }

            return true
        }
    }

    fun onShowBrowserPrompt() {
        showOpenInBrowserPrompt(R.string.long_press_unsupported_default_browser,
                R.string.long_press_unsupported_no_default_browser, mWebRenderer.getUrl().toString())

    }

    private fun onUrlLongClick(webView: WebView, urlAsString: String, type: Int) {
        val resources = resources

        val longClickSelections = ArrayList<String>()

        val shareLabel = resources.getString(R.string.action_share)
        longClickSelections.add(shareLabel)

        val defaultBrowserLabel = Settings.get().getDefaultBrowserLabel()

        val leftConsumeBubbleLabel = Settings.get().getConsumeBubbleLabel(BubbleAction.ConsumeLeft)
        if (leftConsumeBubbleLabel != null) {
            if (defaultBrowserLabel == null || defaultBrowserLabel == leftConsumeBubbleLabel == false) {
                longClickSelections.add(leftConsumeBubbleLabel)
            }
        }

        val rightConsumeBubbleLabel = Settings.get().getConsumeBubbleLabel(BubbleAction.ConsumeRight)
        if (rightConsumeBubbleLabel != null) {
            if (defaultBrowserLabel == null || defaultBrowserLabel == rightConsumeBubbleLabel == false) {
                longClickSelections.add(rightConsumeBubbleLabel)
            }
        }

        // Long pressing for a link doesn't work reliably, re #279
        //final String copyLinkLabel = resources.getString(R.string.action_copy_to_clipboard);
        //longClickSelections.add(copyLinkLabel);

        Collections.sort(longClickSelections)

        val openLinkInNewBubbleLabel = resources.getString(R.string.action_open_link_in_new_bubble)
        val openImageInNewBubbleLabel = resources.getString(R.string.action_open_image_in_new_bubble)
        if (type == WebView.HitTestResult.IMAGE_TYPE || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            longClickSelections.add(0, openImageInNewBubbleLabel)
        }
        if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            longClickSelections.add(0, openLinkInNewBubbleLabel)
        }

        val openInBrowserLabel = if (defaultBrowserLabel != null)
            String.format(resources.getString(R.string.action_open_in_browser), defaultBrowserLabel)
        else null
        if (openInBrowserLabel != null) {
            longClickSelections.add(1, openInBrowserLabel)
        }

        val saveImageLabel = resources.getString(R.string.action_save_image)
        if (type == WebView.HitTestResult.IMAGE_TYPE ||
                type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            longClickSelections.add(saveImageLabel)
        }

        val listView = ListView(context)
        listView.adapter = ArrayAdapter<String>(context, android.R.layout.simple_list_item_1,
                longClickSelections.toTypedArray())
        listView.onItemClickListener = AdapterView.OnItemClickListener { parent, view, position, id ->
            CrashTracking.log("ContentView listView.setOnItemClickListener")
            val string = longClickSelections[position]
            if (string == openLinkInNewBubbleLabel && type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                val msg = Message()
                msg.target = object : Handler() {
                    override fun handleMessage(msg: Message) {
                        val b = msg.data
                        if (b != null && b.getString("url") != null) {
                            MainController.get()!!.openUrl(b.getString("url")!!, System.currentTimeMillis(), false, SourceTag.OPENED_URL_FROM_NEW_TAB)
                        }
                    }
                }
                webView.requestFocusNodeHref(msg)
            }
            if (string == openLinkInNewBubbleLabel || string == openImageInNewBubbleLabel) {
                MainController.get()!!.openUrl(urlAsString, System.currentTimeMillis(), false, SourceTag.OPENED_URL_FROM_NEW_TAB)
            } else if (openInBrowserLabel != null && string == openInBrowserLabel) {
                openInBrowser(urlAsString, false)
            } else if (string == shareLabel) {
                showSelectShareMethod(urlAsString, false)
            } else if (string == saveImageLabel) {
                saveImage(urlAsString)
            } else if (leftConsumeBubbleLabel != null && string == leftConsumeBubbleLabel) {
                MainApplication.handleBubbleAction(context, BubbleAction.ConsumeLeft, urlAsString, -1L)
            } else if (rightConsumeBubbleLabel != null && string == rightConsumeBubbleLabel) {
                MainApplication.handleBubbleAction(context, BubbleAction.ConsumeRight, urlAsString, -1L)
                //} else if (string.equals(copyLinkLabel)) {
                //    MainApplication.copyLinkToClipboard(getContext(), urlAsString, R.string.link_copied_to_clipboard);
            }

            if (mLongPressAlertDialog != null) {
                mLongPressAlertDialog!!.dismiss()
            }
        }
        listView.setBackgroundColor(Settings.get().getThemedContentViewColor())

        mLongPressAlertDialog = AlertDialog.Builder(context).create()
        mLongPressAlertDialog!!.setView(listView)
        mLongPressAlertDialog!!.window!!.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        mLongPressAlertDialog!!.show()
    }

    private fun configureOpenInAppButton() {
        if (mOpenInAppButton.configure(mAppsForUrl)) {
            mOpenInAppButton.invalidate()
        } else {
            mOpenInAppButton.visibility = GONE
        }
    }

    private fun configureArticleModeButton() {
        val articleContent = mWebRenderer.articleContent
        if (articleContent != null) {
            if (mArticleNotificationId == -1 && TextUtils.isEmpty(articleContent.mText) == false && Settings.get().getArticleModeOnWearEnabled()) {
                mArticleNotificationId = sNextArticleNotificationId
                sNextArticleNotificationId++

                val title = if (MainApplication.sTitleHashMap != null) MainApplication.sTitleHashMap!![articleContent.mUrl.toString()] else "Open Bubble"

                val context = context

                val closeTabIntent = Intent(context, NotificationCloseTabActivity::class.java)
                closeTabIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                closeTabIntent.putExtra(NotificationCloseTabActivity.EXTRA_DISMISS_NOTIFICATION, mArticleNotificationId)
                val closeTabPendingIntent =
                        PendingIntent.getActivity(context, mArticleNotificationId, closeTabIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                val openTabIntent = Intent(context, NotificationOpenTabActivity::class.java)
                openTabIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_NEW_TASK
                openTabIntent.putExtra(NotificationOpenTabActivity.EXTRA_DISMISS_NOTIFICATION, mArticleNotificationId)
                val openTabPendingIntent = PendingIntent.getActivity(context, System.currentTimeMillis().toInt(), openTabIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                val notification = NotificationCompat.Builder(context, Constant.NOTIFICATION_CHANNEL_ID)
                        .addAction(R.drawable.ic_action_cancel_white, context.getString(R.string.action_close_tab), closeTabPendingIntent)
                        .setContentTitle(title)
                        .setContentText(articleContent.mText)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setGroup(Constant.NOTIFICATION_GROUP_KEY_ARTICLES)
                        .setContentIntent(openTabPendingIntent)
                        .build()

                val notificationManager = NotificationManagerCompat.from(context)
                notificationManager.notify(mArticleNotificationId, notification)
            }

            if (Settings.get().getArticleModeEnabled()) {
                mArticleModeButton.visibility = VISIBLE
            } else {
                mArticleModeButton.visibility = GONE
            }
        } else {
            mArticleModeButton.visibility = GONE
            if (mArticleRenderer != null) {
                mArticleRenderer!!.stopLoading()
            }
            cancelWearNotification()
        }
    }

    private fun cancelWearNotification() {
        if (mArticleNotificationId > -1) {
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.cancel(mArticleNotificationId)
            mArticleNotificationId = -1
        }
    }

    private fun updateAppsForUrl(url: URL) {
        val urlString = url.toString()
        var tempResolveInfos: List<ResolveInfo>? = ArrayList()
        if (urlString != mContext.getString(R.string.empty_bubble_page)) {
            tempResolveInfos = Settings.get().getAppsThatHandleUrl(urlString, context.packageManager)
        }
        val resolveInfos = tempResolveInfos
        updateAppsForUrl(resolveInfos, url)
    }

    private fun updateAppsForUrl(resolveInfos: List<ResolveInfo>?, url: URL) {
        if (resolveInfos != null && resolveInfos.size > 0) {
            mTempAppsForUrl.clear()
            for (resolveInfoToAdd in resolveInfos) {
                if (resolveInfoToAdd.activityInfo != null) {
                    var alreadyAdded = false
                    for (i in mAppsForUrl.indices) {
                        val existing = mAppsForUrl[i]

                        // In certain situations mResolveInfo is null, likely because we can't find the app.
                        // One possibility is that this happens when the app is currently being updated through the play store.
                        if (existing.mResolveInfo == null) {
                            continue
                        }

                        if (existing.mResolveInfo!!.activityInfo.packageName == resolveInfoToAdd.activityInfo.packageName
                                && existing.mResolveInfo!!.activityInfo.name == resolveInfoToAdd.activityInfo.name) {
                            alreadyAdded = true
                            if (existing.mUrl == url == false) {
                                if (url.host.contains(existing.mUrl.host)
                                        && url.host.length > existing.mUrl.host.length) {
                                    // don't update the url in this case. This means prevents, as an example, saving a host like
                                    // "mobile.twitter.com" instead of using "twitter.com". This occurs when loading
                                    // "https://twitter.com/lokibartleby/status/412160702707539968" with Tweet Lanes
                                    // and the official Twitter client installed.
                                } else {
                                    try {
                                        existing.mUrl = URL(url.toString())   // Update the Url
                                    } catch (e: MalformedURLException) {
                                        throw RuntimeException("Malformed URL: $url")
                                    }
                                }
                            }
                            break
                        }
                    }

                    if (alreadyAdded == false) {
                        //if (resolveInfoToAdd.activityInfo.packageName.equals(Settings.get().mPeekEntryActivityResolveInfo.activityInfo.packageName)) {
                        //    continue;
                        //}
                        mTempAppsForUrl.add(resolveInfoToAdd)
                    }
                }
            }

            if (mTempAppsForUrl.size > 0) {
                val currentUrl: URL
                try {
                    currentUrl = URL(url.toString())
                } catch (e: MalformedURLException) {
                    e.printStackTrace()
                    return
                }

                // We need to handle the following case:
                //   * Load reddit.com/r/Android. The app to handle that URL might be "Reddit is Fun" or something similar.
                //   * Click on a link to play.google.com/store/, which is handled by the "Google Play" app.
                //   * The code below adds "Google Play" to the app list that contains "Reddit is Fun",
                //       even though "Reddit is Fun" is not applicable for this link.
                // Unfortunately there is no way reliable way to find out when a user has clicked on a link using the WebView.
                // http://stackoverflow.com/a/17937536/328679 is close, but doesn't work because it relies on onPageFinished()
                // being called, which will not be called if the current page is still loading when the link was clicked.
                //
                // So, in the event contains results, and these results reference a different URL that which matched the
                // resolveInfos passed in, clear mAppsForUrl.
                if (mAppsForUrl.size > 0) {
                    val firstUrl = mAppsForUrl[0].mUrl
                    if ((currentUrl.host.contains(firstUrl.host)
                                    && currentUrl.host.length > firstUrl.host.length) == false) {
                        mAppsForUrl.clear()    // start again
                    }
                }

                for (resolveInfoToAdd in mTempAppsForUrl) {
                    mAppsForUrl.add(AppForUrl(resolveInfoToAdd, currentUrl))
                }
            }

        } else {
            mAppsForUrl.clear()
        }

        var containsPeek = false
        for (appForUrl in mAppsForUrl) {
            if (appForUrl.mResolveInfo != null
                    && appForUrl.mResolveInfo!!.activityInfo != null
                    && appForUrl.mResolveInfo!!.activityInfo.packageName == BuildConfig.APPLICATION_ID) {
                containsPeek = true
                break
            }
        }

        if (containsPeek == false) {
            mAppsForUrl.add(AppForUrl(Settings.get().mPeekEntryActivityResolveInfo, url))
        }
    }

    fun getDefaultAppForUrl(): AppForUrl? {
        if (mAppsForUrl.size > 0) {
            mTempAppsForUrl.clear()
            for (appForUrl in mAppsForUrl) {
                if (appForUrl.mResolveInfo != null) {
                    mTempAppsForUrl.add(appForUrl.mResolveInfo!!)
                }
            }
            if (mTempAppsForUrl.size > 0) {
                val defaultApp = Settings.get().getDefaultAppForUrl(mWebRenderer.getUrl()!!, mTempAppsForUrl)
                if (defaultApp != null) {
                    for (appForUrl in mAppsForUrl) {
                        if (appForUrl.mResolveInfo === defaultApp) {
                            return appForUrl
                        }
                    }
                }
            }
        }

        return null
    }

    fun onAnimateOnScreen() {
        hidePopups()
        resetButtonPressedStates()
    }

    fun onAnimateOffscreen() {
        hidePopups()
        resetButtonPressedStates()
    }

    fun onBeginBubbleDrag() {
        hidePopups()
        resetButtonPressedStates()
    }

    fun onCurrentContentViewChanged(isCurrent: Boolean) {
        hidePopups()
        resetButtonPressedStates()

        if (isCurrent && MainController.get()!!.contentViewShowing()) {
            saveLoadTime()
        }
    }

    fun saveLoadTime() {
        if (mInitialUrlLoadStartTime > -1) {
            Settings.get().trackLinkLoadTime(System.currentTimeMillis() - mInitialUrlLoadStartTime, Settings.LinkLoadType.PageLoad, mWebRenderer.getUrl().toString())
            mInitialUrlLoadStartTime = -1
        }
    }

    fun onOrientationChanged() {
        metUrl.setDropDownWidth(resources.displayMetrics.widthPixels)
        mAdapter.setDropDownWidth(resources.displayMetrics.widthPixels)
    }

    private fun updateUrl(urlAsString: String): Boolean {
        if (urlAsString == "about:blank") {
            Log.d(TAG, "updateUrl(): ignore url:$urlAsString")
            return true
        }
        if ((urlAsString == mWebRenderer.getUrl().toString()) == false) {
            try {
                Log.d(TAG, "change url from " + mWebRenderer.getUrl() + " to " + urlAsString)
                HostInWhiteListCheck(urlAsString)
                mWebRenderer.setUrl(urlAsString)
            } catch (e: MalformedURLException) {
                return false
            }
        }

        return true
    }

    private fun updateAndLoadUrl(urlAsString: String) {
        updateUrl(urlAsString)
        val updatedUrl = getUrl()!!

        var mode = WebRenderer.Mode.Web
        if (Settings.get().getAutoArticleMode()) {
            val path = updatedUrl.path
            if (path != null && path != "" && path != "/") {
                mode = WebRenderer.Mode.Article
            }
        }

        mWebRenderer.loadUrl(updatedUrl, mode)
    }

    private fun cleanVisitedHistory(urlToLook: String) {
        var peekUrl = ""
        if (mUrlStack.size > 0) {
            peekUrl = mUrlStack.peek().toString()
        }
        if (peekUrl == urlToLook) {
            mUrlStack.pop()
            mEventHandler.onCanGoBackChanged(mUrlStack.size > 1)
        }
    }

    private fun getUpdatedUrl(urlAsString: String, cleanVisitedHistory: Boolean): URL? {
        val currentUrl = mWebRenderer.getUrl()!!
        val currentUrlString = currentUrl.toString()
        if ((urlAsString == currentUrl.toString()) == false) {
            if (cleanVisitedHistory) {
                cleanVisitedHistory(currentUrlString)
            }
            try {
                Log.d(TAG, "getUpdatedUrl(): change url from $currentUrlString to $urlAsString")
                return URL(urlAsString)
            } catch (e: MalformedURLException) {
                return null
            }
        }
        return currentUrl
    }

    fun getUrl(): URL? {
        return mWebRenderer.getUrl()
    }

    private fun hidePopups() {
        if (mOverflowPopupMenu != null) {
            mOverflowPopupMenu!!.dismiss()
            mOverflowPopupMenu = null
        }
        if (mLongPressAlertDialog != null) {
            mLongPressAlertDialog!!.dismiss()
            mLongPressAlertDialog = null
        }
        mWebRenderer.hidePopups()
    }

    private fun resetButtonPressedStates() {
        mShareButton.setIsTouched(false)
        mOpenInAppButton.setIsTouched(false)
        mArticleModeButton.setIsTouched(false)
        mOverflowButton.setIsTouched(false)
    }

    private fun openInBrowser(urlAsString: String, canShowUndoPrompt: Boolean = false): Boolean {
        Log.d(TAG, "ContentView.openInBrowser() - url:$urlAsString")
        CrashTracking.log("ContentView.openInBrowser()")
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = Uri.parse(urlAsString)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        if (MainApplication.openInBrowser(context, intent, true) && MainController.get() != null && mOwnerTabView != null) {
            MainController.get()!!.closeTab(mOwnerTabView, MainController.get()!!.contentViewShowing(), canShowUndoPrompt)
            // L_WATCH: L currently lacks getRecentTasks(), so minimize here
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.KITKAT) {
                MainController.get()!!.switchToBubbleView(false)
            }
            return true
        }

        return false
    }

    private fun openInApp(resolveInfo: ResolveInfo, urlAsString: String): Boolean {
        val context = context
        if (MainApplication.loadResolveInfoIntent(context, resolveInfo, urlAsString, mInitialUrlLoadStartTime)) {
            CrashTracking.log("openInApp(): resolveInfo:" + resolveInfo.activityInfo.packageName)
            val title = String.format(context.getString(R.string.link_loaded_with_app),
                    resolveInfo.loadLabel(context.packageManager))
            MainApplication.saveUrlInHistory(context, resolveInfo, urlAsString, title)

            val mainController = MainController.get()
            if (mainController != null) {
                mainController.closeTab(mOwnerTabView, mainController.contentViewShowing(), false)
            }
            Settings.get().addRedirectToApp(urlAsString)

            // L_WATCH: L currently lacks getRecentTasks(), so minimize here
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.KITKAT && null != mainController) {
                mainController.switchToBubbleView(false)
            }
            return true
        }

        return false
    }

    private fun showOpenInBrowserPrompt(hasBrowserStringId: Int, noBrowserStringId: Int, urlAsString: String?) {
        val defaultBrowserLabel = Settings.get().getDefaultBrowserLabel()
        val message: String
        if (defaultBrowserLabel != null) {
            message = String.format(resources.getString(hasBrowserStringId), defaultBrowserLabel)
        } else {
            message = resources.getString(noBrowserStringId)
        }
        Prompt.show(message, resources.getString(android.R.string.ok),
                Prompt.LENGTH_LONG, object : Prompt.OnPromptEventListener {
            override fun onActionClick() {
                if (urlAsString != null) {
                    CrashTracking.log("ContentView.showOpenInBrowserPrompt() - onActionClick()")
                    openInBrowser(urlAsString, false)
                }
            }

            override fun onClose() {
            }
        })
    }

    fun updateUrlTitleAndText(urlAsString: String) {
        var title: String? = if (MainApplication.sTitleHashMap != null) MainApplication.sTitleHashMap!![urlAsString] else null
        val showTitleUrl = urlAsString != context.getString(R.string.empty_bubble_page)
        if (title == null) {
            title = mLoadingString
        } else if (!showTitleUrl) {
            mTitleTextView.setTextColor(0xFFFFFFFF.toInt())
        }
        mTitleTextView.text = title

        if (urlAsString == Constant.NEW_TAB_URL) {
            mUrlTextView.text = null
        } else {
            mUrlTextView.text = urlAsString.replace("http://", "")
            if (!showTitleUrl) {
                mUrlTextView.setTextColor(0xFFFFFFFF.toInt())
            }
        }
    }

    fun getArticleNotificationId(): Int {
        return mArticleNotificationId
    }

    companion object {
        private const val TAG = "UrlLoad"
        private const val DEFAULT_TOOLBAR_SIZE = 112

        private var sNextArticleNotificationId = 1111
    }
}
