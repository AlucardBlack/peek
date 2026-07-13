/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.content.Context
import android.content.res.Resources
import com.peek.browser.MainApplication
import com.peek.browser.R
import com.peek.browser.util.Util
import java.util.concurrent.CopyOnWriteArrayList

class SearchURLSuggestionsContainer {

    private var mTotalHistoryRecords = 0

    fun loadSuggestions(context: Context, resources: Resources) {
        if (null == mSuggestions) {
            mSuggestions = CopyOnWriteArrayList<SearchURLSuggestions>()
        }
        if (0 != mSuggestions!!.size) {
            return
        }

        // Fill suggestion list with history URL's
        val historyRecords = MainApplication.sDatabaseHelper!!.getRecentNHistoryRecords(HISTORY_ROWS_TO_GET)
        mTotalHistoryRecords = historyRecords.size
        for (historyRecord in historyRecords) {
            val historyUrl = Util.getUrlWithoutHttpHttpsWww(context, historyRecord.getUrl()!!)
            // Looking on duplications
            if (suggestedAlreadyAdded(historyUrl, mSuggestions!!)) {
                continue
            }
            val suggestion = SearchURLSuggestions()
            suggestion.Name = historyUrl
            suggestion.EngineToUse = SearchURLSuggestions.SearchEngine.NONE
            mSuggestions!!.add(suggestion)
        }
        // Set an adapter for search URL control for top 500 websites
        val top500websites = resources.getStringArray(R.array.top500websites)
        for (i in top500websites.indices) {
            // Looking on duplications
            if (suggestedAlreadyAdded(top500websites[i], mSuggestions!!)) {
                continue
            }
            val suggestion = SearchURLSuggestions()
            suggestion.Name = top500websites[i]
            suggestion.EngineToUse = SearchURLSuggestions.SearchEngine.NONE
            mSuggestions!!.add(suggestion)
        }
    }

    fun addUrlToAutoSuggestion(urlToAdd: String, context: Context, resources: Resources) {
        val newUrlToAdd = Util.getUrlWithoutHttpHttpsWww(context, urlToAdd)
        MainApplication.sSearchURLSuggestionsContainer!!.loadSuggestions(context, resources)
        for (suggestion in mSuggestions!!) {
            if (suggestion.Name == newUrlToAdd) {
                return
            }
        }
        if (mTotalHistoryRecords >= HISTORY_ROWS_TO_GET
                && mSuggestions!!.size > HISTORY_ROWS_TO_GET) {
            mSuggestions!!.removeAt(mTotalHistoryRecords - 1)
        } else {
            mTotalHistoryRecords++
        }
        val suggestion = SearchURLSuggestions()
        suggestion.Name = newUrlToAdd
        suggestion.EngineToUse = SearchURLSuggestions.SearchEngine.NONE
        mSuggestions!!.add(0, suggestion)
    }

    // Checks if we have added that suggestion already
    private fun suggestedAlreadyAdded(urlSuggestion: String, suggestionsList: CopyOnWriteArrayList<SearchURLSuggestions>): Boolean {
        for (suggestion in suggestionsList) {
            if (urlSuggestion == suggestion.Name) {
                return true
            }
        }

        return false
    }

    companion object {
        const val HISTORY_ROWS_TO_GET = 50

        @JvmField
        var mSuggestions: CopyOnWriteArrayList<SearchURLSuggestions>? = null
    }
}
