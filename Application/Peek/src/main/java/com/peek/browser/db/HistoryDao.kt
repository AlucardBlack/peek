/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface HistoryDao {

    @Insert
    fun insert(record: HistoryRecordEntity): Long

    @Update
    fun update(record: HistoryRecordEntity)

    @Query("DELETE FROM linkHistory WHERE id = :id")
    fun deleteById(id: Int)

    @Query("DELETE FROM linkHistory")
    fun deleteAll()

    @Query("SELECT id, time FROM linkHistory WHERE url = :url ORDER BY time DESC LIMIT 1")
    fun getMostRecentIdAndTime(url: String): HistoryIdAndTime?

    @Query("SELECT * FROM linkHistory ORDER BY time DESC")
    fun getAll(): List<HistoryRecordEntity>

    @Query("SELECT * FROM linkHistory ORDER BY time DESC LIMIT :limit")
    fun getRecent(limit: Int): List<HistoryRecordEntity>
}
