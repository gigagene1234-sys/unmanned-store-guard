package com.storeguard.hub.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.storeguard.hub.data.CollectionLogEntity
import com.storeguard.hub.data.StoreGuardDatabase
import com.storeguard.hub.parser.AnsiPosParser
import com.storeguard.hub.parser.UiTextNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class StoreAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastAnsiCaptureElapsed = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (packageName != ANSI_POS_PACKAGE) return

        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - lastAnsiCaptureElapsed < CAPTURE_DEBOUNCE_MS) return
        lastAnsiCaptureElapsed = nowElapsed

        val root = rootInActiveWindow ?: return
        val nodes = mutableListOf<UiTextNode>()
        collectTextNodes(root, nodes)
        root.recycle()

        val capturedAt = System.currentTimeMillis()
        scope.launch {
            val dao = StoreGuardDatabase.get(applicationContext).dao()
            val parsed = AnsiPosParser.parse(nodes, capturedAt)
            var inserted = 0
            for (payment in parsed) {
                if (dao.insertPayment(payment) > 0) inserted++
            }
            dao.insertLog(
                CollectionLogEntity(
                    source = "ANSI_POS",
                    level = if (parsed.isEmpty()) "DEBUG" else "INFO",
                    message = "화면 텍스트 ${nodes.size}개 / 거래 ${parsed.size}개 인식 / 신규 ${inserted}건",
                    createdAtMillis = capturedAt
                )
            )
        }
    }

    override fun onInterrupt() = Unit

    private fun collectTextNodes(node: AccessibilityNodeInfo, out: MutableList<UiTextNode>) {
        val text = node.text?.toString()?.trim().orEmpty()
        if (text.isNotBlank()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            out += UiTextNode(text, rect.left, rect.top, rect.right, rect.bottom)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextNodes(child, out)
            child.recycle()
        }
    }

    companion object {
        const val ANSI_POS_PACKAGE = "com.annecypos.foodasp"
        private const val CAPTURE_DEBOUNCE_MS = 900L
    }
}
