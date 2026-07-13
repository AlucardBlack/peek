/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.util

// Tags describing where an opened URL came from, threaded through MainController.openUrl(...)
// to drive per-source behavior (e.g. new-tab vs. history vs. restore handling).
object SourceTag {
    const val OPENED_URL_FROM_NEW_TAB = "Peek-NewTab"
    const val OPENED_URL_FROM_MAIN_NEW_TAB = "Peek-MainNewTab"
    const val OPENED_URL_FROM_NEW_WINDOW = "Peek-NewWindow"
    const val OPENED_URL_FROM_RESTORE = "Peek-Restore"
    const val OPENED_URL_FROM_HISTORY = "Peek-History"
}
