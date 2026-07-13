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
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.TextView
import com.peek.browser.BuildConfig
import com.peek.browser.Constant
import com.peek.browser.R
import com.peek.browser.util.Util
import se.emilsjolander.stickylistheaders.StickyListHeadersAdapter
import se.emilsjolander.stickylistheaders.StickyListHeadersListView
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

        val inflater = mActivity.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val layout = inflater.inflate(R.layout.view_faq, null)

        val listView = layout.findViewById<StickyListHeadersListView>(R.id.faq_list)
        listView.setOnItemClickListener(object : AdapterView.OnItemClickListener {
            override fun onItemClick(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                if (position == sFAQSize - 1) {
                    val emailIntent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", "", null))
                    val appVersion = BuildConfig.VERSION_NAME
                    val subject = "[Peek] Report a bug (v" + appVersion + ", Android " + Constant.getOSFlavor() +
                            ", " + android.os.Build.MODEL + ", " + Locale.getDefault().language + ")"
                    emailIntent.putExtra(Intent.EXTRA_SUBJECT, subject)
                    emailIntent.putExtra(Intent.EXTRA_TEXT, "My bug is ...\n\nHow often does the problem occur?\n\nAre you running a ROM and/or a modified framework/kernel? ")
                    mActivity.startActivity(Intent.createChooser(emailIntent, "Send bug report email..."))
                } else {
                    val adapter = view.tag as FAQAdapter
                    adapter.toggle(position)
                }
            }
        })
        listView.setAdapter(FAQAdapter(mActivity))

        val alertDialog = AlertDialog.Builder(mActivity).create()
        alertDialog.setIcon(Util.getAlertIcon(mActivity))
        alertDialog.setTitle(R.string.faq_title)
        alertDialog.setView(layout)

        /*
        alertDialog.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.ok), new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putInt(KEY_HORIZONTAL_PADDING_PREFERENCE, horizontalSeekBar.getProgress());
                editor.putInt(KEY_VERTICAL_PADDING_PREFERENCE, verticalSeekBar.getProgress());
                editor.commit();
            }

        });

        alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL, getString(R.string.restore_default_action), new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putInt(KEY_HORIZONTAL_PADDING_PREFERENCE, LauncherPreferences.get().getWorkspaceHorizontalPaddingDefaultAsInt());
                editor.putInt(KEY_VERTICAL_PADDING_PREFERENCE, LauncherPreferences.get().getWorkspaceVerticalPaddingDefaultAsInt());
                editor.commit();
            }
        });
        */

        Util.showThemedDialog(alertDialog)
    }

    private inner class FAQAdapter(context: Context) : BaseAdapter(), StickyListHeadersAdapter {

        val mInflater: LayoutInflater = LayoutInflater.from(context)

        override fun getCount(): Int {
            return sFAQSize
        }

        override fun getItem(position: Int): Any {
            return position
        }

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val faqItem: FAQItem
            if (convertView == null) {
                faqItem = mInflater.inflate(R.layout.view_faq_item, null) as FAQItem
            } else {
                faqItem = convertView as FAQItem
            }

            faqItem.configure(this, sQuestionStringIds!![position], sAnswerStringIds!![position], mExpanded[position])
            return faqItem
        }

        fun toggle(position: Int) {
            mExpanded[position] = !mExpanded[position]
            notifyDataSetChanged()
        }

        override fun getHeaderView(position: Int, convertViewIn: View?, parent: ViewGroup): View {
            val convertView = mInflater.inflate(R.layout.view_section_header, parent, false)
            val headerLabel = convertView.findViewById<TextView>(R.id.section_text)

            var stringId = R.string.faq_section_issues
            if (position < sFunctionalityIndex) {
                stringId = R.string.faq_section_general
            } else if (position < sIssuesIndex) {
                stringId = R.string.faq_section_functionality
            }

            headerLabel.setText(stringId)
            return convertView
        }

        override fun getHeaderId(position: Int): Long {
            if (position < sFunctionalityIndex) {
                return 0
            } else if (position < sIssuesIndex) {
                return 1
            }

            return 2
        }
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
    }
}
