package com.storeguard.hub

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.storeguard.hub.automation.AutomationPreferences
import com.storeguard.hub.service.DmssNotificationListener
import com.storeguard.hub.service.PosCaptureService
import com.storeguard.hub.service.StoreAccessibilityService
import com.storeguard.hub.ui.DashboardViewModel
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val vm by viewModels<DashboardViewModel>()

    private var notificationListenerGranted by mutableStateOf(false)
    private var accessibilityGranted by mutableStateOf(false)
    private var posCaptureRunning by mutableStateOf(false)
    private var maxAutoSessionActive by mutableStateOf(false)

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                val serviceIntent = Intent(this, PosCaptureService::class.java).apply {
                    putExtra(PosCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(PosCaptureService.EXTRA_RESULT_DATA, data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                posCaptureRunning = true
                launchPackage(PosCaptureService.ANSI_POS_PACKAGE)
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme {
                Dashboard(
                    vm = vm,
                    notificationListenerGranted = notificationListenerGranted,
                    accessibilityGranted = accessibilityGranted,
                    posCaptureRunning = posCaptureRunning,
                    maxAutoSessionActive = maxAutoSessionActive,
                    openNotificationSettings = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    openAccessibilitySettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    startMaxAutomation = {
                        if (!accessibilityGranted) {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        } else {
                            AutomationPreferences.startPosSession(this)
                            maxAutoSessionActive = true
                            launchPackage(StoreAccessibilityService.ANSI_POS_PACKAGE)
                        }
                    },
                    stopMaxAutomation = {
                        AutomationPreferences.stopPosSession(this)
                        maxAutoSessionActive = false
                    },
                    startPosCapture = { requestPosCapture() },
                    stopPosCapture = {
                        stopService(Intent(this, PosCaptureService::class.java))
                        posCaptureRunning = false
                    },
                    launchDmss = { launchPackage(DmssNotificationListener.DMSS_PACKAGE) },
                    launchAnsi = { launchPackage(StoreAccessibilityService.ANSI_POS_PACKAGE) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        notificationListenerGranted = isNotificationListenerEnabled()
        accessibilityGranted = isAccessibilityEnabled()
        posCaptureRunning = PosCaptureService.running.get()
        maxAutoSessionActive = AutomationPreferences.isPosSessionActive(this)
    }

    private fun requestPosCapture() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun launchPackage(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) startActivity(intent)
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabled =
            Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.contains(packageName)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val expected =
            ComponentName(this, StoreAccessibilityService::class.java).flattenToString()

        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}

@Composable
private fun Dashboard(
    vm: DashboardViewModel,
    notificationListenerGranted: Boolean,
    accessibilityGranted: Boolean,
    posCaptureRunning: Boolean,
    maxAutoSessionActive: Boolean,
    openNotificationSettings: () -> Unit,
    openAccessibilitySettings: () -> Unit,
    startMaxAutomation: () -> Unit,
    stopMaxAutomation: () -> Unit,
    startPosCapture: () -> Unit,
    stopPosCapture: () -> Unit,
    launchDmss: () -> Unit,
    launchAnsi: () -> Unit
) {
    val payments by vm.payments.collectAsState()
    val events by vm.events.collectAsState()
    val logs by vm.logs.collectAsState()

    var offsetText by remember { mutableStateOf(vm.currentOffsetMillis().toString()) }
    var suggestion by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "무인가게 관리 허브 v0.3 Max",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                "DMSS 이벤트는 상시 자동 수집하고, 안시포스는 사용자가 시작한 세션 안에서 검색·거래 읽기·목록 스크롤을 자동 수행합니다."
            )
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("1. DMSS 이벤트 자동 수집", style = MaterialTheme.typography.titleMedium)
                    Text(if (notificationListenerGranted) "알림 접근: 허용됨" else "알림 접근: 설정 필요")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = openNotificationSettings) {
                            Text(if (notificationListenerGranted) "알림 설정" else "알림 접근 허용")
                        }
                        OutlinedButton(onClick = launchDmss) { Text("DMSS 열기") }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("2. 안시포스 최대 자동 수집", style = MaterialTheme.typography.titleMedium)
                    Text(if (accessibilityGranted) "접근성 자동화: 허용됨" else "접근성 자동화: 설정 필요")
                    Text(
                        "자동 세션은 안시포스에서만 거래 화면을 읽고, 정확히 '검색'이라고 표시된 버튼을 1회 누른 뒤 거래목록 끝까지 자동 스크롤합니다."
                    )
                    Text(
                        "로그인·비밀번호 화면은 자동 입력하지 않으며, 결제/환불/송금 같은 금전 동작은 실행하지 않습니다."
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = openAccessibilitySettings) {
                            Text(if (accessibilityGranted) "접근성 설정" else "접근성 허용")
                        }

                        if (!maxAutoSessionActive) {
                            Button(onClick = startMaxAutomation) {
                                Text("전체 자동 수집 시작")
                            }
                        } else {
                            Button(onClick = stopMaxAutomation) {
                                Text("자동 수집 중지")
                            }
                        }
                    }

                    Text(
                        if (maxAutoSessionActive)
                            "현재 자동 수집 세션 실행 중"
                        else
                            "세션을 시작하면 안시포스가 열리고 가능한 범위의 수집을 자동 진행합니다."
                    )
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("3. OCR 보조 수집", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "안시포스가 접근성 트리에 거래 텍스트를 노출하지 않는 경우를 위한 보조 방식입니다. Android 화면공유 승인이 필요합니다."
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!posCaptureRunning) {
                            OutlinedButton(onClick = startPosCapture) {
                                Text("OCR 수집 시작")
                            }
                        } else {
                            OutlinedButton(onClick = stopPosCapture) {
                                Text("OCR 수집 중지")
                            }
                        }
                        OutlinedButton(onClick = launchAnsi) { Text("안시포스 열기") }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("CCTV ↔ POS 시계 보정", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = offsetText,
                        onValueChange = {
                            offsetText = it.filter { ch -> ch == '-' || ch.isDigit() }
                        },
                        label = { Text("CCTV 보정값(ms)") },
                        supportingText = { Text("보정 CCTV 시각 = DMSS 시각 + 이 값") }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                offsetText.toLongOrNull()?.let(vm::saveOffsetMillis)
                            }
                        ) {
                            Text("저장")
                        }

                        OutlinedButton(
                            onClick = {
                                vm.suggestOffset { value ->
                                    suggestion =
                                        value?.let { "추천 보정값: ${it}ms" }
                                            ?: "추천값 계산에 필요한 근접 표본이 3개 미만입니다."
                                }
                            }
                        ) {
                            Text("자동 추천")
                        }
                    }
                    suggestion?.let { Text(it) }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::seedDemo) { Text("데모 데이터") }
                OutlinedButton(onClick = vm::clearAll) { Text("로컬 데이터 초기화") }
            }
        }

        item {
            Text(
                "최근 POS 거래 (${payments.size})",
                style = MaterialTheme.typography.titleMedium
            )
        }

        items(payments.take(15)) { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "${p.salesDateText} ${p.approvedAtText} · " +
                            "${p.paymentMethod} · ${p.salesState}"
                    )
                    Text(
                        "${NumberFormat.getIntegerInstance().format(p.amountWon)}원",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }

        item {
            Text(
                "최근 DMSS 이벤트 (${events.size})",
                style = MaterialTheme.typography.titleMedium
            )
        }

        items(events.take(15)) { e ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        formatTime(e.eventAtMillis) +
                            " · " +
                            e.title.ifBlank { "DMSS 이벤트" }
                    )
                    if (e.body.isNotBlank()) Text(e.body)
                }
            }
        }

        item {
            Text("수집 로그", style = MaterialTheme.typography.titleMedium)
        }

        items(logs.take(20)) { log ->
            Text(
                "${formatTime(log.createdAtMillis)} " +
                    "[${log.source}/${log.level}] ${log.message}"
            )
        }

        item {
            Spacer(Modifier.height(24.dp))
            Text(
                "현재 자동화는 매장 관리용 데이터 수집에 한정됩니다. 얼굴 신원 식별은 하지 않으며, 결제 여부나 행동만으로 절도를 자동 확정하지 않습니다."
            )
        }
    }
}

private fun formatTime(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
