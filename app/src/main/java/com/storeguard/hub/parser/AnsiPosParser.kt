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
    private val amountWithCommaRegex = Regex("^\\s*([0-9]{1,3}(?:,[0-9]{3})+)\\s*(?:원)?\\s*$")
    private val amountPlainRegex = Regex("^\\s*([0-9]{3,7})\\s*(?:원)?\\s*$")

    private val methods = listOf("신용카드", "현금", "간편결제", "카드", "기타")
    private val states = listOf("정상매출", "취소매출", "정상", "취소", "반품")

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
        val amountText = texts.firstNotNullOfOrNull { extractAmount(it) } ?: return null
        val collapsed = texts.joinToString("").replace("\\s+".toRegex(), "")
        val method = methods.firstOrNull { collapsed.contains(it) } ?: "미분류"
        val state = states.firstOrNull { collapsed.contains(it) } ?: "미분류"
        return buildPayment(dateText, timeText, amountText, method, state, capturedAtMillis)
    }

    /**
     * Parses ML Kit OCR lines without persisting the raw OCR text.
     *
     * ANSI POS commonly splits cell labels vertically (e.g. "신용" / "카드",
     * "정상" / "매출"). We anchor each candidate row on its HH:mm:ss value,
     * then inspect only a small neighboring window.
     */
    fun parseFlatText(lines: List<String>, capturedAtMillis: Long = System.currentTimeMillis()): List<PaymentEntity> {
        val clean = lines.map { it.trim() }.filter { it.isNotBlank() }
        if (clean.isEmpty()) return emptyList()

        val result = mutableListOf<PaymentEntity>()

        clean.forEachIndexed { timeIndex, line ->
            val timeText = timeRegex.find(line)?.value ?: return@forEachIndexed
            val from = (timeIndex - 7).coerceAtLeast(0)
            val to = (timeIndex + 7).coerceAtMost(clean.lastIndex)
            val window = clean.subList(from, to + 1)

            val dateText = findDate(window) ?: return@forEachIndexed
            val amountText = findNearestAmount(clean, timeIndex) ?: return@forEachIndexed

            val collapsed = window.joinToString("")
                .replace("\\s+".toRegex(), "")
                .replace("\n", "")

            val method = when {
                collapsed.contains("신용카드") -> "신용카드"
                collapsed.contains("현금") -> "현금"
                collapsed.contains("간편결제") -> "간편결제"
                collapsed.contains("카드") -> "카드"
                else -> "미분류"
            }

            val state = when {
                collapsed.contains("취소매출") -> "취소매출"
                collapsed.contains("정상매출") -> "정상매출"
                collapsed.contains("반품") -> "반품"
                collapsed.contains("취소") -> "취소"
                collapsed.contains("정상") -> "정상"
                else -> "미분류"
            }

            buildPayment(dateText, timeText, amountText, method, state, capturedAtMillis)
                ?.let(result::add)
        }

        return result.distinctBy { it.externalKey }
    }

    private fun findDate(window: List<String>): String? {
        window.forEach { line ->
            dateRegex.find(line)?.value?.let { return it }
        }
        for (i in window.indices) {
            for (count in 2..3) {
                if (i + count > window.size) continue
                val joined = window.subList(i, i + count).joinToString("")
                    .replace("\\s+".toRegex(), "")
                dateRegex.find(joined)?.value?.let { return it }
            }
        }
        return null
    }

    private fun findNearestAmount(lines: List<String>, timeIndex: Int): String? {
        val offsets = listOf(1, 2, 3, 4, -1, -2, -3, -4, 5, -5)
        for (offset in offsets) {
            val index = timeIndex + offset
            if (index !in lines.indices) continue
            val line = lines[index]
            if (dateRegex.containsMatchIn(line) || timeRegex.containsMatchIn(line)) continue
            extractAmount(line)?.let { return it }
        }
        return null
    }

    private fun extractAmount(text: String): String? {
        val comma = amountWithCommaRegex.matchEntire(text)?.groupValues?.getOrNull(1)
        if (comma != null) return comma
        return amountPlainRegex.matchEntire(text)?.groupValues?.getOrNull(1)
    }

    private fun buildPayment(
        dateText: String,
        timeText: String,
        amountText: String,
        method: String,
        state: String,
        capturedAtMillis: Long
    ): PaymentEntity? {
        val dateMatch = dateRegex.find(dateText) ?: return null
        val timeMatch = timeRegex.find(timeText) ?: return null

        return try {
            val dateParts = dateMatch.groupValues.drop(1).map { it.toInt() }
            val timeParts = timeMatch.groupValues.drop(1).map { it.toInt() }
            val date = LocalDate.of(dateParts[0], dateParts[1], dateParts[2])
            val time = LocalTime.of(timeParts[0], timeParts[1], timeParts[2])
            val approved = LocalDateTime.of(date, time)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
            val amount = amountText.replace(",", "").toLong()

            PaymentEntity(
                externalKey = listOf(date, time, amount, method, state).joinToString("|"),
                approvedAtMillis = approved,
                approvedAtText = time.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                salesDateText = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                paymentMethod = method,
                salesState = state,
                amountWon = amount,
                capturedAtMillis = capturedAtMillis
            )
        } catch (_: Exception) {
            null
        }
    }
}
