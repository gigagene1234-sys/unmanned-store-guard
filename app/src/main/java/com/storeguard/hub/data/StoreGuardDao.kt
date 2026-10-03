package com.storeguard.hub.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StoreGuardDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPayment(payment: PaymentEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCctvEvent(event: CctvEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTimeMatch(candidate: TimeMatchCandidateEntity): Long

    @Insert
    suspend fun insertLog(log: CollectionLogEntity): Long

    @Query("SELECT * FROM payments ORDER BY approvedAtMillis DESC LIMIT :limit")
    fun observeRecentPayments(limit: Int = 30): Flow<List<PaymentEntity>>

    @Query("SELECT * FROM cctv_events ORDER BY eventAtMillis DESC LIMIT :limit")
    fun observeRecentCctvEvents(limit: Int = 30): Flow<List<CctvEventEntity>>

    @Query("SELECT * FROM collection_logs ORDER BY createdAtMillis DESC LIMIT :limit")
    fun observeRecentLogs(limit: Int = 30): Flow<List<CollectionLogEntity>>

    @Query("SELECT COUNT(*) FROM payments WHERE approvedAtMillis BETWEEN :from AND :to")
    fun observePaymentCount(from: Long, to: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM cctv_events WHERE eventAtMillis BETWEEN :from AND :to")
    fun observeCctvEventCount(from: Long, to: Long): Flow<Int>

    @Query("SELECT * FROM payments WHERE approvedAtMillis BETWEEN :from AND :to ORDER BY approvedAtMillis")
    suspend fun getPaymentsBetween(from: Long, to: Long): List<PaymentEntity>

    @Query("SELECT * FROM cctv_events WHERE eventAtMillis BETWEEN :from AND :to ORDER BY eventAtMillis")
    suspend fun getCctvEventsBetween(from: Long, to: Long): List<CctvEventEntity>

    @Query("DELETE FROM time_match_candidates")
    suspend fun clearTimeMatches()

    @Query("DELETE FROM payments")
    suspend fun clearPayments()

    @Query("DELETE FROM cctv_events")
    suspend fun clearCctvEvents()

    @Query("DELETE FROM collection_logs")
    suspend fun clearLogs()
}
