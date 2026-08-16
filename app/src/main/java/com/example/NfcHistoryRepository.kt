package com.example

import kotlinx.coroutines.flow.Flow

class NfcHistoryRepository(private val nfcHistoryDao: NfcHistoryDao) {
    val allHistory: Flow<List<NfcHistoryEntity>> = nfcHistoryDao.getAllHistory()

    suspend fun insert(history: NfcHistoryEntity) {
        nfcHistoryDao.insertHistory(history)
    }

    suspend fun clearAll() {
        nfcHistoryDao.clearHistory()
    }
}
