/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser

import android.app.AlertDialog
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.AsyncTask
import android.os.Vibrator
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.color.DynamicColors
import com.peek.browser.Constant.BubbleAction
import com.peek.browser.adblock.ABPFilterParser
import com.peek.browser.adblock.WhiteListCollector
import com.peek.browser.db.DatabaseHelper
import com.peek.browser.db.HistoryRecord
import com.peek.browser.ui.Prompt
import com.peek.browser.ui.SearchURLSuggestionsContainer
import com.peek.browser.ui.SettingsActivity
import com.peek.browser.ui.SettingsMoreActivity
import com.peek.browser.util.ActionItem
import com.peek.browser.util.Analytics
import com.peek.browser.util.CrashTracking
import com.peek.browser.util.EventBus
import com.peek.browser.util.IconCache
import com.peek.browser.util.Util
import org.mozilla.gecko.favicons.Favicons
import java.net.MalformedURLException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class MainApplication : Application() {

    private var mABPParser: ABPFilterParser? = null
    private var mWhiteListCollector: WhiteListCollector? = null

    @JvmField
    var mIconCache: IconCache? = null

    override fun onCreate() {
        super.onCreate()

        // Wallpaper-derived Material You colors on Android 12+; the static Peek palette
        // in colors.xml is the fallback below that.
        DynamicColors.applyToActivitiesIfAvailable(this)

        val channel = NotificationChannel(Constant.NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)

        EventBus.subscribe(this, CheckStateEvent::class.java, ::onCheckStateEvent)
        EventBus.subscribe(this, SettingsActivity.IncognitoModeChangedEvent::class.java, ::onIncognitoModeChanged)
        EventBus.subscribe(this, SettingsMoreActivity.AdBlockTurnOnEvent::class.java, ::onAdBlockOn)

        Settings.initModule(this)
        Prompt.initModule(this)

        sDatabaseHelper = DatabaseHelper(this)
        sSearchURLSuggestionsContainer = SearchURLSuggestionsContainer()

        Analytics.init(this)

        Favicons.attachToContext(this)
        recreateFaviconCache()

        if (Settings.get().isAdBlockEnabled) {
            EventBus.post(SettingsMoreActivity.AdBlockTurnOnEvent())
        }
        InitWhiteListCollectorAsyncTask().execute()

        CrashTracking.log("MainApplication.onCreate()")
        //WebView.setWebContentsDebuggingEnabled(true);
        //checkStrings();
    }

    inner class InitWhiteListCollectorAsyncTask : AsyncTask<Void, Void, Long>() {
        override fun doInBackground(vararg params: Void): Long? {
            initWhiteListCollector()

            return null
        }
    }

    fun initWhiteListCollector() {
        if (mWhiteListCollector == null) {
            mWhiteListCollector = WhiteListCollector(this)
        }
    }

    fun getWhiteListCollector(): WhiteListCollector? {
        return mWhiteListCollector
    }

    fun createABPParser() {
        // Lazy load ABPFilterParser so that if it is disabled we don't even read the binary data
        // to initialize the library.
        if (mABPParser == null) {
            mABPParser = ABPFilterParser(this)
        }
    }

    fun getABPParser(): ABPFilterParser? {
        return mABPParser
    }

    /**
     * There's no guarantee that this function is ever called.
     */
    override fun onTerminate() {
        Prompt.deinitModule()
        Settings.deinitModule()

        sFavicons?.close()
        sFavicons = null

        super.onTerminate()
    }

    class StateChangedEvent {
        @JvmField var mState = 0
        @JvmField var mOldState = 0
        @JvmField var mDisplayToast = false
        @JvmField var mDisplayedToast = false
    }

    class CheckStateEvent

    fun onCheckStateEvent(event: CheckStateEvent) {

    }

    fun onIncognitoModeChanged(event: SettingsActivity.IncognitoModeChangedEvent) {
        if (null == event.mainController) {
            return
        }
        event.mainController.updateIncognitoMode(event.mIncognito)
    }

    inner class DownloadAdBlockDataAsyncTask : AsyncTask<Void, Void, Long>() {
        override fun doInBackground(vararg params: Void): Long? {
            createABPParser()

            return null
        }
    }

    fun onAdBlockOn(event: SettingsMoreActivity.AdBlockTurnOnEvent) {
        DownloadAdBlockDataAsyncTask().execute()
    }

    /*
    private void checkStrings() {
        String blerg = "blerg";
        String[] langs = {"ar", "cs", "cs-rCZ", "da", "de", "es", "fr", "hi", "hu-rHU", "it", "ja-rJP", "nl", "pl-rPL",
                "pt-rBR", "pt-rPT", "ru", "sv", "th", "th-rTH", "tr", "zh-rCN", "zh-rTW",
                "af-rZA", "he", "id", "ko", "no", "sk-rSK", "tr-rCY", "zh-rHK" };
        for (String lang : langs) {
            Util.setLocale(this, lang);
            Log.e("langcheck", "setLocale():" + lang);
            Log.d("langcheck", String.format(getString(R.string.untrusted_certificate), blerg));
            Log.d("langcheck", String.format(getString(R.string.add_domain_error), blerg));
            Log.d("langcheck", String.format(getString(R.string.remove_default_message), blerg, blerg));
            Log.d("langcheck", String.format(getString(R.string.action_open_in_browser), blerg));
            Log.d("langcheck", String.format(getString(R.string.link_redirected), blerg));
            Log.d("langcheck", String.format(getString(R.string.long_press_unsupported_default_browser), blerg));
            Log.d("langcheck", String.format(getString(R.string.unsupported_scheme_default_browser), blerg));
            Log.d("langcheck", String.format(getString(R.string.unsupported_drop_down_default_browser), blerg));
            Log.d("langcheck", String.format(getString(R.string.requesting_location_message), blerg));
            Log.d("langcheck", String.format(getString(R.string.link_loaded_with_app), blerg));
            Log.d("langcheck", String.format(getString(R.string.undo_close_tab_title), blerg));
            Log.d("langcheck", getResources().getQuantityString(R.plurals.restore_tabs_from_previous_session, 1, 1));
            Log.d("langcheck", getResources().getQuantityString(R.plurals.restore_tabs_from_previous_session, 2, 2));
            Log.d("langcheck", String.format(getString(R.string.search_for_with), blerg, blerg));
            Log.d("langcheck", String.format(getString(R.string.duckduckgo_search_engine), blerg));
        }
    }*/

    companion object {
        @JvmField
        var sDatabaseHelper: DatabaseHelper? = null
        @JvmField
        var sSearchURLSuggestionsContainer: SearchURLSuggestionsContainer? = null
        @JvmField
        var sTitleHashMap: ConcurrentHashMap<String, String>? = ConcurrentHashMap(64)
        @JvmField
        var sFavicons: Favicons? = null
        @JvmField
        var sShowingAppPickerDialog = false
        @JvmField
        var sLastLoadedUrl: String? = null
        @JvmField
        var sLastLoadedTime: Long = 0

        @JvmStatic
        fun recreateFaviconCache() {
            sFavicons?.close()

            sFavicons = Favicons()
        }

        @JvmStatic
        fun openLink(context: Context, url: String, openedFromAppName: String?) {
            openLink(context, url, false, openedFromAppName)
        }

        @JvmStatic
        fun openLink(context: Context, url: String, checkLastAppLoad: Boolean, openedFromAppName: String?): Boolean {
            val time = System.currentTimeMillis()

            val appContext = context.applicationContext

            if (!android.provider.Settings.canDrawOverlays(appContext)) {
                return false
            }

            if (checkLastAppLoad) {
                /*
                long timeDiff = time - sLastLoadedTime;
                boolean earlyExit = false;
                if (timeDiff < 3000 && sLastLoadedUrl != null && sLastLoadedUrl.equals(url)) {
                    Toast.makeText(context, "DOUBLE TAP!!", Toast.LENGTH_SHORT).show();
                    earlyExit = true;
                }
                sLastLoadedUrl = url;
                sLastLoadedTime = time;

                if (earlyExit) {
                    return false;
                }*/
            }

            val serviceIntent = Intent(appContext, MainService::class.java)
            serviceIntent.putExtra("cmd", "open")
            serviceIntent.putExtra("url", url)
            serviceIntent.putExtra("start_time", time)
            serviceIntent.putExtra("openedFromAppName", openedFromAppName)
            ContextCompat.startForegroundService(appContext, serviceIntent)

            return true
        }

        @JvmStatic
        fun checkRestoreCurrentTabs(_context: Context) {
            // Don't restore tabs if we've already got tabs open, #389
            val context = _context.applicationContext
            if (MainController.get() == null) {
                val urls = Settings.get().loadCurrentTabs()
                val urlCount = urls.size
                if (urlCount > 0) {
                    val message = context.resources.getQuantityString(R.plurals.restore_tabs_from_previous_session, urlCount, urlCount)
                    Prompt.show(message,
                            context.resources.getString(android.R.string.ok),
                            Prompt.LENGTH_LONG,
                            object : Prompt.OnPromptEventListener {

                                var mOnActionClicked = false

                                override fun onActionClick() {
                                    restoreLinks(context, urls.toArray(arrayOfNulls<String>(urls.size)))
                                    mOnActionClicked = true
                                }

                                override fun onClose() {
                                    if (!mOnActionClicked) {
                                        Settings.get().saveCurrentTabs(null)
                                    }
                                }
                            })
                }
            }
        }

        @JvmStatic
        fun restoreLinks(context: Context, urls: Array<String?>?) {
            val appContext = context.applicationContext
            if (urls == null || urls.isEmpty()) {
                return
            }
            if (!android.provider.Settings.canDrawOverlays(appContext)) {
                return
            }
            CrashTracking.log("MainApplication.restoreLinks(), urls.length:" + urls.size)
            val serviceIntent = Intent(appContext, MainService::class.java)
            serviceIntent.putExtra("cmd", "restore")
            serviceIntent.putExtra("urls", urls)
            serviceIntent.putExtra("start_time", System.currentTimeMillis())
            ContextCompat.startForegroundService(appContext, serviceIntent)
        }

        @JvmStatic
        fun openInBrowser(context: Context, intent: Intent, showToastIfNoBrowser: Boolean): Boolean {
            var activityStarted = false
            val defaultBrowserComponentName = Settings.get().getDefaultBrowserComponentName(context)
            if (defaultBrowserComponentName != null) {
                intent.component = defaultBrowserComponentName
                context.startActivity(intent)
                activityStarted = true
                CrashTracking.log("MainApplication.openInBrowser()")
            }

            if (!activityStarted && showToastIfNoBrowser) {
                Toast.makeText(context, R.string.no_default_browser, Toast.LENGTH_LONG).show()
            }
            return activityStarted
        }

        @JvmStatic
        fun openInBrowser(context: Context, urlAsString: String, showToastIfNoBrowser: Boolean): Boolean {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(urlAsString)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            return openInBrowser(context, intent, showToastIfNoBrowser)
        }

        @JvmStatic
        fun loadResolveInfoIntent(context: Context, resolveInfo: ResolveInfo, url: String, urlLoadStartTime: Long): Boolean {
            if (resolveInfo.activityInfo != null) {
                return loadIntent(context, resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name, url, urlLoadStartTime, true)
            }
            return false
        }

        @JvmStatic
        fun loadIntent(context: Context, packageName: String, className: String, urlAsString: String, urlLoadStartTime: Long, toastOnError: Boolean): Boolean {

            var openIntent = Intent(Intent.ACTION_VIEW)

            try {
                openIntent.setClassName(packageName, className)
                openIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                openIntent.data = Uri.parse(urlAsString)
                context.startActivity(openIntent)
                //Log.d(TAG, "redirect to app: " + resolveInfo.loadLabel(context.getPackageManager()) + ", url:" + url);
                if (urlLoadStartTime > -1) {
                    Settings.get().trackLinkLoadTime(System.currentTimeMillis() - urlLoadStartTime, Settings.LinkLoadType.AppRedirectBrowser, urlAsString)
                }
                CrashTracking.log("MainApplication.loadIntent()")
                return true
            } catch (ex: Exception) {
                // We want to catch SecurityException || ActivityNotFoundException
                openIntent = Intent(Intent.ACTION_VIEW)
                openIntent.setPackage(packageName)
                openIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                openIntent.data = Uri.parse(urlAsString)
                try {
                    context.startActivity(openIntent)
                    if (urlLoadStartTime > -1) {
                        Settings.get().trackLinkLoadTime(System.currentTimeMillis() - urlLoadStartTime, Settings.LinkLoadType.AppRedirectBrowser, urlAsString)
                    }
                    CrashTracking.log("MainApplication.loadIntent() [2]")
                    return true
                } catch (ex2: SecurityException) {
                    if (toastOnError) {
                        Toast.makeText(context, R.string.unable_to_launch_app, Toast.LENGTH_SHORT).show()
                    }
                    return false
                } catch (activityNotFoundException: ActivityNotFoundException) {
                    if (toastOnError) {
                        Toast.makeText(context, R.string.unable_to_launch_app, Toast.LENGTH_SHORT).show()
                    }
                    return false
                }
            }
        }

        @JvmStatic
        fun loadIntent(context: Context, intent: Intent, urlAsString: String, urlLoadStartTime: Long): Boolean {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            context.startActivity(intent)
            //Log.d(TAG, "redirect to app: " + resolveInfo.loadLabel(context.getPackageManager()) + ", url:" + url);
            if (urlLoadStartTime > -1) {
                Settings.get().trackLinkLoadTime(System.currentTimeMillis() - urlLoadStartTime, Settings.LinkLoadType.AppRedirectBrowser, urlAsString)
            }
            return true
        }

        @JvmStatic
        fun handleBubbleAction(context: Context, action: BubbleAction, urlAsString: String, totalTrackedLoadTime: Long): Boolean {
            val actionType = Settings.get().getConsumeBubbleActionType(action)
            var result = false
            if (actionType == Constant.ActionType.Share) {
                val consumePackageName = Settings.get().getConsumeBubblePackageName(action)
                CrashTracking.log("MainApplication.handleBubbleAction() action:$action, consumePackageName:$consumePackageName")
                val consumeName = Settings.get().getConsumeBubbleActivityClassName(action)

                if (consumePackageName == BuildConfig.APPLICATION_ID && consumeName == Constant.SHARE_PICKER_NAME) {
                    val alertDialog = ActionItem.getShareAlert(context, false, object : ActionItem.OnActionItemSelectedListener {
                        override fun onSelected(actionItem: ActionItem) {
                            val intent = Intent(Intent.ACTION_SEND)
                            intent.type = "text/plain"
                            intent.setClassName(actionItem.mPackageName, actionItem.mActivityClassName)
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            intent.putExtra(Intent.EXTRA_TEXT, urlAsString)
                            val title = sTitleHashMap?.get(urlAsString)
                            if (title != null) {
                                intent.putExtra(Intent.EXTRA_SUBJECT, title)
                            }
                            context.startActivity(intent)
                        }
                    })
                    Util.showThemedDialog(alertDialog)
                    return true
                }

                // TODO: Retrieve the class name below from the app in case Twitter ever change it.
                val intent = Util.getSendIntent(consumePackageName!!, consumeName!!, urlAsString)
                try {
                    context.startActivity(intent)
                    if (totalTrackedLoadTime > -1) {
                        Settings.get().trackLinkLoadTime(totalTrackedLoadTime, Settings.LinkLoadType.ShareToOtherApp, urlAsString)
                    }
                    result = true
                } catch (ex: ActivityNotFoundException) {
                    Toast.makeText(context, R.string.consume_activity_not_found, Toast.LENGTH_LONG).show()
                } catch (ex: SecurityException) {
                    Toast.makeText(context, R.string.consume_activity_security_exception, Toast.LENGTH_SHORT).show()
                }
            } else if (actionType == Constant.ActionType.View) {
                val consumePackageName = Settings.get().getConsumeBubblePackageName(action)
                CrashTracking.log("MainApplication.handleBubbleAction() action:$action, consumePackageName:$consumePackageName")
                result = loadIntent(context, consumePackageName!!,
                        Settings.get().getConsumeBubbleActivityClassName(action)!!, urlAsString, -1, true)
            } else if (action == BubbleAction.Close || action == BubbleAction.BackButton) {
                CrashTracking.log("MainApplication.handleBubbleAction() action:$action")
                result = true
            }

            if (result) {
                val hapticFeedbackEnabled = android.provider.Settings.System.getInt(context.contentResolver,
                        android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 0) != 0
                if (hapticFeedbackEnabled && action != BubbleAction.BackButton) {
                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    if (vibrator.hasVibrator()) {
                        vibrator.vibrate(10)
                    }
                }
            }

            return result
        }

        @JvmStatic
        fun saveUrlInHistory(context: Context, resolveInfo: ResolveInfo?, url: String, title: String?) {
            saveUrlInHistory(context, resolveInfo, url, null, title)
        }

        @JvmStatic
        fun saveUrlInHistory(context: Context, resolveInfo: ResolveInfo?, url: String, host: String?, title: String?) {
            var resolvedHost = host

            if (resolvedHost == null) {
                try {
                    val _url = URL(url)
                    resolvedHost = _url.host
                } catch (e: MalformedURLException) {
                    e.printStackTrace()
                }
            }

            val historyRecord = HistoryRecord(title, url, resolvedHost, System.currentTimeMillis())

            sDatabaseHelper?.addHistoryRecord(historyRecord)
            EventBus.post(HistoryRecord.ChangedEvent(historyRecord))
        }

        @JvmStatic
        fun copyLinkToClipboard(context: Context, urlAsString: String?, string: Int) {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
            if (clipboardManager != null) {
                val clipData = ClipData.newPlainText("url", urlAsString)
                clipboardManager.setPrimaryClip(clipData)
                Toast.makeText(context, string, Toast.LENGTH_SHORT).show()
            }
        }

        @JvmStatic
        fun getStoreIntent(context: Context, storeProUrl: String): Intent? {
            val manager = context.packageManager
            val intent = Intent()
            intent.action = Intent.ACTION_VIEW
            intent.data = Uri.parse(storeProUrl)
            val infos = manager.queryIntentActivities(intent, PackageManager.GET_RESOLVED_FILTER)
            for (info in infos) {
                val filter: IntentFilter? = info.filter
                if (filter != null && filter.hasAction(Intent.ACTION_VIEW) && filter.hasCategory(Intent.CATEGORY_BROWSABLE)) {
                    if (info.activityInfo.packageName == BuildConfig.STORE_PACKAGE) {
                        val result = Intent(Intent.ACTION_VIEW)
                        result.setClassName(info.activityInfo.packageName, info.activityInfo.name)
                        result.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        result.data = Uri.parse(storeProUrl)
                        return result
                    }
                }
            }

            return null
        }

        @JvmStatic
        fun openAppStore(context: Context, url: String) {
            val manager = context.packageManager
            val intent = Intent()
            intent.action = Intent.ACTION_VIEW
            intent.data = Uri.parse(url)
            val infos = manager.queryIntentActivities(intent, PackageManager.GET_RESOLVED_FILTER)
            for (info in infos) {
                val filter: IntentFilter? = info.filter
                if (filter != null && filter.hasAction(Intent.ACTION_VIEW) && filter.hasCategory(Intent.CATEGORY_BROWSABLE)) {
                    if (info.activityInfo.packageName == BuildConfig.STORE_PACKAGE) {
                        loadIntent(context, info.activityInfo.packageName, info.activityInfo.name, url, -1, true)
                        return
                    }
                }
            }
        }
    }
}
