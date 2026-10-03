package com.storeguard.hub.parser

import com.storeguard.hub.data.PaymentEntity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

data class UiTextNode(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerY: Int get() = (top + bottom) / 2
}

object AnsiPosParser {
    private val dateRegex = Regex("(20\\d{2})[-./](\\d{1,2})[-./](\\d{1,2})")
    private val timeRegex = Regex("([01]?\\d|2[0-3]):([0-5]\\d):([0-5]\\d)")
    private val amountRegex = Regex("([0-9][0-9,]*)\\s*원")

    private val methods = listOf("신용카드", "카드", "현금", "간편결제", "기타")
    private val states = listOf("정상매출", "정상", "취소", "취소매출", "반품")

    fun parse(nodes: List<UiTextNode>, capturedAtMillis: Long = System.currentTimeMillis()): List<PaymentEntity> {
        if (nodes.isEmpty()) return emptyList()
        val rows = groupRows(nodes.filter { it.text.isNotBlank() })
        return rows.mapNotNull { parseRow(it, capturedAtMillis) }.distinctBy { it.externalKey }
    }

    private fun groupRows(nodes: List<UiTextNode>): List<List<UiTextNode>> {
        val sorted = nodes.sortedWith(compareBy<UiTextNode> { it.centerY }.thenBy { it.left })
        val groups = mutableListOf<MutableList<UiTextNode>>()
        val tolerancePx = 30
        for (node in sorted) {
            val target = groups.lastOrNull()
            if (target == null || abs(target.map { it.centerY }.average() - node.centerY) > tolerancePx) {
                groups += mutableListOf(node)
            } else {
                target += node
            }
        }
        return groups.map { it.sortedBy(UiTextNode::left) }
    }

    private fun parseRow(row: List<UiTextNode>, capturedAtMillis: Long): PaymentEntity? {
        val texts = row.map { it.text.trim() }
        val dateText = texts.firstNotNullOfOrNull { dateRegex.find(it)?.value } ?: return null
        val timeText = texts.firstNotNullOfOrNull { timeRegex.find(it)?.value } ?: return null
        val amountText = texts.firstNotNullOfOrNull { amountRegex.find(it)?.groupValues?.get(1) } ?: return null
        val method = methods.firstOrNull { method -> texts.any { it.contains(method) } } ?: "미분류"
        val state = states.firstOrNull { state -> texts.any { it.contains(state) } } ?: "미분류"
        val normalizedDate = dateRegex.find(dateText)!!.groupValues.drop(1).map { it.toInt() }
        val t = timeRegex.find(timeText)!!.groupValues.drop(1).map { it.toInt() }
        val date = LocalDate.of(normalizedDate[0], normalizedDate[1], normalizedDate[2])
        val time = LocalTime.of(t[0], t[1], t[2])
        val approved = LocalDateTime.of(date, time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val amount = amountText.replace(",", "").toLongOrNull() ?: return null
        val externalKey = listOf(date, time, amount, method, state).joinToString("|")
        return PaymentEntity(
            externalKey = externalKey,
            approvedAtMillis = approved,
            approvedAtText = time.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
            salesDateText = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
            paymentMethod = method,
            salesState = state,
            amountWon = amount,
            capturedAtMillis = capturedAtMillis
        )
    }

    fun parseFlatText(lines: List<String>, capturedAtMillis: Long = System.currentTimeMillis()): List<PaymentEntity> {
        val result = mutableListOf<PaymentEntity>()
        for (i in lines.indices) {
            val window = lines.drop(i).take(7)
            val date = window.firstOrNull { dateRegex.containsMatchIn(it) } ?: continue
            val time = window.firstOrNull { timeRegex.containsMatchIn(it) } ?: continue
            val amount = window.firstOrNull { amountRegex.containsMatchIn(it) } ?: continue
            val method = methods.firstOrNull { m -> window.any { it.contains(m) } } ?: "미분류"
            val state = states.firstOrNull { s -> window.any { it.contains(s) } } ?: "미분류"
            val fakeNodes = listOf(method, state, date, time, amount).mapIndexed { index, text ->
                UiTextNode(text, index * 100, 0, index * 100 + 90, 40)
            }
            parseRow(fakeNodes, capturedAtMillis)?.let(result::add)
        }
        return result.distinctBy { it.externalKey }
    }
}
