/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
        entities = [HistoryRecordEntity::class, FaviconEntity::class],
        version = 2,
        exportSchema = false
)
abstract class PeekDatabase : RoomDatabase() {

    abstract fun historyDao(): HistoryDao
    abstract fun faviconDao(): FaviconDao

    companion object {
        private const val DATABASE_NAME = "PeekDB"

        // Version 1 was a hand-rolled SQLiteOpenHelper with the same two tables but no NOT NULL
        // constraints anywhere. Rebuild each table to Room's expected schema (matching the
        // Kotlin nullability in HistoryRecordEntity/FaviconEntity) while preserving existing rows,
        // rather than assuming the old on-disk schema already happens to match byte-for-byte.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE linkHistory RENAME TO linkHistory_old")
                db.execSQL(
                        "CREATE TABLE linkHistory (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "title TEXT, " +
                                "url TEXT, " +
                                "host TEXT, " +
                                "time INTEGER NOT NULL)"
                )
                // WHERE guards against a migration crash if any historical row is missing a
                // NOT NULL column below - vanishingly unlikely (every insert path always
                // populates time), but a dropped history/favicon row is harmless where a
                // failed migration would brick the app for that user.
                db.execSQL(
                        "INSERT INTO linkHistory (id, title, url, host, time) " +
                                "SELECT id, title, url, host, time FROM linkHistory_old WHERE time IS NOT NULL"
                )
                db.execSQL("DROP TABLE linkHistory_old")

                db.execSQL("ALTER TABLE favicons RENAME TO favicons_old")
                db.execSQL(
                        "CREATE TABLE favicons (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "url TEXT NOT NULL, " +
                                "pageUrl TEXT, " +
                                "imageSize INTEGER NOT NULL, " +
                                "data BLOB, " +
                                "time INTEGER NOT NULL)"
                )
                db.execSQL(
                        "INSERT INTO favicons (id, url, pageUrl, imageSize, data, time) " +
                                "SELECT id, url, pageUrl, imageSize, data, time FROM favicons_old " +
                                "WHERE url IS NOT NULL AND imageSize IS NOT NULL AND time IS NOT NULL"
                )
                db.execSQL("DROP TABLE favicons_old")
            }
        }

        fun build(context: Context): PeekDatabase {
            return Room.databaseBuilder(context.applicationContext, PeekDatabase::class.java, DATABASE_NAME)
                    .addMigrations(MIGRATION_1_2)
                    // Existing call sites (HistoryActivity, SettingsActivity, SearchURLSuggestionsContainer,
                    // MainApplication.saveUrlInHistory) call DatabaseHelper synchronously, some from the
                    // main thread, exactly as the old raw SQLiteOpenHelper allowed. Preserve that instead
                    // of forcing a broader async rewrite of those call sites in this migration.
                    .allowMainThreadQueries()
                    .build()
        }
    }
}
