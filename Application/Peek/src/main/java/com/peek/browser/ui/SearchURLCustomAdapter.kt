/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.content.Context
import android.content.res.Resources
import android.text.Html
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.TextView
import com.peek.browser.MainApplication
import com.peek.browser.R
import com.peek.browser.util.Util
import java.util.concurrent.CopyOnWriteArrayList

class SearchURLCustomAdapter(
        context: Context, textViewResourceId: Int, resources: Resources, controlSize: Int
) : ArrayAdapter<SearchURLSuggestions>(context, textViewResourceId) {

    @JvmField
    var mRealUrlBarConstraint: String = ""

    private var mControlSize: Int = 0
    private val mResources: Resources = resources
    private val mMaxCharsCountToUse = HashMap<SearchURLSuggestions.SearchEngine, Int>()

    init {
        setDropDownWidth(controlSize)
        mMaxCharsCountToUse[SearchURLSuggestions.SearchEngine.GOOGLE] = 0
        mMaxCharsCountToUse[SearchURLSuggestions.SearchEngine.DUCKDUCKGO] = 0
        mMaxCharsCountToUse[SearchURLSuggestions.SearchEngine.YAHOO] = 0
        mMaxCharsCountToUse[SearchURLSuggestions.SearchEngine.AMAZON] = 0
    }

    private val mFilter: Filter = object : Filter() {
        override fun convertResultToString(resultValue: Any): CharSequence {
            return (resultValue as SearchURLSuggestions).Name!!
        }

        override fun performFiltering(constraintInCome: CharSequence?): FilterResults {
            val results = FilterResults()

            val constraint = Util.getUrlWithoutHttpHttpsWww(context, mRealUrlBarConstraint)
            MainApplication.sSearchURLSuggestionsContainer!!.loadSuggestions(context, mResources)
            if (constraint.isNotEmpty()) {
                val suggestions = CopyOnWriteArrayList<SearchURLSuggestions>()
                var showSearchEngines = true
                for (suggestion in SearchURLSuggestionsContainer.mSuggestions!!) {
                    // Note: change the "startsWith" to "contains" if you only want starting matches
                    if (suggestion.Name!!.lowercase().startsWith(constraint.lowercase())) {
                        suggestions.add(suggestion)
                        if (suggestion.Name!!.length == constraint.length) {
                            showSearchEngines = false
                        }
                    }
                }

                // For search engines
                if (showSearchEngines && !Util.isValidURL(context, mRealUrlBarConstraint)) {
                    val searchSuggestion1 = SearchURLSuggestions()
                    val searchSuggestion2 = SearchURLSuggestions()
                    val searchSuggestion3 = SearchURLSuggestions()
                    val searchSuggestion4 = SearchURLSuggestions()

                    searchSuggestion1.Name = constraint
                    searchSuggestion1.EngineToUse = SearchURLSuggestions.SearchEngine.GOOGLE

                    searchSuggestion2.Name = constraint
                    searchSuggestion2.EngineToUse = SearchURLSuggestions.SearchEngine.DUCKDUCKGO

                    searchSuggestion3.Name = constraint
                    searchSuggestion3.EngineToUse = SearchURLSuggestions.SearchEngine.YAHOO

                    searchSuggestion4.Name = constraint
                    searchSuggestion4.EngineToUse = SearchURLSuggestions.SearchEngine.AMAZON

                    suggestions.add(searchSuggestion1)
                    suggestions.add(searchSuggestion2)
                    suggestions.add(searchSuggestion3)
                    suggestions.add(searchSuggestion4)
                }
                //
                results.values = suggestions
                results.count = suggestions.size
            } else {
                results.values = SearchURLSuggestionsContainer.mSuggestions
                results.count = SearchURLSuggestionsContainer.mSuggestions!!.size
            }

            return results
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            clear()
            if (results != null && results.count > 0) {
                // We have filtered results
                addAll(results.values as CopyOnWriteArrayList<SearchURLSuggestions>)
            }
            notifyDataSetChanged()
        }
    }

    fun setDropDownWidth(controlSize: Int) {
        mControlSize = controlSize - 130
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent)

        val name = view as TextView
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, DROP_DOWN_TEXT_SIZE)
        val suggestion = getItem(position)!!
        if (SearchURLSuggestions.SearchEngine.NONE == suggestion.EngineToUse) {
            name.text = Html.fromHtml(context.getString(R.string.top_500_prepend) + " <font color=" +
                    context.getString(R.string.url_bar_constraint_text_color) + ">" + suggestion.Name + "</font>")
        } else {

            var valueToSet = ""
            if (SearchURLSuggestions.SearchEngine.GOOGLE == suggestion.EngineToUse) {
                valueToSet = String.format(context.getString(R.string.search_for_with),
                        context.getString(R.string.google),
                        "<font color=" + context.getString(R.string.url_bar_constraint_text_color) + ">" + suggestion.Name)
            } else if (SearchURLSuggestions.SearchEngine.DUCKDUCKGO == suggestion.EngineToUse) {
                valueToSet = String.format(context.getString(R.string.search_for_with),
                        context.getString(R.string.duck_duck_go),
                        "<font color=" + context.getString(R.string.url_bar_constraint_text_color) + ">" + suggestion.Name)
            } else if (SearchURLSuggestions.SearchEngine.YAHOO == suggestion.EngineToUse) {
                valueToSet = String.format(context.getString(R.string.search_for_with),
                        context.getString(R.string.yahoo),
                        "<font color=" + context.getString(R.string.url_bar_constraint_text_color) + ">" + suggestion.Name)
            } else if (SearchURLSuggestions.SearchEngine.AMAZON == suggestion.EngineToUse) {
                valueToSet = String.format(context.getString(R.string.search_for_with),
                        context.getString(R.string.amazon),
                        "<font color=" + context.getString(R.string.url_bar_constraint_text_color) + ">" + suggestion.Name)
            }
            val textPaint = name.paint
            var toAdd = "</font>"
            val textWidth = textPaint.measureText(Html.fromHtml(valueToSet + toAdd).toString())
            if (textWidth > mControlSize) {
                var charactersToLeft = mMaxCharsCountToUse[suggestion.EngineToUse]!!
                if (charactersToLeft >= valueToSet.length) {
                    charactersToLeft = valueToSet.length - 1
                }
                if (0 == charactersToLeft) {
                    val percentToShow = mControlSize * 100 / textWidth
                    charactersToLeft = (valueToSet.length * percentToShow / 100).toInt()
                }

                valueToSet = valueToSet.substring(0, charactersToLeft)
                toAdd = "..." + toAdd
            } else {
                toAdd += "\""
                mMaxCharsCountToUse[suggestion.EngineToUse] = valueToSet.length
            }

            valueToSet += toAdd
            name.text = Html.fromHtml(valueToSet)
        }

        return view
    }

    override fun getFilter(): Filter {
        return mFilter
    }

    fun addUrlToAutoSuggestion(urlToAdd: String) {
        MainApplication.sSearchURLSuggestionsContainer!!.addUrlToAutoSuggestion(urlToAdd, context, mResources)
    }

    companion object {
        private const val DROP_DOWN_TEXT_SIZE = 16f
    }
}
