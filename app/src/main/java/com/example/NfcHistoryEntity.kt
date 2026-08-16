package com.example

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "nfc_history")
data class NfcHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val operationMode: String,
    val tagId: String,
    val data: String
)
