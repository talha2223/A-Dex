package com.adex.app.data

import androidx.room.Database
import androidx.room.RoomDatabase

// Room cache stores locked apps for parental-control checks.
@Database(entities = [LockedAppEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lockedAppDao(): LockedAppDao
}
