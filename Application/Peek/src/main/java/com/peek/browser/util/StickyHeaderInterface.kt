/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.util

import android.view.View

// Implemented by a RecyclerView.Adapter to describe which rows are section headers, so
// StickyHeaderItemDecoration can render a floating copy of the current section's header.
interface StickyHeaderInterface {
    fun isHeader(itemPosition: Int): Boolean
    fun getHeaderPositionForItem(itemPosition: Int): Int
    fun getHeaderLayout(headerPosition: Int): Int
    fun bindHeaderData(header: View, headerPosition: Int)
}
