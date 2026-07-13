/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.util

import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

// Draws a floating duplicate of the current section's header on top of the list, and pushes it
// off-screen as the next header scrolls into place. Headers themselves stay as normal rows in
// the adapter; this only adds the "stuck to top" copy while their section is on screen.
class StickyHeaderItemDecoration(private val listener: StickyHeaderInterface) : RecyclerView.ItemDecoration() {

    private var stickyHeaderHeight = 0

    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val topChild = parent.findChildViewUnder(parent.paddingLeft.toFloat(), 0f) ?: return

        val topChildPosition = parent.getChildAdapterPosition(topChild)
        if (topChildPosition == RecyclerView.NO_POSITION) {
            return
        }

        val headerView = getHeaderViewForItem(topChildPosition, parent)
        fixLayoutSize(parent, headerView)
        val contactPoint = headerView.bottom
        val childInContact = getChildInContact(parent, contactPoint) ?: return

        val childAdapterPosition = parent.getChildAdapterPosition(childInContact)
        if (listener.isHeader(childAdapterPosition)) {
            moveHeader(canvas, headerView, childInContact)
            return
        }

        drawHeader(canvas, headerView)
    }

    private fun getHeaderViewForItem(itemPosition: Int, parent: RecyclerView): View {
        val headerPosition = listener.getHeaderPositionForItem(itemPosition)
        val layoutResId = listener.getHeaderLayout(headerPosition)
        val header = LayoutInflater.from(parent.context).inflate(layoutResId, parent, false)
        listener.bindHeaderData(header, headerPosition)
        return header
    }

    private fun drawHeader(canvas: Canvas, header: View) {
        canvas.save()
        canvas.translate(0f, 0f)
        header.draw(canvas)
        canvas.restore()
    }

    private fun moveHeader(canvas: Canvas, currentHeader: View, nextHeader: View) {
        canvas.save()
        canvas.translate(0f, (nextHeader.top - currentHeader.height).toFloat())
        currentHeader.draw(canvas)
        canvas.restore()
    }

    private fun getChildInContact(parent: RecyclerView, contactPoint: Int): View? {
        var childInContact: View? = null
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val childBottomPosition = if (i == 0) {
                val bottom = child.bottom
                val tolerance = stickyHeaderHeight - bottom
                if (tolerance > 0) bottom + tolerance else bottom
            } else {
                child.bottom
            }
            if (childBottomPosition > contactPoint) {
                childInContact = child
                break
            }
        }
        return childInContact
    }

    private fun fixLayoutSize(parent: ViewGroup, view: View) {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(parent.width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(parent.height, View.MeasureSpec.UNSPECIFIED)

        val childWidthSpec = ViewGroup.getChildMeasureSpec(widthSpec, parent.paddingLeft + parent.paddingRight, view.layoutParams?.width ?: ViewGroup.LayoutParams.MATCH_PARENT)
        val childHeightSpec = ViewGroup.getChildMeasureSpec(heightSpec, parent.paddingTop + parent.paddingBottom, view.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT)

        view.measure(childWidthSpec, childHeightSpec)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        stickyHeaderHeight = view.measuredHeight
    }
}
