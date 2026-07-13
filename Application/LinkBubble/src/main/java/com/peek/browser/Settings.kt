/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Point
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import androidx.preference.PreferenceManager
import android.util.Log
import android.view.View
import com.peek.browser.ui.TabView
import com.peek.browser.util.Analytics
import com.peek.browser.util.CrashTracking
import com.peek.browser.util.EventBus
import com.peek.browser.util.Util
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.MalformedURLException
import java.net.URL
import java.util.TreeMap
import java.util.Vector

class Settings private constructor(private val mContext: Context) {

    private val mSharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(mContext)
    private val mDefaultAppsMap = TreeMap<String, String>()
    private var mBrowsers: MutableList<Intent>? = null
    private var mBrowserPackageNames: MutableList<String>? = null

    @JvmField
    var mPeekEntryActivityResolveInfo: ResolveInfo? = null

    private var mIgnoreLinksFromPackageNames: MutableList<String>? = null
    private var mWebViewBatterySaveMode: WebViewBatterySaveMode? = null

    // The point to save
    private val mBubbleRestingPoint = Point()

    // The point used as the return value. Required so we don't overwrite the desired point in landscape mode
    private val mTempBubbleRestingPoint = Point()

    private var mFallbackRedirectHosts = HashSet<String>()

    enum class WebViewBatterySaveMode {
        Aggressive,
        Default,
        Off,
    }

    enum class ColorTheme {
        Light,
        Dark,
        Palette,
    }

    class OnConsumeBubblesChangedEvent

    private class LastAppRedirect {
        var mUrl: String? = null
        var mTime: Long = 0
    }

    private val mLastAppRedirects = ArrayList<LastAppRedirect>(MAX_LAST_APP_REDIRECT_COUNT)

    init {
        //mDownloadHandlerComponentName = new ComponentName(mContext, DownloadHandlerActivity.class);

        COLOR_WHITE = mContext.resources.getColor(android.R.color.white)
        COLOR_BLACK = mContext.resources.getColor(android.R.color.black)
        COLOR_TEXT_DARK = mContext.resources.getColor(R.color.color_text_dark)
        COLOR_TEXT_LIGHT = mContext.resources.getColor(R.color.color_text_light)
        COLOR_LINK_DARK = mContext.resources.getColor(R.color.color_link_dark)
        COLOR_LINK_LIGHT = mContext.resources.getColor(R.color.color_link_light)
        COLOR_CONTENT_VIEW_DARK = mContext.resources.getColor(R.color.color_content_view_dark)
        COLOR_CONTENT_VIEW_LIGHT = mContext.resources.getColor(R.color.color_content_view_light)
        COLOR_PROGRESS_DARK = mContext.resources.getColor(R.color.color_progress_default_dark)
        COLOR_PROGRESS_LIGHT = mContext.resources.getColor(R.color.color_progress_default_light)

        checkForVersionUpgrade()

        setDefaultRightConsumeBubble()
        setDefaultLeftConsumeBubble()

        if (mSharedPreferences.getBoolean("first_run", true)) {
            val editor = mSharedPreferences.edit()
            editor.putBoolean("first_run", false)
            editor.putLong(LAST_FLUSH_WEBVIEW_CACHE_TIME, System.currentTimeMillis())
            editor.apply()

            val packageManager = mContext.packageManager

            configureDefaultApp(packageManager, "https://www.youtube.com/watch?v=_Aj-PRdU7xA", "com.google.android.youtube")
            configureDefaultApp(packageManager, "https://play.google.com/store/apps/details?id=com.peek.browser.playstore&hl=en", "com.android.vending")
            configureDefaultApp(packageManager, "https://maps.google.com/maps/ms?msid=212078515518849153944.000434d59f7fc56a57668", "com.google.android.apps.maps")
            saveDefaultApps()
        } else {
            // This option is being added in 1.3. For people upgrading from older versions, set the value to force a clear very soon
            if (mSharedPreferences.getLong(LAST_FLUSH_WEBVIEW_CACHE_TIME, -1) == -1L) {
                val editor = mSharedPreferences.edit()
                editor.putLong(LAST_FLUSH_WEBVIEW_CACHE_TIME, System.currentTimeMillis() - Constant.EMPTY_WEBVIEW_CACHE_INTERVAL)
                editor.apply()
            }
            // One-time migration: clear any fallback browser that was auto-selected by a previous version.
            if (!mSharedPreferences.getBoolean(PREFERENCE_DID_RESET_FALLBACK_BROWSER, false)) {
                val editor = mSharedPreferences.edit()
                editor.putBoolean(PREFERENCE_DID_RESET_FALLBACK_BROWSER, true)
                editor.remove(PREFERENCE_DEFAULT_BROWSER_PACKAGE_NAME)
                editor.remove(PREFERENCE_DEFAULT_BROWSER_LABEL)
                editor.apply()
            }
        }

        configureDefaultApps(mSharedPreferences.getString(PREFERENCE_DEFAULT_APPS, null))
        mBubbleRestingPoint.set(-1, -1)

        loadLinkLoadStats()
        loadRecentAppRedirects()
        loadIgnoreLinksFromPackageNames()

        setWebViewBatterySaveMode(mSharedPreferences.getString(PREFERENCE_WEBVIEW_BATTERY_SAVING_MODE, "aggressive")!!)

        val defaultRedirects = HashSet<String>()
        defaultRedirects.add("accounts.google.com")
        configureFallbackRedirectHosts(mSharedPreferences.getStringSet(PREFERENCE_FALLBACK_REDIRECT_HOSTS, defaultRedirects))
    }

    private fun checkForVersionUpgrade() {
        val key = "lastUpgradeVersion"
        val lastUpgradeVersion = mSharedPreferences.getInt(key, -1)
        var upgradeVersionToSet = -1
        if (lastUpgradeVersion < 1) {
            // Remove all defaults to TapPath
            val defaultAppsAsString = mSharedPreferences.getString(PREFERENCE_DEFAULT_APPS, null)

            try {
                if (defaultAppsAsString != null) {
                    val defaultApps = JSONArray(defaultAppsAsString)
                    mDefaultAppsMap.clear()

                    var tapPathFound = false

                    for (i in 0 until defaultApps.length()) {
                        try {
                            val obj = defaultApps.getJSONObject(i)
                            val host = obj.getString(DEFAULT_APPS_MAP_KEY_HOST)
                            val flattenedName = obj.getString(DEFAULT_APPS_MAP_KEY_COMPONENT)
                            if (!flattenedName.contains(BuildConfig.TAP_PATH_PACKAGE_NAME)) {
                                mDefaultAppsMap[host] = flattenedName
                            } else {
                                tapPathFound = true
                                //Log.d("blerg", "ignore " + host + ", " + flattenedName);
                            }

                        } catch (e: JSONException) {
                            e.printStackTrace()
                        }
                    }

                    if (tapPathFound) {
                        saveDefaultApps()
                    }
                }
            } catch (e: JSONException) {
                mDefaultAppsMap.clear()
            }

            upgradeVersionToSet = 1
        }

        if (upgradeVersionToSet > -1) {
            val editor = mSharedPreferences.edit()
            editor.putInt(key, upgradeVersionToSet)
            editor.apply()
        }
    }

    private fun configureDefaultApp(packageManager: PackageManager, urlAsString: String, desiredPackageName: String) {
        try {
            val url = URL(urlAsString)
            var tempResolveInfos: List<ResolveInfo> = ArrayList()
            if (urlAsString != mContext.getString(R.string.empty_bubble_page)) {
                tempResolveInfos = getAppsThatHandleUrl(urlAsString, packageManager) ?: return
            }
            val resolveInfos = tempResolveInfos

            for (resolveInfo in resolveInfos) {
                if (resolveInfo.activityInfo != null) {
                    val packageName = resolveInfo.activityInfo.packageName
                    if (packageName != BuildConfig.APPLICATION_ID && packageName.contains(desiredPackageName)) {
                        setDefaultApp(url.toString(), resolveInfo, false)
                        return
                    }
                }
            }
        } catch (ex: MalformedURLException) {

        }
    }

    fun saveData() {
        saveLinkLoadStats()
        saveRecentAppRedirects()
    }

    fun onOrientationChange() {
        var bubbleRestingX = mBubbleRestingPoint.x
        if (bubbleRestingX == -1) {
            bubbleRestingX = mSharedPreferences.getInt(BUBBLE_RESTING_X, -1)
            if (bubbleRestingX == -1) {
                bubbleRestingX = Config.mBubbleSnapLeftX
            }
        }
        bubbleRestingX = if (bubbleRestingX < Config.mScreenCenterX) {
            Config.mBubbleSnapLeftX
        } else {
            Config.mBubbleSnapRightX
        }

        var bubbleRestingY = mBubbleRestingPoint.y
        if (bubbleRestingY == -1) {
            bubbleRestingY = mSharedPreferences.getInt(BUBBLE_RESTING_Y, -1)
            if (bubbleRestingY == -1) {
                bubbleRestingY = (Config.mScreenHeight * 0.35f).toInt()
            }
        }

        mBubbleRestingPoint.set(bubbleRestingX, bubbleRestingY)
    }

    fun updateBrowsers() {
        var browsers = mBrowsers
        var browserPackageNames = mBrowserPackageNames
        if (browsers == null || browserPackageNames == null) {
            browsers = Vector()
            browserPackageNames = ArrayList()
            mBrowsers = browsers
            mBrowserPackageNames = browserPackageNames
        } else {
            browsers.clear()
            browserPackageNames.clear()
        }
        val packageManager = mContext.packageManager
        val queryIntent = Intent()
        queryIntent.action = Intent.ACTION_VIEW
        queryIntent.data = Uri.parse("http://www.fdasfjsadfdsfas.com")        // Something stupid that no non-browser app will handle
        val resolveInfos = packageManager.queryIntentActivities(queryIntent, PackageManager.GET_RESOLVED_FILTER)
        for (resolveInfo in resolveInfos) {
            val filter: IntentFilter? = resolveInfo.filter
            if (filter != null && filter.hasAction(Intent.ACTION_VIEW) && filter.hasCategory(Intent.CATEGORY_BROWSABLE)) {
                // Ignore this app itself from this list
                if (resolveInfo.activityInfo.packageName == BuildConfig.APPLICATION_ID) {
                    mPeekEntryActivityResolveInfo = resolveInfo
                } else if (Util.isValidBrowserPackageName(resolveInfo.activityInfo.packageName)) {
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.setClassName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name)
                    browsers.add(intent)
                    browserPackageNames.add(resolveInfo.activityInfo.packageName)
                }
            }
        }
    }

    private fun doesPackageExist(pm: PackageManager, targetPackage: String): Boolean {
        try {
            pm.getPackageInfo(targetPackage, PackageManager.GET_META_DATA)
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
        return true
    }

    private fun findResolveInfoForPackageName(resolveInfos: List<ResolveInfo>, packageName: String): ResolveInfo? {
        for (resolveInfo in resolveInfos) {
            if (resolveInfo.activityInfo.packageName == packageName) {
                return resolveInfo
            }
        }

        return null
    }

    private fun setDefaultRightConsumeBubble() {
        val packageManager = mContext.packageManager
        val rightConsumeBubblePackageName = mSharedPreferences.getString(PREFERENCE_RIGHT_CONSUME_BUBBLE_PACKAGE_NAME, null)
        if (rightConsumeBubblePackageName == null
                || (rightConsumeBubblePackageName != BuildConfig.APPLICATION_ID
                        && !doesPackageExist(packageManager, rightConsumeBubblePackageName))) {
            setConsumeBubble(Constant.BubbleAction.ConsumeRight, Constant.ActionType.Share,
                    mContext.resources.getString(R.string.share_picker_label),
                    BuildConfig.APPLICATION_ID, Constant.SHARE_PICKER_NAME)
        }
    }

    private fun setDefaultLeftConsumeBubble() {
        val leftConsumeBubblePackageName = mSharedPreferences.getString(PREFERENCE_LEFT_CONSUME_BUBBLE_PACKAGE_NAME, null)
        if (leftConsumeBubblePackageName == null) {
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "text/plain"
            val packageManager = mContext.packageManager
            val resolveInfos = packageManager.queryIntentActivities(intent, 0)

            if (!setDefaultLeftConsumeBubble(findResolveInfoForPackageName(resolveInfos, Constant.POCKET_PACKAGE_NAME), packageManager)) {
                if (!setDefaultLeftConsumeBubble(findResolveInfoForPackageName(resolveInfos, "com.instapaper.android"), packageManager)) {
                    if (!setDefaultLeftConsumeBubble(findResolveInfoForPackageName(resolveInfos, "com.facebook.katana"), packageManager)) {
                        if (!setDefaultLeftConsumeBubble(findResolveInfoForPackageName(resolveInfos, "com.twitter.android"), packageManager)) {
                            if (!setDefaultLeftConsumeBubble(findResolveInfoForPackageName(resolveInfos, "com.google.android.apps.plus"), packageManager)) {
                                if (!setDefaultLeftConsumeBubble(findResolveInfoForPackageName(resolveInfos, "com.google.android.gm"), packageManager)) {
                                    // Can't imagine *none* of the above apps will not be installed too often, but if so, fall back to the first item in the list...
                                    setDefaultLeftConsumeBubble(resolveInfos[0], packageManager)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setDefaultLeftConsumeBubble(resolveInfo: ResolveInfo?, packageManager: PackageManager): Boolean {
        if (resolveInfo != null) {
            setConsumeBubble(Constant.BubbleAction.ConsumeLeft, Constant.ActionType.Share,
                    resolveInfo.loadLabel(packageManager).toString(),
                    resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name)
            return true
        }
        return false
    }

    fun getBrowsers(): List<Intent>? {
        if (mBrowsers == null) {
            updateBrowsers()
        }
        return mBrowsers
    }

    fun initiateBrowsersUpdate() {
        mBrowsers = null
    }

    fun getBrowserPackageNames(): List<String>? {
        if (mBrowserPackageNames == null) {
            updateBrowsers()
        }

        return mBrowserPackageNames
    }

    fun setDefaultBrowser(label: String?, packageName: String?) {
        val editor = mSharedPreferences.edit()
        editor.putString(PREFERENCE_DEFAULT_BROWSER_LABEL, label)
        editor.putString(PREFERENCE_DEFAULT_BROWSER_PACKAGE_NAME, packageName)
        editor.commit()
    }

    fun getDefaultBrowserLabel(): String? {
        return mSharedPreferences.getString(PREFERENCE_DEFAULT_BROWSER_LABEL, null)
    }

    fun getDefaultBrowserPackageName(): String? {
        return mSharedPreferences.getString(PREFERENCE_DEFAULT_BROWSER_PACKAGE_NAME, null)
    }

    fun getDefaultBrowserComponentName(context: Context): ComponentName? {
        val defaultBrowserPackageName = getDefaultBrowserPackageName()
        if (defaultBrowserPackageName != null) {
            val browserIntent = context.packageManager.getLaunchIntentForPackage(defaultBrowserPackageName)
            if (browserIntent != null) {
                return browserIntent.component
            }
        }
        return null
    }

    fun getDefaultBrowserIcon(context: Context): Drawable? {
        val componentName = getDefaultBrowserComponentName(context)
        if (componentName != null) {
            try {
                return context.packageManager.getActivityIcon(componentName)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e("Exception", e.message, e)
            }
        }

        return null
    }

    fun setConsumeBubble(action: Constant.BubbleAction, type: Constant.ActionType, label: String?, packageName: String?, activityClassName: String?) {
        val editor = mSharedPreferences.edit()
        when (action) {
            Constant.BubbleAction.ConsumeLeft -> {
                editor.putString(PREFERENCE_LEFT_CONSUME_BUBBLE_LABEL, label)
                editor.putString(PREFERENCE_LEFT_CONSUME_BUBBLE_PACKAGE_NAME, packageName)
                editor.putString(PREFERENCE_LEFT_CONSUME_BUBBLE_ACTIVITY_CLASS_NAME, activityClassName)
                editor.putString(PREFERENCE_LEFT_CONSUME_BUBBLE_TYPE, type.name)
            }

            Constant.BubbleAction.ConsumeRight -> {
                editor.putString(PREFERENCE_RIGHT_CONSUME_BUBBLE_LABEL, label)
                editor.putString(PREFERENCE_RIGHT_CONSUME_BUBBLE_PACKAGE_NAME, packageName)
                editor.putString(PREFERENCE_RIGHT_CONSUME_BUBBLE_ACTIVITY_CLASS_NAME, activityClassName)
                editor.putString(PREFERENCE_RIGHT_CONSUME_BUBBLE_TYPE, type.name)
            }

            //case LinkDoubleTap:
            //    editor.putString(PREFERENCE_LINK_DOUBLE_TAP_LABEL, label);
            //    editor.putString(PREFERENCE_LINK_DOUBLE_TAP_PACKAGE_NAME, packageName);
            //    editor.putString(PREFERENCE_LINK_DOUBLE_TAP_ACTIVITY_CLASS_NAME, activityClassName);
            //    editor.putString(PREFERENCE_LINK_DOUBLE_TAP_TYPE, type.name());
            //    break;
            else -> {}
        }
        editor.commit()

        EventBus.post(OnConsumeBubblesChangedEvent())
    }

    fun getConsumeBubbleLabel(action: Constant.BubbleAction): String? {
        return when (action) {
            Constant.BubbleAction.ConsumeLeft ->
                mSharedPreferences.getString(PREFERENCE_LEFT_CONSUME_BUBBLE_LABEL, null)

            Constant.BubbleAction.ConsumeRight ->
                mSharedPreferences.getString(PREFERENCE_RIGHT_CONSUME_BUBBLE_LABEL, null)

            //case LinkDoubleTap:
            //    return mSharedPreferences.getString(PREFERENCE_LINK_DOUBLE_TAP_LABEL, mContext.getString(R.string.not_set));
            else -> null
        }
    }

    fun getConsumeBubblePackageName(action: Constant.BubbleAction): String? {
        return when (action) {
            Constant.BubbleAction.ConsumeLeft ->
                mSharedPreferences.getString(PREFERENCE_LEFT_CONSUME_BUBBLE_PACKAGE_NAME, null)

            Constant.BubbleAction.ConsumeRight ->
                mSharedPreferences.getString(PREFERENCE_RIGHT_CONSUME_BUBBLE_PACKAGE_NAME, null)

            //case LinkDoubleTap:
            //    return mSharedPreferences.getString(PREFERENCE_LINK_DOUBLE_TAP_PACKAGE_NAME, null);
            else -> null
        }
    }

    fun getConsumeBubbleActivityClassName(action: Constant.BubbleAction): String? {
        return when (action) {
            Constant.BubbleAction.ConsumeLeft ->
                mSharedPreferences.getString(PREFERENCE_LEFT_CONSUME_BUBBLE_ACTIVITY_CLASS_NAME, null)

            Constant.BubbleAction.ConsumeRight ->
                mSharedPreferences.getString(PREFERENCE_RIGHT_CONSUME_BUBBLE_ACTIVITY_CLASS_NAME, null)

            //case LinkDoubleTap:
            //    return mSharedPreferences.getString(PREFERENCE_LINK_DOUBLE_TAP_ACTIVITY_CLASS_NAME, null);
            else -> null
        }
    }

    fun getConsumeBubbleActionType(action: Constant.BubbleAction): Constant.ActionType {
        var actionTypeAsString: String? = null
        when (action) {
            Constant.BubbleAction.ConsumeLeft ->
                actionTypeAsString = mSharedPreferences.getString(PREFERENCE_LEFT_CONSUME_BUBBLE_TYPE, null)

            Constant.BubbleAction.ConsumeRight ->
                actionTypeAsString = mSharedPreferences.getString(PREFERENCE_RIGHT_CONSUME_BUBBLE_TYPE, null)

            //case LinkDoubleTap:
            //    actionTypeAsString = mSharedPreferences.getString(PREFERENCE_LINK_DOUBLE_TAP_TYPE, null);
            //    break;
            else -> {}
        }

        if (actionTypeAsString != null) {
            if (actionTypeAsString == Constant.ActionType.Share.name) {
                return Constant.ActionType.Share
            } else if (actionTypeAsString == Constant.ActionType.View.name) {
                return Constant.ActionType.View
            }
        }

        return Constant.ActionType.Unknown
    }

    @JvmOverloads
    fun getConsumeBubbleIcon(action: Constant.BubbleAction, whiteShareIcon: Boolean = true): Drawable {
        val packageManager = mContext.packageManager
        try {
            val packageName = getConsumeBubblePackageName(action)
            val name = getConsumeBubbleActivityClassName(action)
            if (packageName != null && name != null) {
                if (name == Constant.SHARE_PICKER_NAME) {
                    return mContext.resources.getDrawable(if (whiteShareIcon) R.drawable.ic_share_white_24dp else R.drawable.ic_share_grey600_24dp)
                }
                val componentName = ComponentName(packageName, name)
                return packageManager.getActivityIcon(componentName)
            } else if (packageName != null) {
                // Try rendering the icon if we only have a packageName.
                val app = packageManager.getApplicationInfo(packageName, 0)
                return packageManager.getApplicationIcon(app)
            }
        } catch (ex: OutOfMemoryError) {
        } catch (e: PackageManager.NameNotFoundException) {
        }
        return mContext.resources.getDrawable(R.mipmap.ic_launcher)
    }

    private fun loadIgnoreLinksFromPackageNames() {
        var ignoreLinksFromPackageNames = mIgnoreLinksFromPackageNames
        if (ignoreLinksFromPackageNames == null) {
            ignoreLinksFromPackageNames = ArrayList()
            mIgnoreLinksFromPackageNames = ignoreLinksFromPackageNames
        }
        ignoreLinksFromPackageNames.clear()

        // Ignore this on L. Hopefully Google reverse this decision...
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT) {
            val string = mSharedPreferences.getString(PREFERENCE_IGNORE_LINKS_FROM, null)
            if (string != null) {
                val split = string.split(",")
                for (s in split) {
                    ignoreLinksFromPackageNames.add(s)
                }
            }
        }
    }

    fun setIgnoreLinksFromPackageNames(packageNames: ArrayList<String>?) {

        val ignoreLinksFromPackageNames = mIgnoreLinksFromPackageNames ?: ArrayList<String>().also { mIgnoreLinksFromPackageNames = it }
        ignoreLinksFromPackageNames.clear()

        var result = ""
        if (packageNames != null && packageNames.size > 0) {
            for (packageName in packageNames) {
                result += "$packageName,"
                ignoreLinksFromPackageNames.add(packageName)
            }
        }

        val editor = mSharedPreferences.edit()
        editor.putString(PREFERENCE_IGNORE_LINKS_FROM, result)
        editor.commit()
    }

    fun getIgnoreLinksFromPackageNames(): List<String>? {
        return mIgnoreLinksFromPackageNames
    }

    fun ignoreLinkFromPackageName(packageName: String): Boolean {
        for (ignore in mIgnoreLinksFromPackageNames.orEmpty()) {
            if (ignore == packageName) {
                return true
            }
        }

        return false
    }

    fun getAutoContentDisplayLinkLoaded(): Boolean {
        return mSharedPreferences.getBoolean(PREFERENCE_AUTO_CONTENT_DISPLAY_LINK_LOADED, PREFERENCE_AUTO_CONTENT_DISPLAY_LINK_LOADED_DEFAULT)
    }

    val isIncognitoMode: Boolean
        get() = mSharedPreferences.getBoolean(PREFERENCE_INCOGNITO_MODE, false)

    fun isHideBubbles(): Boolean {
        return mSharedPreferences.getBoolean(PREFERENCE_HIDE_BUBBLES, true)
    }

    val isAdBlockEnabled: Boolean
        get() = mSharedPreferences.getBoolean(PREFERENCE_ADBLOCK_MODE, true)

    fun isBlock3PCookiesEnabled(): Boolean {
        return mSharedPreferences.getBoolean(PREFERENCE_BLOCK_3P_COOKIES, true)
    }

    fun setWebViewBatterySaveMode(mode: String) {
        mWebViewBatterySaveMode = if (mode == "aggressive") {
            WebViewBatterySaveMode.Aggressive
        } else if (mode == "off") {
            WebViewBatterySaveMode.Off
        } else {
            WebViewBatterySaveMode.Default
        }
    }

    fun setWebViewBatterySaveMode(mode: WebViewBatterySaveMode) {
        val value: String = when (mode) {
            WebViewBatterySaveMode.Off -> "off"
            WebViewBatterySaveMode.Aggressive -> "aggressive"
            WebViewBatterySaveMode.Default -> "default"
        }

        val editor = mSharedPreferences.edit()
        editor.putString(PREFERENCE_WEBVIEW_BATTERY_SAVING_MODE, value)
        editor.apply()

        setWebViewBatterySaveMode(value)
    }

    fun getWebViewBatterySaveMode(): WebViewBatterySaveMode? {
        return mWebViewBatterySaveMode
    }

    fun getUserAgentString(): String? {
        val defaultUserAgent = mSharedPreferences.getString(PREFERENCE_USER_AGENT, "default")
        if (defaultUserAgent == "chrome_phone") {
            return Constant.USER_AGENT_CHROME_PHONE
        } else if (defaultUserAgent == "chrome_tablet") {
            return Constant.USER_AGENT_CHROME_TABLET
        } else if (defaultUserAgent == "chrome_desktop") {
            return Constant.USER_AGENT_CHROME_DESKTOP
        }

        return null
    }

    fun isEnabled(): Boolean {
        //return mSharedPreferences.getBoolean(PREFERENCE_ENABLED, false);
        return true
    }

    fun getSayThanksClicked(): Boolean {
        return mSharedPreferences.getBoolean(SAY_THANKS_CLICKED, false)
    }

    fun setSayThanksClicked(value: Boolean) {
        val editor = mSharedPreferences.edit()
        editor.putBoolean(SAY_THANKS_CLICKED, value)
        editor.commit()
    }

    fun configureFallbackRedirectHosts(items: MutableSet<String>?) {
        mFallbackRedirectHosts = if (items != null && items.size > 0) {
            // Make a copy to as documentation explicitly states not to trust the result
            // of getStringSet() call. http://stackoverflow.com/a/19949833/328679
            HashSet(items)
        } else {
            mFallbackRedirectHosts.clear()
            mFallbackRedirectHosts
        }
    }

    fun addFallbackRedirectHost(host: String) {
        mFallbackRedirectHosts.add(host)
        saveFallbackRedirectHosts()
    }

    fun removeFallbackRedirectHost(host: String) {
        mFallbackRedirectHosts.remove(host)
        saveFallbackRedirectHosts()
    }

    private fun saveFallbackRedirectHosts() {
        val editor = mSharedPreferences.edit()
        editor.remove(PREFERENCE_FALLBACK_REDIRECT_HOSTS)      // always remove. See http://stackoverflow.com/a/21401062/328679
        if (mFallbackRedirectHosts.size > 0) {
            editor.putStringSet(PREFERENCE_FALLBACK_REDIRECT_HOSTS, mFallbackRedirectHosts)
        }
        editor.apply()
    }

    fun getFallbackRedirectHosts(): Set<String> {
        return mFallbackRedirectHosts
    }

    fun redirectUrlToBrowser(url: URL): Boolean {
        val host = url.host

        val hostAlt = if (host.contains("www.")) host.replace("www.", "") else "www.$host"
        return mFallbackRedirectHosts.contains(host) || mFallbackRedirectHosts.contains(hostAlt)

        /*
         * Temporarily enable DownloadHandlerActivity to see if it might be used to handle this URL. If so, redirect the
         * URL to the default browser rather than allowing an app like ES File Explorer be set as a default app for a host. #338
         */

        /*
        boolean result = false;

        packageManager.setComponentEnabledSetting(mDownloadHandlerComponentName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);

        mDownloadQueryIntent.setAction(Intent.ACTION_VIEW);
        mDownloadQueryIntent.setData(Uri.parse(url));
        List<ResolveInfo> resolveInfos = packageManager.queryIntentActivities(mDownloadQueryIntent, PackageManager.GET_RESOLVED_FILTER);
        if (resolveInfos != null && resolveInfos.size() > 0) {
            String className = DownloadHandlerActivity.class.getName();
            for (ResolveInfo resolveInfo : resolveInfos) {
                if (resolveInfo.activityInfo != null && resolveInfo.activityInfo.name.equals(className)) {
                    result = true;
                    break;
                }
            }
        }

        packageManager.setComponentEnabledSetting(mDownloadHandlerComponentName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);

        return result;
                */
    }

    fun getAutoArticleMode(): Boolean {
        //return Constant.ARTICLE_MODE ? false : mSharedPreferences.getBoolean(PREFERENCE_AUTO_ARTICLE_MODE, false);
        return false
    }

    fun getWebViewTextZoom(): Int {
        return mSharedPreferences.getInt(PREFERENCE_WEBVIEW_TEXT_ZOOM, PREFERENCE_WEBVIEW_TEXT_ZOOM_DEFAULT)
    }

    fun setWebViewTextZoom(zoom: Int) {
        val editor = mSharedPreferences.edit()
        editor.putInt(PREFERENCE_WEBVIEW_TEXT_ZOOM, zoom)
        editor.commit()
    }

    fun getShowUndoCloseTab(): Boolean {
        return mSharedPreferences.getBoolean(PREFERENCE_SHOW_UNDO_CLOSE_TAB, true)
    }

    fun getAppsThatHandleUrl(urlAsString: String, packageManager: PackageManager): List<ResolveInfo>? {

        val browsers = getBrowsers()

        val intent = Intent()
        intent.action = Intent.ACTION_VIEW
        intent.data = Uri.parse(urlAsString)
        val infos = packageManager.queryIntentActivities(intent, PackageManager.GET_RESOLVED_FILTER)

        val results = ArrayList<ResolveInfo>()

        for (info in infos) {
            val filter: IntentFilter? = info.filter
            if (filter != null && filter.hasAction(Intent.ACTION_VIEW)) {

                // Check if this item is a browser, and if so, ignore it
                var packageOk = true
                if (browsers != null) {
                    for (browser in browsers) {
                        if (info.activityInfo.packageName == browser.component?.packageName) {
                            packageOk = false
                            break
                        }
                    }
                }

                if (packageOk) {
                    // Ensure TapPath is always ignored
                    if (info.activityInfo.packageName.contains("com.digitalashes.tappath")) {
                        //Log.d("blerg", "ignore " + info.activityInfo.packageName);
                        packageOk = false
                    } else {
                        // And some special case code for me to ignore alternate builds
                        if (BuildConfig.DEBUG) {
                            if (info.activityInfo.packageName == "com.peek.browser.playstore") {
                                //Log.d("blerg", "ignore " + info.activityInfo.packageName);
                                packageOk = false
                            }
                        } else {
                            if (info.activityInfo.packageName == "com.peek.browser.playstore.dev") {
                                //Log.d("blerg", "ignore " + info.activityInfo.packageName);
                                packageOk = false
                            }
                        }
                    }
                }

                if (packageOk) {
                    results.add(info)
                    Log.d("appHandles", info.loadLabel(packageManager).toString() + " for url:" + urlAsString)
                }
            }
        }

        if (results.size > 0) {
            return results
        }

        return null
    }

    fun getDefaultAppForUrl(url: URL, resolveInfos: List<ResolveInfo>?): ResolveInfo? {
        if (resolveInfos == null || resolveInfos.isEmpty()) {
            return null
        }

        val host = url.host
        if (host.length > 1) {
            val flattenedComponentName = mDefaultAppsMap[host]
            if (flattenedComponentName != null) {
                val componentName = ComponentName.unflattenFromString(flattenedComponentName)
                if (componentName != null) {
                    for (resolveInfo in resolveInfos) {
                        // Handle crash: activityInfo can be null for a disabled/uninstalled resolveInfo entry.
                        if (resolveInfo == null || resolveInfo.activityInfo == null) {
                            CrashTracking.log("Null resolveInfo when getting default for app: $resolveInfo")
                            continue
                        }

                        if (resolveInfo.activityInfo.packageName == componentName.packageName
                                && resolveInfo.activityInfo.name == componentName.className) {
                            return resolveInfo
                        }
                    }

                    if (componentName.packageName == mContext.packageName) {
                        return mPeekEntryActivityResolveInfo
                    }
                }
            }
        }

        return null
    }

    @JvmOverloads
    fun setDefaultApp(urlAsString: String, resolveInfo: ResolveInfo, save: Boolean = true) {
        try {
            val url = URL(urlAsString)
            val host = url.host
            if (host.length > 1) {
                val componentName = ComponentName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name)
                addDefaultApp(host, componentName, save)
            }
        } catch (e: MalformedURLException) {
            e.printStackTrace()
        }
    }

    private fun addDefaultApp(host: String, componentName: ComponentName, save: Boolean) {

        mDefaultAppsMap[host] = componentName.flattenToString()

        if (save) {
            saveDefaultApps()
        }
    }

    fun removeDefaultApp(host: String) {
        if (mDefaultAppsMap.containsKey(host)) {
            mDefaultAppsMap.remove(host)
            saveDefaultApps()
        }
    }

    fun loadCurrentTabs(): Vector<String> {
        val urls = Vector<String>()
        val json = mSharedPreferences.getString(PREFERENCE_CURRENT_TABS, "[]")
        try {
            val jsonArray = JSONArray(json)
            for (i in 0 until jsonArray.length()) {
                urls.add(jsonArray.getString(i))
            }
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        return urls
    }

    fun saveCurrentTabs(bubbles: List<View>?) {
        if (!Constant.SAVE_CURRENT_TABS) {
            return
        }

        val jsonArray = JSONArray()
        if (bubbles != null) {
            for (view in bubbles) {
                val url = (view as TabView).getUrl()
                if (url.toString() != Constant.NEW_TAB_URL) {
                    jsonArray.put(url.toString())
                }
            }
        }

        val currentTabsString = mSharedPreferences.getString(PREFERENCE_CURRENT_TABS, "")
        val newTabsString = jsonArray.toString()

        if (currentTabsString != newTabsString) {
            val editor = mSharedPreferences.edit()
            editor.putString(PREFERENCE_CURRENT_TABS, newTabsString)
            // apply(), not commit() — this runs on the main thread on every tab open/close.
            editor.apply()
        }
    }

    private fun saveDefaultApps() {
        val jsonArray = JSONArray()
        for (key in mDefaultAppsMap.keys) {
            val component = mDefaultAppsMap[key]
            val obj = JSONObject()
            try {
                obj.put(DEFAULT_APPS_MAP_KEY_HOST, key)
                obj.put(DEFAULT_APPS_MAP_KEY_COMPONENT, component)
                jsonArray.put(obj)
            } catch (e: JSONException) {
                e.printStackTrace()
            }
        }

        val editor = mSharedPreferences.edit()
        editor.putString(PREFERENCE_DEFAULT_APPS, jsonArray.toString())
        editor.commit()
    }

    private fun configureDefaultApps(defaultAppsAsString: String?) {
        try {
            if (defaultAppsAsString != null) {
                val defaultApps = JSONArray(defaultAppsAsString)
                mDefaultAppsMap.clear()

                for (i in 0 until defaultApps.length()) {
                    try {
                        val obj = defaultApps.getJSONObject(i)
                        val host = obj.getString(DEFAULT_APPS_MAP_KEY_HOST)
                        val flattenedName = obj.getString(DEFAULT_APPS_MAP_KEY_COMPONENT)
                        mDefaultAppsMap[host] = flattenedName
                    } catch (e: JSONException) {
                        e.printStackTrace()
                    }
                }
            } else {
                mDefaultAppsMap.clear()
            }
        } catch (e: JSONException) {
            mDefaultAppsMap.clear()
        }
    }

    fun getDefaultAppsMap(): TreeMap<String, ComponentName?>? {
        if (mDefaultAppsMap.isNotEmpty()) {
            val result = TreeMap<String, ComponentName?>()
            for (host in mDefaultAppsMap.keys) {
                result[host] = ComponentName.unflattenFromString(mDefaultAppsMap[host]!!)
            }
            return result
        }

        return null
    }

    fun getBubbleRestingPoint(): Point {
        mTempBubbleRestingPoint.x = mBubbleRestingPoint.x
        mTempBubbleRestingPoint.x = if (mTempBubbleRestingPoint.x > Config.mScreenCenterX) {
            Config.mBubbleSnapRightX
        } else {
            Config.mBubbleSnapLeftX
        }

        mTempBubbleRestingPoint.y = mBubbleRestingPoint.y
        val minYPosition = (Config.mScreenHeight * .8f).toInt()
        if (mTempBubbleRestingPoint.y > minYPosition) {
            mTempBubbleRestingPoint.y = minYPosition
        }
        return mTempBubbleRestingPoint
    }

    fun getBubbleStartingX(bubbleRestingPoint: Point): Int {
        val fromX: Float = if (bubbleRestingPoint.x > Config.mScreenCenterX) {
            Config.mBubbleSnapRightX + Config.mBubbleWidth
        } else {
            Config.mBubbleSnapLeftX - Config.mBubbleWidth
        }
        return fromX.toInt()
    }

    fun setBubbleRestingPoint(x: Int, y: Int) {
        mBubbleRestingPoint.set(x, y)
    }

    fun saveBubbleRestingPoint() {
        val editor = mSharedPreferences.edit()
        editor.putInt(BUBBLE_RESTING_X, mBubbleRestingPoint.x)
        editor.putInt(BUBBLE_RESTING_Y, mBubbleRestingPoint.y)
        editor.commit()
    }


    fun setTermsAccepted(accepted: Boolean) {
        val editor = mSharedPreferences.edit()
        editor.putBoolean(TERMS_ACCEPTED, accepted)
        editor.commit()
    }

    fun getTermsAccepted(): Boolean {
        return mSharedPreferences.getBoolean(TERMS_ACCEPTED, false)
    }

    fun debugAutoLoadUrl(): Boolean {
        return mSharedPreferences.getBoolean("auto_load_url", false)
    }

    fun getArticleModeEnabled(): Boolean {
        return mSharedPreferences.getBoolean(KEY_ARTICLE_MODE_PREFERENCE, true)
    }

    fun getArticleModeOnWearEnabled(): Boolean {
        return mSharedPreferences.getBoolean(KEY_ARTICLE_MODE_ON_WEAR_PREFERENCE, false)
    }

    enum class LinkLoadType {
        PageLoad,                   // The page was loaded.
        AppRedirectInstant,         // Redirect via MainController (before a ContentView instance is created for this link).
        AppRedirectBrowser,         // Redirect via ContentView.
        ShareToOtherApp,            // Adding to Pocket or sending to Twitter.
        OpenInOtherApp,             // Opening in Chrome.
    }

    private var mTotalTimeSaved: Long = 0
    private var mTotalLinksLoaded = 0

    class LinkLoadTimeStatsUpdatedEvent

    private val mLinkLoadTimeStatsUpdatedEvent = LinkLoadTimeStatsUpdatedEvent()

    fun trackLinkLoadTime(timeSavedIn: Long, linkLoadType: LinkLoadType, url: String) {
        var timeSaved = timeSavedIn
        when (linkLoadType) {
            LinkLoadType.AppRedirectInstant ->
                // Add some time for the time taken to animate to the new app
                timeSaved += APP_CHANGE_ANIM_TIME.toLong()

            LinkLoadType.AppRedirectBrowser -> {
                // Add some time for the time taken to load the browser initially
                timeSaved += APP_CHANGE_ANIM_TIME.toLong()
                // Add some time for the time taken to animate to the new app
                timeSaved += APP_CHANGE_ANIM_TIME.toLong()
            }

            else -> {}
        }

        mTotalTimeSaved += timeSaved
        mTotalLinksLoaded++

        Log.d(LOAD_TIME_TAG, "trackLinkLoadTime() - timeSaved:" + timeSaved.toFloat() / 1000f + " seconds, " + linkLoadType + ", " + url)

        EventBus.post(mLinkLoadTimeStatsUpdatedEvent)

        Analytics.trackTimeSaved(timeSaved)
    }

    fun getTotalTimeSaved(): Long {
        return mTotalTimeSaved
    }

    fun getTimeSavedPerLink(): Long {
        if (mTotalLinksLoaded > 0) {
            return mTotalTimeSaved / mTotalLinksLoaded
        }
        return -1
    }

    fun saveLinkLoadStats() {
        val editor = mSharedPreferences.edit()
        editor.putLong(TOTAL_TIME_SAVED_KEY, mTotalTimeSaved)
        editor.putInt(TOTAL_LINKS_LOADED_KEY, mTotalLinksLoaded)
        editor.commit()
    }

    private fun loadLinkLoadStats() {
        mTotalTimeSaved = mSharedPreferences.getLong(TOTAL_TIME_SAVED_KEY, 0)
        mTotalLinksLoaded = mSharedPreferences.getInt(TOTAL_LINKS_LOADED_KEY, 0)
    }

    // Did we *just* redirect to this URL? We need to store this to fix #276
    fun didRecentlyRedirectToApp(url: String): Boolean {
        val currentTime = System.currentTimeMillis()
        for (lastAppRedirect in mLastAppRedirects) {
            val timeDelta = currentTime - lastAppRedirect.mTime
            if (timeDelta < RECENT_REDIRECT_TIME_DELTA && lastAppRedirect.mUrl == url) {
                return true
            }
        }

        return false
    }

    fun addRedirectToApp(url: String) {

        var record: LastAppRedirect? = null

        // try and find a current record with this url
        for (i in mLastAppRedirects) {
            if (i.mUrl == url) {
                record = i
                break
            }
        }

        if (record == null && mLastAppRedirects.size == MAX_LAST_APP_REDIRECT_COUNT) {
            // Get the oldest record in the list
            for (i in mLastAppRedirects) {
                if (record == null) {
                    record = i
                } else if (i.mTime < record.mTime) {
                    record = i
                }
            }
        }

        if (record == null) {
            record = LastAppRedirect()
            mLastAppRedirects.add(record)
        }

        record.mUrl = url
        record.mTime = System.currentTimeMillis()
    }

    private fun saveRecentAppRedirects() {
        val jsonArray = JSONArray()
        for (lastAppRedirect in mLastAppRedirects) {
            val obj = JSONObject()
            try {
                obj.put(LAST_APP_REDIRECT_KEY_URL, lastAppRedirect.mUrl)
                obj.put(LAST_APP_REDIRECT_KEY_TIME, lastAppRedirect.mTime)
                jsonArray.put(obj)
            } catch (e: JSONException) {
                e.printStackTrace()
            }
        }

        val editor = mSharedPreferences.edit()
        editor.putString(LAST_APP_REDIRECTS, jsonArray.toString())
        editor.commit()
    }

    private fun loadRecentAppRedirects() {
        mLastAppRedirects.clear()
        val json = mSharedPreferences.getString(LAST_APP_REDIRECTS, "[]")
        try {
            val jsonArray = JSONArray(json)
            for (i in 0 until jsonArray.length()) {
                try {
                    val obj = jsonArray.getJSONObject(i)
                    val lastAppRedirect = LastAppRedirect()
                    lastAppRedirect.mUrl = obj.getString(LAST_APP_REDIRECT_KEY_URL)
                    lastAppRedirect.mTime = obj.getLong(LAST_APP_REDIRECT_KEY_TIME)
                    mLastAppRedirects.add(lastAppRedirect)
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    fun canFlushWebViewCache(): Boolean {
        val lastEmptyTime = mSharedPreferences.getLong(LAST_FLUSH_WEBVIEW_CACHE_TIME, -1)
        if (lastEmptyTime > -1) {
            val delta = System.currentTimeMillis() - lastEmptyTime
            if (delta > Constant.EMPTY_WEBVIEW_CACHE_INTERVAL) {
                return true
            }
        }

        return false
    }

    fun updateLastFlushWebViewCacheTime() {
        val editor = mSharedPreferences.edit()
        editor.putLong(LAST_FLUSH_WEBVIEW_CACHE_TIME, System.currentTimeMillis())
        editor.commit()
    }

    val darkThemeEnabled: Boolean
        get() = mSharedPreferences.getBoolean(PREFERENCE_THEME_DARK, false)

    fun setDarkThemeEnabled(value: Boolean) {
        val editor = mSharedPreferences.edit()
        editor.putBoolean(PREFERENCE_THEME_DARK, value)
        editor.commit()
    }

    fun getColoredProgressIndicator(): Boolean {
        return mSharedPreferences.getBoolean(PREFERENCE_COLORED_PROGRESS_INDICATOR, true)
    }

    fun setColoredProgressIndicator(value: Boolean) {
        val editor = mSharedPreferences.edit()
        editor.putBoolean(PREFERENCE_COLORED_PROGRESS_INDICATOR, value)
        editor.commit()
    }

    fun getThemedDefaultProgressColor(): Int {
        if (darkThemeEnabled) {
            return COLOR_PROGRESS_DARK
        }
        return COLOR_PROGRESS_LIGHT
    }

    fun getThemedContentViewColor(): Int {
        if (darkThemeEnabled) {
            return COLOR_CONTENT_VIEW_DARK
        }
        return COLOR_CONTENT_VIEW_LIGHT
    }

    fun getThemedTextColor(): Int {
        if (darkThemeEnabled) {
            return COLOR_TEXT_DARK
        }
        return COLOR_TEXT_LIGHT
    }

    fun getThemedLinkColor(): Int {
        if (darkThemeEnabled) {
            return COLOR_LINK_DARK
        }
        return COLOR_LINK_LIGHT
    }

    fun getThemeToolbar(): Boolean {
        return mSharedPreferences.getBoolean(PREFERENCE_THEME_TOOLBAR, true)
    }

    companion object {
        const val PREFERENCE_ENABLED = "preference_enabled"
        const val PREFERENCE_IGNORE_LINKS_FROM = "preference_ignore_links_from"

        const val PREFERENCE_AUTO_CONTENT_DISPLAY_LINK_LOADED = "preference_auto_content_display_link_loaded"
        const val PREFERENCE_AUTO_CONTENT_DISPLAY_LINK_LOADED_DEFAULT = false
        const val PREFERENCE_SHOW_UNDO_CLOSE_TAB = "preference_show_undo_close_tab_prompt"

        const val PREFERENCE_LEFT_CONSUME_BUBBLE = "preference_left_consume_bubble"
        const val PREFERENCE_LEFT_CONSUME_BUBBLE_PACKAGE_NAME = "preference_left_consume_bubble_package_name"
        const val PREFERENCE_LEFT_CONSUME_BUBBLE_ACTIVITY_CLASS_NAME = "preference_left_consume_bubble_activity_class_name"
        const val PREFERENCE_LEFT_CONSUME_BUBBLE_LABEL = "preference_left_consume_bubble_label"
        const val PREFERENCE_LEFT_CONSUME_BUBBLE_TYPE = "preference_left_consume_bubble_type"

        const val PREFERENCE_RIGHT_CONSUME_BUBBLE = "preference_right_consume_bubble"
        const val PREFERENCE_RIGHT_CONSUME_BUBBLE_PACKAGE_NAME = "preference_right_consume_bubble_package_name"
        const val PREFERENCE_RIGHT_CONSUME_BUBBLE_ACTIVITY_CLASS_NAME = "preference_right_consume_bubble_activity_class_name"
        const val PREFERENCE_RIGHT_CONSUME_BUBBLE_LABEL = "preference_right_consume_bubble_label"
        const val PREFERENCE_RIGHT_CONSUME_BUBBLE_TYPE = "preference_right_consume_bubble_type"

        //public static final String PREFERENCE_LINK_DOUBLE_TAP = "preference_double_tap";
        //public static final String PREFERENCE_LINK_DOUBLE_TAP_PACKAGE_NAME = "preference_double_tap_package_name";
        //public static final String PREFERENCE_LINK_DOUBLE_TAP_ACTIVITY_CLASS_NAME = "preference_double_tap_activity_class_name";
        //public static final String PREFERENCE_LINK_DOUBLE_TAP_LABEL = "preference_double_tap_bubble_label";
        //public static final String PREFERENCE_LINK_DOUBLE_TAP_TYPE = "preference_double_tap_bubble_type";

        const val PREFERENCE_DEFAULT_BROWSER = "preference_default_browser"
        const val PREFERENCE_DEFAULT_BROWSER_PACKAGE_NAME = "preference_default_browser_package_name"
        const val PREFERENCE_DEFAULT_BROWSER_LABEL = "preference_default_browser_bubble_label"

        const val KEY_ARTICLE_MODE_PREFERENCE = "preference_article_mode"
        const val KEY_ARTICLE_MODE_ON_WEAR_PREFERENCE = "preference_reading_mode_on_wear"

        const val PREFERENCE_THEME_DARK = "preference_theme_dark"
        const val PREFERENCE_COLORED_PROGRESS_INDICATOR = "preference_colored_progress_indicator"

        const val PREFERENCE_CURRENT_TABS = "preference_current_bubbles"
        const val PREFERENCE_DEFAULT_APPS = "preference_default_apps"
        const val PREFERENCE_FALLBACK_REDIRECT_HOSTS = "preference_redirect_hosts"
        const val PREFERENCE_THEME_TOOLBAR = "preference_theme_toolbar"

        const val PREFERENCE_AUTO_ARTICLE_MODE = "preference_auto_article_mode"
        const val PREFERENCE_INCOGNITO_MODE = "preference_incognito"

        const val PREFERENCE_HIDE_BUBBLES = "preference_hide_bubbles"

        const val PREFERENCE_WEBVIEW_BATTERY_SAVING_MODE = "preference_webview_battery_save_v2"
        const val PREFERENCE_ADBLOCK_MODE = "preference_adblock"
        const val PREFERENCE_BLOCK_3P_COOKIES = "preference_block_3p"

        const val PREFERENCE_WEBVIEW_TEXT_ZOOM = "preference_webview_text_zoom2"
        const val PREFERENCE_WEBVIEW_TEXT_ZOOM_MIN = 50
        const val PREFERENCE_WEBVIEW_TEXT_ZOOM_DEFAULT = 100
        const val PREFERENCE_WEBVIEW_TEXT_ZOOM_MAX = 250

        const val PREFERENCE_USER_AGENT = "preference_user_agent"

        private const val SAY_THANKS_CLICKED = "say_thanks_clicked"

        private const val DEFAULT_APPS_MAP_KEY_HOST = "host"
        private const val DEFAULT_APPS_MAP_KEY_COMPONENT = "component"

        private const val BUBBLE_RESTING_X = "bubble_resting_x"
        private const val BUBBLE_RESTING_Y = "bubble_resting_y"

        private const val TERMS_ACCEPTED = "terms_accepted"
        private const val LAST_FLUSH_WEBVIEW_CACHE_TIME = "last_flush_cache_time"
        private const val PREFERENCE_DID_RESET_FALLBACK_BROWSER = "did_reset_fallback_browser"

        @JvmStatic
        fun initModule(context: Context) {
            mInstance = Settings(context)
        }

        @JvmStatic
        fun deinitModule() {
            mInstance = null
        }

        @JvmStatic
        fun get(): Settings {
            return mInstance!!
        }

        private var mInstance: Settings? = null

        @JvmField
        var COLOR_TEXT_DARK = 0
        @JvmField
        var COLOR_TEXT_LIGHT = 0
        @JvmField
        var COLOR_LINK_DARK = 0
        @JvmField
        var COLOR_LINK_LIGHT = 0
        @JvmField
        var COLOR_CONTENT_VIEW_DARK = 0
        @JvmField
        var COLOR_CONTENT_VIEW_LIGHT = 0
        @JvmField
        var COLOR_PROGRESS_DARK = 0
        @JvmField
        var COLOR_PROGRESS_LIGHT = 0
        @JvmField
        var COLOR_WHITE = 0
        @JvmField
        var COLOR_BLACK = 0

        private const val APP_CHANGE_ANIM_TIME = 250

        private const val TOTAL_TIME_SAVED_KEY = "total_time_saved"
        private const val TOTAL_LINKS_LOADED_KEY = "total_links_loaded"

        const val LOAD_TIME_TAG = "LoadTime"

        private const val RECENT_REDIRECT_TIME_DELTA = 2500

        private const val LAST_APP_REDIRECTS = "last_app_redirects"
        private const val LAST_APP_REDIRECT_KEY_TIME = "time"
        private const val LAST_APP_REDIRECT_KEY_URL = "url"
        private const val MAX_LAST_APP_REDIRECT_COUNT = 3
    }
}
