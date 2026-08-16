package com.example

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NfcHistoryDao {
    @Query("SELECT * FROM nfc_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<NfcHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: NfcHistoryEntity)

    @Query("DELETE FROM nfc_history")
    suspend fun clearHistory()
}
