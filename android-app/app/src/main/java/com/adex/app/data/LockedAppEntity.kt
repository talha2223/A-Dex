package com.adex.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity

// Locked app packages are enforced by accessibility monitoring and overlay activity.
@Entity(tableName = "locked_apps", primaryKeys = ["device_id", "package_name"])
data class LockedAppEntity(
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
