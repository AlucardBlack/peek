/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.util

import androidx.collection.LruCache
import java.util.concurrent.ConcurrentHashMap

/**
 * An LruCache that also supports a set of items that will never be evicted.
 *
 * Alas, LruCache is final, so we compose rather than inherit.
 */
class NonEvictingLruCache<K : Any, V : Any>(evictableSize: Int) {
    private val permanent = ConcurrentHashMap<K, V>()
    private val evictable: LruCache<K, V> = LruCache(evictableSize)

    fun get(key: K): V? {
        val `val` = permanent[key]
        if (`val` == null) {
            return evictable.get(key)
        }
        return `val`
    }

    fun putWithoutEviction(key: K, value: V) {
        permanent[key] = value
    }

    fun put(key: K, value: V) {
        evictable.put(key, value)
    }

    fun evictAll() {
        evictable.evictAll()
    }
}
