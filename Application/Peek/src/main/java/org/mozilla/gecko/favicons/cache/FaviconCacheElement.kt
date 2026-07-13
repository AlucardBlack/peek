/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.favicons.cache

import android.graphics.Bitmap

/**
 * Objects stored in the Favicon cache - allow for the bitmap to be tagged to indicate if it has
 * been scaled. Unscaled bitmaps are not included in the scaled-bitmap cache's size calculation.
 */
class FaviconCacheElement(
        payload: Bitmap?,
        // Was this Favicon computed via scaling another primary Favicon, or is this a primary Favicon?
        val mIsPrimary: Boolean,
        val mImageSize: Int,
        // Used for LRU pruning.
        val mBackpointer: FaviconsForURL?
) : Comparable<FaviconCacheElement> {

    constructor(payload: Bitmap?, isPrimary: Boolean, backpointer: FaviconsForURL?) : this(
            payload, isPrimary, payload?.width ?: 0, backpointer
    )

    // The Favicon bitmap.
    var mFaviconPayload: Bitmap? = payload

    // If set, mFaviconPayload is absent. Since the underlying ICO may contain multiple primary
    // payloads, primary payloads are never truly deleted from the cache, but instead have their
    // payload deleted and this flag set on their FaviconCacheElement. That way, the cache always
    // has a record of the existence of a primary payload, even if it is no longer in the cache.
    // This means that when a request comes in that will be best served using a primary that is in
    // the database but no longer cached, we know that it exists and can go get it (Useful when ICO
    // support is added).
    @Volatile
    var mInvalidated: Boolean = false

    fun sizeOf(): Int {
        if (mInvalidated) {
            return 0
        }
        return mFaviconPayload!!.rowBytes * mFaviconPayload!!.height
    }

    /**
     * Establish an ordering on FaviconCacheElements based on size and validity. An element is
     * considered "greater than" another if it is valid and the other is not, or if it contains a
     * larger payload.
     *
     * @param another The FaviconCacheElement to compare to this one.
     * @return -1 if this element is less than the given one, 1 if the other one is larger than this
     *         and 0 if both are of equal value.
     */
    override fun compareTo(another: FaviconCacheElement): Int {
        if (mInvalidated && !another.mInvalidated) {
            return -1
        }

        if (!mInvalidated && another.mInvalidated) {
            return 1
        }

        if (mInvalidated) {
            return 0
        }

        val w1 = mImageSize
        val w2 = another.mImageSize
        if (w1 > w2) {
            return 1
        } else if (w2 > w1) {
            return -1
        }
        return 0
    }

    /**
     * Called when this element is evicted from the cache.
     *
     * If primary, drop the payload and set invalid. If secondary, just unlink from parent node.
     */
    fun onEvictedFromCache() {
        if (mIsPrimary) {
            // So we keep a record of which primaries exist in the database for this URL, we
            // don't actually delete the entry for primaries. Instead, we delete their payload
            // and flag them as invalid. This way, we can later figure out that what a request
            // really want is one of the primaries that have been dropped from the cache, and we
            // can go get it.
            mInvalidated = true
            mFaviconPayload = null
        } else {
            // Secondaries don't matter - just delete them.
            if (mBackpointer == null) {
                return
            }
            mBackpointer.mFavicons.remove(this)
        }
    }
}
