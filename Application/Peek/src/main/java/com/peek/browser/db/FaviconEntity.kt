/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// Plain class rather than data class: the generated equals()/hashCode() on a ByteArray property
// would compare by reference, not content, which would be misleading. Nothing compares instances
// of this class for equality, so it's not needed.
@Entity(tableName = "favicons")
class FaviconEntity(
        @PrimaryKey(autoGenerate = true)
        @ColumnInfo(name = "id")
        val id: Long = 0,
        @ColumnInfo(name = "url")
        val url: String,
        @ColumnInfo(name = "pageUrl")
        val pageUrl: String?,
        @ColumnInfo(name = "imageSize")
        val imageSize: Int,
        @ColumnInfo(name = "data")
        val data: ByteArray?,
        @ColumnInfo(name = "time")
        val time: Long
)

// Row subset backing getFavicon()'s fetch-by-url lookup.
class FaviconFetchRow(
        @ColumnInfo(name = "id")
        val id: Long,
        @ColumnInfo(name = "time")
        val time: Long,
        @ColumnInfo(name = "data")
        val data: ByteArray?
)

// Row subset backing faviconExists()'s metadata-only lookup.
class FaviconExistsRow(
        @ColumnInfo(name = "id")
        val id: Long,
        @ColumnInfo(name = "imageSize")
        val imageSize: Int,
        @ColumnInfo(name = "time")
        val time: Long
)
