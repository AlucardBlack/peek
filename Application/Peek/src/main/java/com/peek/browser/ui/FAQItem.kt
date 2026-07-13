/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.content.Context
import android.text.Html
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.TextView
import com.peek.browser.R

class FAQItem @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    private var mQuestionTextView: TextView? = null
    private var mAnswerTextView: TextView? = null

    fun configure(questionStringId: Int, answerStringId: Int, expanded: Boolean) {
        if (mQuestionTextView == null) {
            mQuestionTextView = findViewById(R.id.question_text_view)
        }
        if (mAnswerTextView == null) {
            mAnswerTextView = findViewById(R.id.answer_text_view)
        }

        mQuestionTextView!!.setText(questionStringId)

        val answerString = context.getString(answerStringId)
        //if (answerString.matches(".*\\<[^>]+>.*")) {
        /*
        if (answerString.contains("href=") || answerString.contains("<img")) {
            mAnswerTextView.setText(Html.fromHtml(answerString));
            mAnswerTextView.setMovementMethod(LinkMovementMethod.getInstance());

            mQuestionTextView.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    mAnswerTextView.setVisibility(mAnswerTextView.getVisibility() == VISIBLE ? GONE : VISIBLE);
                    v.requestLayout();
                    ViewParent parent = v.getParent();
                    do {
                        if (parent instanceof ListView) {
                            BaseAdapter baseAdapter = (BaseAdapter)((ListView) parent).getAdapter();
                            baseAdapter.notifyDataSetChanged();
                            break;
                        }
                        parent = parent.getParent();
                    } while (parent != null);
                }
            });
        } else {
            mAnswerTextView.setText(answerString);
        }*/
        mAnswerTextView!!.text = Html.fromHtml(answerString)
        mAnswerTextView!!.visibility = if (expanded) VISIBLE else GONE
    }
}
