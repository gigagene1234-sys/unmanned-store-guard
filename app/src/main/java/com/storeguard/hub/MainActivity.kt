package com.storeguard.hub

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.storeguard.hub.service.DmssNotificationListener
import com.storeguard.hub.service.StoreAccessibilityService
import com.storeguard.hub.ui.DashboardViewModel
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val vm by viewModels<DashboardViewModel>()
    private var notificationGranted by mutableStateOf(false)
    private var accessibilityGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Dashboard(
                    vm = vm,
                    notificationGranted = notificationGranted,
                    accessibilityGranted = accessibilityGranted,
                    openNotificationSettings = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    openAccessibilitySettings = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    launchDmss = { launchPackage(DmssNotificationListener.DMSS_PACKAGE) },
                    launchAnsi = { launchPackage(StoreAccessibilityService.ANSI_POS_PACKAGE) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        notificationGranted = isNotificationListenerEnabled()
        accessibilityGranted = isAccessibilityEnabled()
    }

    private fun launchPackage(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) startActivity(intent)
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.contains(packageName)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val expected = ComponentName(this, StoreAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}

@Composable
private fun Dashboard(
    vm: DashboardViewModel,
    notificationGranted: Boolean,
    accessibilityGranted: Boolean,
    openNotificationSettings: () -> Unit,
    openAccessibilitySettings: () -> Unit,
    launchDmss: () -> Unit,
    launchAnsi: () -> Unit
) {
    val payments by vm.payments.collectAsState()
    val events by vm.events.collectAsState()
    val logs by vm.logs.collectAsState()
    var offsetText by remember { mutableStateOf(vm.currentOffsetMillis().toString()) }
    var suggestion by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("무인가게 관리 허브 v0.1", style = MaterialTheme.typography.headlineSmall)
            Text("DMSS 알림과 안시포스 거래를 같은 시간축에 수집하는 기반 버전입니다.")
        }
        item { StatusCard("DMSS 알림 접근", notificationGranted, openNotificationSettings) }
        item { StatusCard("안시포스 화면 접근성", accessibilityGranted, openAccessibilitySettings) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("앱 연결 테스트", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = launchDmss) { Text("DMSS 열기") }
                        OutlinedButton(onClick = launchAnsi) { Text("안시포스 열기") }
                    }
                    Text("안시포스의 매출 목록 화면을 열면 접근성 서비스가 보이는 거래 행을 자동 수집합니다.")
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("CCTV ↔ POS 시계 보정", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = offsetText,
                        onValueChange = { offsetText = it.filter { ch -> ch == '-' || ch.isDigit() } },
                        label = { Text("CCTV 보정값(ms)") },
                        supportingText = { Text("보정 CCTV 시각 = DMSS 시각 + 이 값") }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { offsetText.toLongOrNull()?.let(vm::saveOffsetMillis) }) { Text("저장") }
                        OutlinedButton(onClick = {
                            vm.suggestOffset { value ->
                                suggestion = value?.let { "추천 보정값: ${it}ms" } ?: "추천값 계산에 필요한 근접 표본이 3개 미만입니다."
                            }
                        }) { Text("자동 추천") }
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
        item { Text("최근 POS 거래 (${payments.size})", style = MaterialTheme.typography.titleMedium) }
        items(payments.take(10)) { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${p.salesDateText} ${p.approvedAtText} · ${p.paymentMethod} · ${p.salesState}")
                    Text("${NumberFormat.getIntegerInstance().format(p.amountWon)}원", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        item { Text("최근 DMSS 이벤트 (${events.size})", style = MaterialTheme.typography.titleMedium) }
        items(events.take(10)) { e ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(formatTime(e.eventAtMillis) + " · " + e.title.ifBlank { "DMSS 이벤트" })
                    if (e.body.isNotBlank()) Text(e.body)
                }
            }
        }
        item { Text("수집 로그", style = MaterialTheme.typography.titleMedium) }
        items(logs.take(12)) { log ->
            Text("${formatTime(log.createdAtMillis)} [${log.source}] ${log.message}")
        }
        item {
            Spacer(Modifier.height(24.dp))
            Text("v0.1은 방문자 신원 또는 절도 여부를 판정하지 않습니다. 수집·시간동기화가 실제 기기에서 검증된 뒤 방문자 추적/이상징후 모듈을 추가합니다.")
        }
    }
}

@Composable
private fun StatusCard(title: String, granted: Boolean, openSettings: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(if (granted) "허용됨" else "설정 필요")
            }
            OutlinedButton(onClick = openSettings) { Text(if (granted) "설정" else "허용") }
        }
    }
}

private fun formatTime(millis: Long): String = Instant.ofEpochMilli(millis)
    .atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
