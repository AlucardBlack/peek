/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.util

import android.app.Application

object Analytics {

    const val GA_PROPERTY_ID = "UA-49396039-1"

    @JvmStatic
    fun init(application: Application) {
    }

    private const val CATEGORY = "Usage"

    const val OPENED_URL_FROM_NEW_TAB = "Peek-NewTab"
    const val OPENED_URL_FROM_MAIN_NEW_TAB = "Peek-MainNewTab"
    const val OPENED_URL_FROM_NEW_WINDOW = "Peek-NewWindow"
    const val OPENED_URL_FROM_RESTORE = "Peek-Restore"
    const val OPENED_URL_FROM_HISTORY = "Peek-History"

    @JvmStatic
    fun trackOpenUrl(openedFromAppName: String?) {
    }

    @JvmStatic
    fun trackTimeSaved(time: Long) {
    }

    const val UPGRADE_PROMPT_SINGLE_APP = "single_app"
    const val UPGRADE_PROMPT_SINGLE_APP_SET = "single_app_set"
    const val UPGRADE_PROMPT_SINGLE_TAB_OPEN_URL = "single_tab_open_url"
    const val UPGRADE_PROMPT_SINGLE_TAB_REDIRECT = "single_tab_redirect"

    @JvmStatic
    fun trackUpgradePromptDisplayed(promptType: String) {
    }

    @JvmStatic
    fun trackUpgradePromptClicked(promptType: String) {
    }

    @JvmStatic
    fun trackScreenView(screenName: String) {
    }
}
