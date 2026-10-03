package com.storeguard.hub.matching

import com.storeguard.hub.data.CctvEventEntity
import com.storeguard.hub.data.PaymentEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeMatcherTest {
    @Test
    fun appliesClockOffsetBeforeMatching() {
        val event = CctvEventEntity(id = 1, eventAtMillis = 100_000, title = "", body = "", notificationKey = "e", capturedAtMillis = 0)
        val payment = PaymentEntity(id = 2, externalKey = "p", approvedAtMillis = 107_000, approvedAtText = "", salesDateText = "", paymentMethod = "", salesState = "", amountWon = 1000, capturedAtMillis = 0)
        val result = TimeMatcher.match(listOf(event), listOf(payment), cctvOffsetMillis = 7_000)
        assertEquals(1, result.size)
        assertEquals(0L, result.single().correctedDeltaMillis)
        assertEquals("EXACT_WINDOW", result.single().classification)
    }
}
