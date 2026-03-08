package com.adex.app

import android.app.Application
import androidx.room.Room
import com.adex.app.data.AppDatabase

// Application container provides process-wide Room database access.
class ADexApplication : Application() {
    lateinit var db: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "adex.db"
        ).fallbackToDestructiveMigration().build()
    }
}
