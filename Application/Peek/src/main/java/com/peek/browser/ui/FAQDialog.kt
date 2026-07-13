/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.peek.browser.BuildConfig
import com.peek.browser.Constant
import com.peek.browser.R
import com.peek.browser.util.StickyHeaderInterface
import com.peek.browser.util.StickyHeaderItemDecoration
import com.peek.browser.util.Util
import java.util.ArrayList
import java.util.Locale

class FAQDialog(context: Activity) {

    private val mActivity: Activity = context
    private val mExpanded: BooleanArray

    init {
        if (sQuestionStringIds == null) {
            sQuestionStringIds = ArrayList()
            sAnswerStringIds = ArrayList()

            val resources = context.resources
            val packageName = context.packageName

            for (entry in sFAQEntry) {
                val questionId = resources.getIdentifier("string/" + entry + "_question", "id", packageName)
                val answerId = resources.getIdentifier("string/" + entry + "_answer", "id", packageName)

                if (answerId == 0 && questionId == 0) {
                    break
                }
                if (answerId > 0 && questionId > 0) {
                    sQuestionStringIds!!.add(questionId)
                    sAnswerStringIds!!.add(answerId)
                    sFAQSize = sAnswerStringIds!!.size
                }
            }
        }

        mExpanded = BooleanArray(sFAQSize)
    }

    // Call to show the FAQ
    fun show() {

        val recyclerView = RecyclerView(mActivity)
        recyclerView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        recyclerView.layoutManager = LinearLayoutManager(mActivity)
        val adapter = FAQAdapter(mActivity)
        recyclerView.adapter = adapter
        recyclerView.addItemDecoration(StickyHeaderItemDecoration(adapter))

        val alertDialog = AlertDialog.Builder(mActivity).create()
        alertDialog.setIcon(Util.getAlertIcon(mActivity))
        alertDialog.setTitle(R.string.faq_title)
        alertDialog.setView(recyclerView)

        Util.showThemedDialog(alertDialog)
    }

    private inner class FAQAdapter(context: Context) : RecyclerView.Adapter<RecyclerView.ViewHolder>(), StickyHeaderInterface {

        private val mInflater: LayoutInflater = LayoutInflater.from(context)

        // Row index -> FAQ entry index, or -1 if the row is a section header.
        private val mRowToFaqIndex = ArrayList<Int>()
        // Row index -> section id (0 = general, 1 = functionality, 2 = issues).
        private val mRowToSectionId = ArrayList<Int>()

        init {
            var sectionId = -1
            for (faqIndex in 0 until sFAQSize) {
                if (faqIndex == 0 || faqIndex == sFunctionalityIndex || faqIndex == sIssuesIndex) {
                    sectionId++
                    mRowToFaqIndex.add(-1)
                    mRowToSectionId.add(sectionId)
                }
                mRowToFaqIndex.add(faqIndex)
                mRowToSectionId.add(sectionId)
            }
        }

        override fun getItemCount(): Int {
            return mRowToFaqIndex.size
        }

        override fun getItemViewType(position: Int): Int {
            return if (mRowToFaqIndex[position] == -1) TYPE_HEADER else TYPE_ITEM
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_HEADER) {
                HeaderViewHolder(mInflater.inflate(R.layout.view_section_header, parent, false))
            } else {
                ItemViewHolder(mInflater.inflate(R.layout.view_faq_item, parent, false) as FAQItem)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is HeaderViewHolder) {
                holder.label.setText(sectionTitleFor(mRowToSectionId[position]))
            } else if (holder is ItemViewHolder) {
                val faqIndex = mRowToFaqIndex[position]
                holder.faqItem.configure(sQuestionStringIds!![faqIndex], sAnswerStringIds!![faqIndex], mExpanded[faqIndex])
                holder.itemView.setOnClickListener {
                    if (faqIndex == sFAQSize - 1) {
                        val emailIntent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", "", null))
                        val appVersion = BuildConfig.VERSION_NAME
                        val subject = "[Peek] Report a bug (v" + appVersion + ", Android " + Constant.getOSFlavor() +
                                ", " + android.os.Build.MODEL + ", " + Locale.getDefault().language + ")"
                        emailIntent.putExtra(Intent.EXTRA_SUBJECT, subject)
                        emailIntent.putExtra(Intent.EXTRA_TEXT, "My bug is ...\n\nHow often does the problem occur?\n\nAre you running a ROM and/or a modified framework/kernel? ")
                        mActivity.startActivity(Intent.createChooser(emailIntent, "Send bug report email..."))
                    } else {
                        mExpanded[faqIndex] = !mExpanded[faqIndex]
                        notifyItemChanged(position)
                    }
                }
            }
        }

        private fun sectionTitleFor(sectionId: Int): Int {
            return when (sectionId) {
                0 -> R.string.faq_section_general
                1 -> R.string.faq_section_functionality
                else -> R.string.faq_section_issues
            }
        }

        override fun isHeader(itemPosition: Int): Boolean {
            return mRowToFaqIndex[itemPosition] == -1
        }

        override fun getHeaderPositionForItem(itemPosition: Int): Int {
            var position = itemPosition
            while (!isHeader(position)) {
                position -= 1
                if (position < 0) return 0
            }
            return position
        }

        override fun getHeaderLayout(headerPosition: Int): Int {
            return R.layout.view_section_header
        }

        override fun bindHeaderData(header: View, headerPosition: Int) {
            val headerLabel = header.findViewById<TextView>(R.id.section_text)
            headerLabel.setText(sectionTitleFor(mRowToSectionId[headerPosition]))
        }

        private inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val label: TextView = itemView.findViewById(R.id.section_text)
        }

        private inner class ItemViewHolder(val faqItem: FAQItem) : RecyclerView.ViewHolder(faqItem)
    }

    companion object {
        private var sQuestionStringIds: ArrayList<Int>? = null
        private var sAnswerStringIds: ArrayList<Int>? = null
        private var sFAQSize = 0

        private val sFAQEntry = arrayOf(
                "faq_app_internal_browser",
                "faq_close_tab",
                "faq_article_mode",

                "faq_back_button_minimize",
                "faq_cant_type_url",
                "faq_future_features",
                "faq_roadmap",

                "faq_close_quickly",
                "faq_change_default_browser",
                "faq_crap_webview",
                "faq_low_spec_performance",
                "faq_copy_text",

                "faq_report_bug"
        )

        private const val sFunctionalityIndex = 3
        private const val sIssuesIndex = 12

        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }
}
