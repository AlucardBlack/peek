/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.webkit.WebIconDatabase
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.peek.browser.ui.NotificationCloseAllActivity
import com.peek.browser.ui.NotificationHideActivity
import com.peek.browser.ui.NotificationUnhideActivity
import com.peek.browser.util.SourceTag
import com.peek.browser.util.CrashTracking
import com.peek.browser.util.EventBus

class MainService : Service() {

    private var mRestoreComplete = false
    private var mFullyInitialized = false

    class ShowDefaultNotificationEvent

    class ShowUnhideNotificationEvent

    class OnDestroyMainServiceEvent

    class ReloadMainServiceEvent(@JvmField val mContext: Context)

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cmd = intent?.getStringExtra("cmd")
        CrashTracking.log("MainService.onStartCommand(), cmd:$cmd")

        val mainController = MainController.get()
        if (mainController == null || intent == null || cmd == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val urlLoadStartTime = intent.getLongExtra("start_time", System.currentTimeMillis())
        if (cmd.compareTo("open") == 0) {
            val url = intent.getStringExtra("url")
            if (url != null) {
                val openedFromAppName = intent.getStringExtra("openedFromAppName")
                mainController.openUrl(url, urlLoadStartTime, true, openedFromAppName)
            }
        } else if (cmd.compareTo("restore") == 0) {
            if (!mRestoreComplete) {
                val urls = intent.getStringArrayExtra("urls")
                if (urls != null) {
                    val startOpenTabCount = mainController.activeTabCount

                    for (i in urls.indices) {
                        val urlAsString = urls[i]
                        if (urlAsString != null) {
                            var setAsCurrentTab = false
                            if (startOpenTabCount == 0) {
                                setAsCurrentTab = i == urls.size - 1
                            }

                            mainController.openUrl(urlAsString, urlLoadStartTime, setAsCurrentTab, SourceTag.OPENED_URL_FROM_RESTORE)
                        }
                    }
                }
                mRestoreComplete = true
            }
        }

        return START_STICKY
    }

    override fun onCreate() {

        mRestoreComplete = false

        setTheme(if (Settings.get().darkThemeEnabled) R.style.MainServiceThemeDark else R.style.MainServiceThemeLight)

        super.onCreate()
        CrashTracking.log("MainService.onCreate()")

        if (!android.provider.Settings.canDrawOverlays(this)) {
            CrashTracking.log("MainService.onCreate(): overlay permission not granted, stopping self")
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        showDefaultNotification()

        Config.init(this)
        Settings.get().onOrientationChange()

        try {
            WebIconDatabase.getInstance().open(getDir("icons", MODE_PRIVATE).path)
        } catch (exc: RuntimeException) {
            CrashTracking.logHandledException(exc)
        }

        MainController.create(this, object : MainController.EventHandler {
            override fun onDestroy() {
                Settings.get().saveBubbleRestingPoint()
                stopSelf()
                CrashTracking.log("MainService.onCreate(): onDestroy()")
            }
        })

        //Intent i = new Intent();
        //i.setData(Uri.parse("https://t.co/uxMl3bWtMP"));
        //i.setData(Uri.parse("http://t.co/oOyu7GBZMU"));
        //i.setData(Uri.parse("http://goo.gl/abc57"));
        //i.setData(Uri.parse("https://bitly.com/QtQET"));
        //i.setData(Uri.parse("http://www.duckduckgo.com"));
        //openUrl("https://www.duckduckgo.com");
        //openUrl("http://www.duckduckgo.com", true);
        //openUrl("https://t.co/uxMl3bWtMP", true);

        var filter = IntentFilter()
        filter.addAction(BCAST_CONFIGCHANGED)
        ContextCompat.registerReceiver(this, mBroadcastReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        ContextCompat.registerReceiver(this, mDialogReceiver, IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
                ContextCompat.RECEIVER_NOT_EXPORTED)

        filter = IntentFilter(Intent.ACTION_PACKAGE_ADDED)
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED)
        filter.addDataScheme("package")

        filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        filter.addAction(Intent.ACTION_USER_PRESENT)
        ContextCompat.registerReceiver(this, mScreenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        EventBus.subscribe(this, ShowDefaultNotificationEvent::class.java, ::onShowDefaultNotificationEvent)
        EventBus.subscribe(this, ShowUnhideNotificationEvent::class.java, ::onShowUnhideNotificationEvent)
        EventBus.subscribe(this, ReloadMainServiceEvent::class.java, ::onReloadMainServiceEvent)
        mFullyInitialized = true
    }

    override fun onDestroy() {
        if (mFullyInitialized) {
            EventBus.post(OnDestroyMainServiceEvent())
            EventBus.unsubscribeAll(this)
            unregisterReceiver(mScreenReceiver)
            unregisterReceiver(mDialogReceiver)
            unregisterReceiver(mBroadcastReceiver)
            MainController.destroy()
        }
        CrashTracking.log("MainService.onDestroy()")
        super.onDestroy()
    }

    private fun cancelCurrentNotification() {
        stopForeground(true)
        //Log.d("blerg", "cancelCurrentNotification()");
    }

    private fun showDefaultNotification() {
        val closeAllIntent = Intent(this, NotificationCloseAllActivity::class.java)
        closeAllIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_NEW_TASK
        val closeAllPendingIntent = PendingIntent.getActivity(this, System.currentTimeMillis().toInt(), closeAllIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val hideIntent = Intent(this, NotificationHideActivity::class.java)
        hideIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_NEW_TASK
        val hidePendingIntent = PendingIntent.getActivity(this, System.currentTimeMillis().toInt(), hideIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notificationBuilder = NotificationCompat.Builder(this, Constant.NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat)
                .setPriority(Notification.PRIORITY_MIN)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_default_summary))
                //.addAction(R.drawable.ic_action_eye_closed_dark, getString(R.string.notification_action_hide), hidePendingIntent)
                //.addAction(R.drawable.ic_action_cancel_dark, getString(R.string.notification_action_close_all), closeAllPendingIntent)
                .addAction(if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT) R.drawable.ic_action_cancel_white else R.drawable.ic_action_cancel_dark, getString(R.string.notification_action_close_all), closeAllPendingIntent)
                .setGroup(Constant.NOTIFICATION_GROUP_KEY_ARTICLES)
                .setGroupSummary(true)
                .setLocalOnly(true)
                .setContentIntent(hidePendingIntent)

        // Nuke all previous notifications
        NotificationManagerCompat.from(this).cancel(NotificationUnhideActivity.NOTIFICATION_ID)
        NotificationManagerCompat.from(this).cancel(NotificationHideActivity.NOTIFICATION_ID)

        startForeground(NotificationHideActivity.NOTIFICATION_ID, notificationBuilder.build())
    }

    private fun showUnhideHiddenNotification() {
        val unhideIntent = Intent(this, NotificationUnhideActivity::class.java)
        unhideIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_NEW_TASK
        val unhidePendingIntent = PendingIntent.getActivity(this, System.currentTimeMillis().toInt(), unhideIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val closeAllIntent = Intent(this, NotificationCloseAllActivity::class.java)
        closeAllIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_NEW_TASK
        val closeAllPendingIntent = PendingIntent.getActivity(this, System.currentTimeMillis().toInt(), closeAllIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notificationBuilder = NotificationCompat.Builder(this, Constant.NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat)
                .setPriority(Notification.PRIORITY_MIN)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_unhide_summary))
                .setLocalOnly(true)
                //.addAction(R.drawable.ic_action_eye_open_dark, getString(R.string.notification_action_unhide), unhidePendingIntent)
                //.addAction(R.drawable.ic_action_cancel_dark, getString(R.string.notification_action_close_all), closeAllPendingIntent)
                .addAction(if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT) R.drawable.ic_action_cancel_white else R.drawable.ic_action_cancel_dark, getString(R.string.notification_action_close_all), closeAllPendingIntent)
                .setContentIntent(unhidePendingIntent)

        // Nuke all previous notifications
        NotificationManagerCompat.from(this).cancel(NotificationUnhideActivity.NOTIFICATION_ID)
        NotificationManagerCompat.from(this).cancel(NotificationHideActivity.NOTIFICATION_ID)
        startForeground(NotificationUnhideActivity.NOTIFICATION_ID, notificationBuilder.build())
    }

    fun onShowDefaultNotificationEvent(event: ShowDefaultNotificationEvent) {
        cancelCurrentNotification()
        showDefaultNotification()
    }

    fun onShowUnhideNotificationEvent(event: ShowUnhideNotificationEvent) {
        cancelCurrentNotification()
        showUnhideHiddenNotification()
    }

    fun onReloadMainServiceEvent(event: ReloadMainServiceEvent) {
        stopSelf()

        val urls = Settings.get().loadCurrentTabs()
        MainApplication.restoreLinks(event.mContext, urls.toArray(arrayOfNulls<String>(urls.size)))
    }


    private val mDialogReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, myIntent: Intent) {
            if (myIntent.action == Intent.ACTION_CLOSE_SYSTEM_DIALOGS) {
                MainController.get()!!.onCloseSystemDialogs()
            }
        }
    }

    private val mBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, myIntent: Intent) {
            if (myIntent.action == BCAST_CONFIGCHANGED) {
                MainController.get()!!.onOrientationChanged()
            }
        }
    }

    private val mScreenReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            MainController.get()!!.updateScreenState(intent.action!!)
        }
    }

    companion object {
        private const val BCAST_CONFIGCHANGED = "android.intent.action.CONFIGURATION_CHANGED"
    }
}
