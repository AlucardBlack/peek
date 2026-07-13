/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser

import android.content.Context

object Constant {

    enum class BubbleAction {
        None,
        Close,
        BackButton,
        ConsumeRight,
        ConsumeLeft,
        //LinkDoubleTap,
    }

    enum class ActionType {
        Unknown,
        View,
        Share,
    }

    // If true, transfer the WebView to an Activity. Enables text selection and drop down items to work
    const val ACTIVITY_WEBVIEW_RENDERING = false

    // Make GW's changes per d46678694ab79ed7a4aec5e293beff9ae9a62382 optional
    const val DYNAMIC_ANIM_STEP = true

    const val PROFILE_FPS = false

    const val INTENT_EXTRA_IGNORE_LAUNCH_ANIMATION = "com.android.launcher.intent.extra.shortcut.INGORE_LAUNCH_ANIMATION"

    const val EXPANDED_ACTIVITY_DEBUG = false

    const val SAVE_CURRENT_TABS = true

    const val TRIAL_TIME = 1000 * 60 * 60 * 24

    const val ENABLE_FLUSH_CACHE_SERVICE = false

    const val COVER_STATUS_BAR = false //(Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) ? true : false;
    const val BOTTOM_CANVAS_MASK = false
    const val TOP_CANVAS_MASK = true

    const val BUBBLE_ANIM_TIME = 550
    const val BUBBLE_FLOW_ANIM_TIME = 333
    const val BUBBLE_SLIDE_ON_SCREEN_TIME = BUBBLE_FLOW_ANIM_TIME
    const val CANVAS_FADE_ANIM_TIME = BUBBLE_FLOW_ANIM_TIME
    const val TARGET_BUBBLE_APPEAR_TIME = 150

    const val BUBBLE_MODE_ALPHA = 1f

    const val DESIRED_FAVICON_SIZE = 96

    // When opening a link in a new tab, there is no reliable way to get the link to be loaded. Use this guy
    // so we can determine when this is occurring, and not pollute the history. #280
    const val NEW_TAB_URL = "http://ishouldbeusedbutneverseen55675.com"

    const val AUTO_CONTENT_DISPLAY_DELAY = 200

    const val TOUCH_ICON_MAX_SIZE = 256

    const val EMPTY_WEBVIEW_CACHE_INTERVAL = 7 * 24 * 60 * 60 * 1000

    const val DEBUG_SHOW_TARGET_REGIONS = false

    const val SHARE_PICKER_NAME = "com.peek.browser.SharePicker"

    @JvmStatic
    fun getOSFlavor(): String {
        val apiVersion = android.os.Build.VERSION.SDK_INT
        var flavor = ""
        when (apiVersion) {
            15 -> flavor = "4.0"
            16 -> flavor = "4.1"
            17 -> flavor = "4.2"
            18 -> flavor = "4.3"
            19 -> flavor = "4.4"
            20 -> flavor = "4.5"
        }
        return flavor
    }

    @JvmField
    var DEVICE_ID = "<unset>"

    @JvmStatic
    fun getValidDeviceId(): String? {
        if (DEVICE_ID == "<unset>" || DEVICE_ID.length < 4) {
            return null
        }
        return DEVICE_ID
    }

    private var sSecureAndroidId: String? = null

    @JvmStatic
    fun getSecureAndroidId(context: Context): String? {
        if (sSecureAndroidId == null) {
            sSecureAndroidId = android.provider.Settings.Secure.getString(context.applicationContext.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        }
        return sSecureAndroidId
    }

    // String to represent the group all the notifications will be a part of
    const val NOTIFICATION_GROUP_KEY_ARTICLES = "group_key_articles"

    const val NOTIFICATION_CHANNEL_ID = "peek_default"

    const val DATA_USER_ENTRY = "User"
    const val DATA_USER_EMAIL_KEY_PREFIX = "email_"
    const val DATA_USER_TWITTER_KEY_PREFIX = "twitter_"
    const val DATA_USER_YAHOO_KEY_PREFIX = "yahoo_"
    const val DATA_USER_MAX_EMAILS = 9

    const val TWITTER_ACCOUNT_TYPE = "com.twitter.android.auth.login"
    const val YAHOO_ACCOUNT_TYPE = "com.yahoo.mobile.client.share.account"

    const val DATA_TRIAL_ENTRY = "Trial"
    const val DATA_TRIAL_EMAIL = "email"

    const val USER_AGENT_CHROME_PHONE = "Mozilla/5.0 (Linux; Android 4.4.2; GT-I9505 Build/KOT49H) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/34.0.1847.114 Mobile Safari/537.36"
    const val USER_AGENT_CHROME_TABLET = "Mozilla/5.0 (Linux; Android 4.4.2; Nexus 7 Build/KOT49H) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/35.0.1916.48 Safari/537.36"
    const val USER_AGENT_CHROME_DESKTOP = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_9_2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/34.0.1847.116 Safari/537.36"

    const val POCKET_PACKAGE_NAME = "com.ideashower.readitlater.pro"

    const val ABOUT_BLANK_URI = "about:blank"
}
