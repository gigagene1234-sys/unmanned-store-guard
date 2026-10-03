package com.storeguard.hub.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.storeguard.hub.data.CctvEventEntity
import com.storeguard.hub.data.PaymentEntity
import com.storeguard.hub.data.StoreGuardDatabase
import com.storeguard.hub.matching.TimeMatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

class DashboardViewModel(app: Application) : AndroidViewModel(app) {
    private val db = StoreGuardDatabase.get(app)
    private val dao = db.dao()
    private val prefs = app.getSharedPreferences("storeguard_settings", 0)

    val payments = dao.observeRecentPayments().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val events = dao.observeRecentCctvEvents().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val logs = dao.observeRecentLogs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun currentOffsetMillis(): Long = prefs.getLong("cctv_offset_millis", 0L)

    fun saveOffsetMillis(value: Long) {
        prefs.edit().putLong("cctv_offset_millis", value).apply()
    }

    fun suggestOffset(onDone: (Long?) -> Unit) = viewModelScope.launch {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val end = start + 24 * 60 * 60 * 1000L - 1
        val suggested = TimeMatcher.suggestOffsetMillis(
            dao.getCctvEventsBetween(start, end),
            dao.getPaymentsBetween(start, end)
        )
        onDone(suggested)
    }

    fun seedDemo() = viewModelScope.launch {
        val now = System.currentTimeMillis()
        dao.insertCctvEvent(
            CctvEventEntity(
                eventAtMillis = now - 8_000,
                title = "Demo motion",
                body = "테스트용 DMSS 이벤트",
                notificationKey = "demo-$now",
                capturedAtMillis = now
            )
        )
        dao.insertPayment(
            PaymentEntity(
                externalKey = "demo-payment-$now",
                approvedAtMillis = now - 5_000,
                approvedAtText = "DEMO",
                salesDateText = LocalDate.now().toString(),
                paymentMethod = "신용카드",
                salesState = "정상매출",
                amountWon = 1700,
                capturedAtMillis = now
            )
        )
    }

    fun clearAll() = viewModelScope.launch {
        dao.clearPayments(); dao.clearCctvEvents(); dao.clearLogs(); dao.clearTimeMatches()
    }
}
