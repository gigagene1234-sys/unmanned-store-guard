package com.storeguard.hub.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.storeguard.hub.automation.AutomationPreferences
import com.storeguard.hub.data.CollectionLogEntity
import com.storeguard.hub.data.StoreGuardDatabase
import com.storeguard.hub.parser.AnsiPosParser
import com.storeguard.hub.parser.UiTextNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Maximum-automation collector for the two store-management apps only.
 *
 * Important safeguards:
 * - No automation runs unless the user explicitly starts a StoreGuard session.
 * - The service is package-scoped to ANSI POS + DMSS in accessibility_service_config.xml.
 * - It never fills passwords, presses login/approval/payment buttons, or sends money.
 * - ANSI automation is limited to: read visible text -> click the exact "검색" button
 *   once -> scroll the result list -> save transaction rows.
 * - Login screens are detected and left for the user.
 */
class StoreAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var activeSessionId = 0L
    private var searchClicked = false
    private var scrollCount = 0
    private var sameScreenCount = 0
    private var lastSignature = ""
    private var lastCaptureElapsed = 0L
    private var loginNoticeSession = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        log("ACCESSIBILITY", "INFO", "자동 수집 접근성 서비스가 연결되었습니다.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return

        when (packageName) {
            ANSI_POS_PACKAGE -> handleAnsiPos()
            DMSS_PACKAGE -> {
                // DMSS is observed only for future user-initiated playback automation.
                // No automatic clicks are performed in DMSS in v0.3.
            }
        }
    }

    override fun onInterrupt() {
        log("ACCESSIBILITY", "WARN", "접근성 서비스가 중단되었습니다.")
    }

    private fun handleAnsiPos() {
        if (!AutomationPreferences.isPosSessionActive(this)) return

        val sessionId = AutomationPreferences.sessionId(this)
        if (sessionId <= 0L) return

        if (sessionId != activeSessionId) {
            activeSessionId = sessionId
            searchClicked = false
            scrollCount = 0
            sameScreenCount = 0
            lastSignature = ""
            lastCaptureElapsed = 0L
            log("ANSI_AUTO", "INFO", "POS 자동 수집 세션 #$sessionId 시작")
        }

        val startedAt = AutomationPreferences.startedAt(this)
        if (startedAt > 0L && System.currentTimeMillis() - startedAt > SESSION_TIMEOUT_MS) {
            finishSession("자동 수집 제한시간 초과로 종료")
            return
        }

        val elapsed = SystemClock.elapsedRealtime()
        if (elapsed - lastCaptureElapsed < CAPTURE_DEBOUNCE_MS) return
        lastCaptureElapsed = elapsed

        val root = rootInActiveWindow ?: return
        val nodes = mutableListOf<UiTextNode>()
        val flatTexts = mutableListOf<String>()
        collectTextNodes(root, nodes, flatTexts)

        if (looksLikeLoginScreen(flatTexts)) {
            if (loginNoticeSession != sessionId) {
                loginNoticeSession = sessionId
                log(
                    "ANSI_AUTO",
                    "ACTION_REQUIRED",
                    "안시포스 로그인 화면이 감지되었습니다. 로그인은 자동 입력하지 않습니다. 사용자가 로그인하면 수집을 이어갑니다."
                )
            }
            return
        }

        scope.launch {
            val dao = StoreGuardDatabase.get(applicationContext).dao()
            val layoutParsed = AnsiPosParser.parse(nodes)
            val flatParsed = AnsiPosParser.parseFlatText(flatTexts)
            val parsed = (layoutParsed + flatParsed).distinctBy { it.externalKey }

            var inserted = 0
            parsed.forEach { payment ->
                if (dao.insertPayment(payment) > 0L) inserted++
            }

            if (inserted > 0) {
                dao.insertLog(
                    CollectionLogEntity(
                        source = "ANSI_AUTO",
                        level = "INFO",
                        message = "거래 신규 ${inserted}건 저장 (화면 인식 ${parsed.size}건)",
                        createdAtMillis = System.currentTimeMillis()
                    )
                )
            }
        }

        if (!searchClicked) {
            val clicked = clickExactText(root, "검색")
            if (clicked) {
                searchClicked = true
                log("ANSI_AUTO", "INFO", "현재 조회조건의 '검색' 버튼을 자동 실행했습니다.")
                return
            }
        }

        val signature = buildScreenSignature(flatTexts)
        if (signature == lastSignature && signature.isNotBlank()) {
            sameScreenCount++
        } else {
            sameScreenCount = 0
            lastSignature = signature
        }

        if (sameScreenCount >= SAME_SCREEN_LIMIT) {
            finishSession("동일한 마지막 화면이 반복되어 수집 완료")
            return
        }

        if (scrollCount >= MAX_SCROLLS) {
            finishSession("최대 스크롤 횟수에 도달하여 수집 종료")
            return
        }

        val scroller = findScrollable(root)
        if (scroller == null) {
            if (searchClicked) finishSession("스크롤 가능한 거래목록을 찾지 못해 현재 화면 수집 후 종료")
            return
        }

        val scrolled = scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        if (scrolled) {
            scrollCount++
        } else if (searchClicked) {
            finishSession("거래목록의 끝에 도달하여 수집 완료")
        }
    }

    private fun collectTextNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<UiTextNode>,
        flatTexts: MutableList<String>
    ) {
        val text = node.text?.toString()?.trim().orEmpty()
        if (text.isNotBlank()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            out += UiTextNode(text, rect.left, rect.top, rect.right, rect.bottom)
            flatTexts += text
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextNodes(child, out, flatTexts)
        }
    }

    private fun looksLikeLoginScreen(texts: List<String>): Boolean {
        if (texts.isEmpty()) return false
        val joined = texts.joinToString(" ").replace("\n", " ")
        val loginWords = listOf("로그인", "비밀번호", "패스워드", "아이디", "ID")
        val transactionWords = listOf("승인시간", "매출일자", "결제금액", "정상매출")
        return loginWords.count { joined.contains(it, ignoreCase = true) } >= 2 &&
            transactionWords.none { joined.contains(it) }
    }

    private fun clickExactText(root: AccessibilityNodeInfo, target: String): Boolean {
        val matches = root.findAccessibilityNodeInfosByText(target)
            .filter { it.text?.toString()?.trim() == target }

        for (node in matches) {
            var current: AccessibilityNodeInfo? = node
            var depth = 0
            while (current != null && depth < 4) {
                if (current.isClickable) {
                    return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                current = current.parent
                depth++
            }
        }
        return false
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollable(child)
            if (found != null) return found
        }
        return null
    }

    private fun buildScreenSignature(texts: List<String>): String {
        val relevant = texts
            .filter {
                it.contains(":") ||
                    it.contains("매출") ||
                    it.contains("신용") ||
                    it.contains("현금") ||
                    it.any(Char::isDigit)
            }
            .takeLast(40)
            .joinToString("|")
        return relevant.hashCode().toString()
    }

    private fun finishSession(reason: String) {
        AutomationPreferences.stopPosSession(this)
        log("ANSI_AUTO", "COMPLETE", reason)
    }

    private fun log(source: String, level: String, message: String) {
        scope.launch {
            StoreGuardDatabase.get(applicationContext).dao().insertLog(
                CollectionLogEntity(
                    source = source,
                    level = level,
                    message = message,
                    createdAtMillis = System.currentTimeMillis()
                )
            )
        }
    }

    companion object {
        const val ANSI_POS_PACKAGE = "com.annecypos.foodasp"
        const val DMSS_PACKAGE = "com.mm.android.DMSS"

        private const val CAPTURE_DEBOUNCE_MS = 650L
        private const val SESSION_TIMEOUT_MS = 5 * 60 * 1000L
        private const val MAX_SCROLLS = 80
        private const val SAME_SCREEN_LIMIT = 3
    }
}
