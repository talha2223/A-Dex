package com.adex.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LockedAppDao {
    // Returns all locked package names for in-memory checks.
    @Query("SELECT package_name FROM locked_apps WHERE device_id = :deviceId")
    suspend fun getLockedPackages(deviceId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: LockedAppEntity)

    @Query("DELETE FROM locked_apps WHERE device_id = :deviceId AND package_name = :packageName")
    suspend fun remove(deviceId: String, packageName: String): Int

    @Query("DELETE FROM locked_apps WHERE device_id = :deviceId")
    suspend fun clearForDevice(deviceId: String)
}
