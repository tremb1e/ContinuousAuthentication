package com.continuousauth.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "upload_progress")
data class UploadProgress(
    @PrimaryKey val id: Int = 1,
    val acknowledgedPackets: Long = 0L,
    val lastAckTimestamp: Long = 0L,
    val lastPacketSeqNo: Long = 0L
)
