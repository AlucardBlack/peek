/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.ui

import android.app.Activity
import android.graphics.Point
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import com.peek.browser.R

class BubbleFlowActivity : Activity() {

    lateinit var mBubbleFlowView: BubbleFlowView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_bubble_flow)

        val size = Point()
        val w = windowManager
        w.defaultDisplay.getSize(size)

        val inflater = LayoutInflater.from(this)
        mBubbleFlowView = findViewById(R.id.bubble_flow)
        mBubbleFlowView.configure(size.x,
                resources.getDimensionPixelSize(R.dimen.bubble_pager_item_width),
                resources.getDimensionPixelSize(R.dimen.bubble_pager_item_height))
        for (i in 0 until 19) {
            val bubble = inflater.inflate(R.layout.view_tab, null) as TabView
            mBubbleFlowView.add(bubble, false)
        }

        findViewById<View>(R.id.add_bubble_button).setOnClickListener {
            val bubble = inflater.inflate(R.layout.view_tab, null) as TabView
            mBubbleFlowView.add(bubble, false)
        }

        findViewById<View>(R.id.remove_bubble_button).setOnClickListener {
            val centerIndex = mBubbleFlowView.getCenterIndex()
            if (centerIndex > -1) {
                mBubbleFlowView.remove(centerIndex, false, true)
            }
        }

        val animateButton = findViewById<Button>(R.id.animate_bubble_button)
        animateButton.setOnClickListener {
            if (mBubbleFlowView.isExpanded()) {
                mBubbleFlowView.collapse()
                animateButton.text = "Expand"
            } else {
                mBubbleFlowView.expand()
                animateButton.text = "Collapse"
            }
        }
    }

    override fun onResume() {
        super.onResume()

        mBubbleFlowView.postDelayed({
            mBubbleFlowView.setCenterIndex(6)
        }, 100)
    }
}
