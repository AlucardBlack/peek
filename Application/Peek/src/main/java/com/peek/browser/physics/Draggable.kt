/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.physics

interface Draggable {
    val draggableHelper: DraggableHelper
    fun update(dt: Float)
    fun onOrientationChanged()
    val isDragging: Boolean

    interface OnUpdateListener {
        fun onUpdate(draggable: Draggable, dt: Float)
    }
}
