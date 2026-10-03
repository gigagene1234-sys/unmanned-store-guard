package com.storeguard.hub.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiPosParserTest {
    @Test
    fun parsesScreenshotStyleRow() {
        val nodes = listOf(
            UiTextNode("신용카드", 0, 100, 100, 140),
            UiTextNode("정상매출", 120, 100, 230, 140),
            UiTextNode("2026-10-03", 250, 100, 400, 140),
            UiTextNode("21:15:53", 420, 100, 540, 140),
            UiTextNode("1,700", 560, 100, 680, 140)
        )
        val result = AnsiPosParser.parse(nodes, 0L)
        assertEquals(1, result.size)
        assertEquals(1700L, result.single().amountWon)
        assertEquals("21:15:53", result.single().approvedAtText)
        assertEquals("신용카드", result.single().paymentMethod)
        assertTrue(result.single().externalKey.contains("정상매출"))
    }

    @Test
    fun parsesSplitOcrCellsFromAnsiScreen() {
        val lines = listOf(
            "결제구분", "매출구분", "매출일자", "승인시간", "결제금액",
            "신용", "카드", "정상", "매출", "2026-10-03", "21:15:53", "1,700",
            "신용", "카드", "정상", "매출", "2026-10-03", "20:59:46", "2,800"
        )
        val result = AnsiPosParser.parseFlatText(lines, 0L)
        assertTrue(result.any { it.approvedAtText == "21:15:53" && it.amountWon == 1700L })
        assertTrue(result.any { it.approvedAtText == "20:59:46" && it.amountWon == 2800L })
    }
}
