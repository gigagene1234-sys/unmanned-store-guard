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
            UiTextNode("1,700원", 560, 100, 680, 140)
        )
        val result = AnsiPosParser.parse(nodes, 0L)
        assertEquals(1, result.size)
        assertEquals(1700L, result.single().amountWon)
        assertEquals("21:15:53", result.single().approvedAtText)
        assertEquals("신용카드", result.single().paymentMethod)
        assertTrue(result.single().externalKey.contains("정상매출"))
    }
}
