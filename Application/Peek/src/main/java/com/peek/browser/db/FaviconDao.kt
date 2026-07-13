/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface FaviconDao {

    @Insert
    fun insert(favicon: FaviconEntity): Long

    @Query("DELETE FROM favicons")
    fun deleteAll()

    @Query("DELETE FROM favicons WHERE id = :id")
    fun deleteById(id: Long)

    // No ORDER BY, matching the original raw query - just the first row SQLite returns for this
    // url (there's no uniqueness constraint, so duplicate rows per url are possible over time).
    @Query("SELECT id, time, data FROM favicons WHERE url = :url LIMIT 1")
    fun getFetchRowByUrl(url: String): FaviconFetchRow?

    @Query("SELECT id, imageSize, time FROM favicons WHERE url = :url LIMIT 1")
    fun getExistsRowByUrl(url: String): FaviconExistsRow?
}
