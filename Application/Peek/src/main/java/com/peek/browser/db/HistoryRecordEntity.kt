/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "linkHistory")
data class HistoryRecordEntity(
        @PrimaryKey(autoGenerate = true)
        @ColumnInfo(name = "id")
        val id: Int = 0,
        @ColumnInfo(name = "title")
        val title: String?,
        @ColumnInfo(name = "url")
        val url: String?,
        @ColumnInfo(name = "host")
        val host: String?,
        @ColumnInfo(name = "time")
        val time: Long
)

// Row subset backing getRecentHistoryRecordId()'s "most recent entry for this URL" lookup.
data class HistoryIdAndTime(
        @ColumnInfo(name = "id")
        val id: Int,
        @ColumnInfo(name = "time")
        val time: Long
)
