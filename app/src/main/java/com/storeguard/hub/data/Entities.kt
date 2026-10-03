package com.storeguard.hub.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "payments",
    indices = [Index(value = ["externalKey"], unique = true), Index(value = ["approvedAtMillis"])]
)
data class PaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val externalKey: String,
    val approvedAtMillis: Long,
    val approvedAtText: String,
    val salesDateText: String,
    val paymentMethod: String,
    val salesState: String,
    val amountWon: Long,
    val capturedAtMillis: Long,
    val sourcePackage: String = "com.annecypos.foodasp"
)

@Entity(tableName = "cctv_events", indices = [Index(value = ["eventAtMillis"])])
data class CctvEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventAtMillis: Long,
    val title: String,
    val body: String,
    val notificationKey: String,
    val capturedAtMillis: Long,
    val sourcePackage: String = "com.mm.android.DMSS"
)

@Entity(
    tableName = "time_match_candidates",
    indices = [Index(value = ["paymentId"]), Index(value = ["cctvEventId"])]
)
data class TimeMatchCandidateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cctvEventId: Long,
    val paymentId: Long,
    val deltaMillis: Long,
    val classification: String,
    val createdAtMillis: Long
)

@Entity(tableName = "collection_logs", indices = [Index(value = ["createdAtMillis"])])
data class CollectionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val level: String,
    val message: String,
    val createdAtMillis: Long
)
