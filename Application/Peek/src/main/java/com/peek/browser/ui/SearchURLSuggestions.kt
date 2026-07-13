/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

class SearchURLSuggestions {

    enum class SearchEngine {
        DUCKDUCKGO,
        GOOGLE,
        YAHOO,
        AMAZON,
        NONE
    }

    @JvmField
    var Name: String? = null
    @JvmField
    var EngineToUse: SearchEngine = SearchEngine.NONE
}
