package com.storeguard.hub.matching

import com.storeguard.hub.data.CctvEventEntity
import com.storeguard.hub.data.PaymentEntity
import kotlin.math.abs

data class TimeMatchResult(
    val cctvEventId: Long,
    val paymentId: Long,
    val rawDeltaMillis: Long,
    val correctedDeltaMillis: Long,
    val classification: String
)

object TimeMatcher {
    fun match(
        events: List<CctvEventEntity>,
        payments: List<PaymentEntity>,
        cctvOffsetMillis: Long,
        maxWindowMillis: Long = 15_000L
    ): List<TimeMatchResult> {
        if (events.isEmpty() || payments.isEmpty()) return emptyList()
        return events.mapNotNull { event ->
            val correctedEventTime = event.eventAtMillis + cctvOffsetMillis
            val nearest = payments.minByOrNull { abs(it.approvedAtMillis - correctedEventTime) } ?: return@mapNotNull null
            val correctedDelta = nearest.approvedAtMillis - correctedEventTime
            if (abs(correctedDelta) > maxWindowMillis) return@mapNotNull null
            val rawDelta = nearest.approvedAtMillis - event.eventAtMillis
            TimeMatchResult(
                cctvEventId = event.id,
                paymentId = nearest.id,
                rawDeltaMillis = rawDelta,
                correctedDeltaMillis = correctedDelta,
                classification = when {
                    abs(correctedDelta) <= 2_000L -> "EXACT_WINDOW"
                    abs(correctedDelta) <= 5_000L -> "NEAR_WINDOW"
                    else -> "REVIEW_WINDOW"
                }
            )
        }
    }

    fun suggestOffsetMillis(events: List<CctvEventEntity>, payments: List<PaymentEntity>, maxPairGapMillis: Long = 30_000L): Long? {
        if (events.isEmpty() || payments.isEmpty()) return null
        val deltas = events.mapNotNull { event ->
            val nearest = payments.minByOrNull { abs(it.approvedAtMillis - event.eventAtMillis) } ?: return@mapNotNull null
            val d = nearest.approvedAtMillis - event.eventAtMillis
            d.takeIf { abs(it) <= maxPairGapMillis }
        }.sorted()
        if (deltas.size < 3) return null
        return deltas[deltas.size / 2]
    }
}
