package com.storeguard.hub

import android.Manifest
import android.app.Activity
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
import com.storeguard.hub.service.DmssNotificationListener
import com.storeguard.hub.service.PosCaptureService
import com.storeguard.hub.ui.DashboardViewModel
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val vm by viewModels<DashboardViewModel>()
    private var notificationListenerGranted by mutableStateOf(false)
    private var posCaptureRunning by mutableStateOf(false)

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

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme {
                Dashboard(
                    vm = vm,
                    notificationListenerGranted = notificationListenerGranted,
                    posCaptureRunning = posCaptureRunning,
                    openNotificationSettings = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    startPosCapture = { requestPosCapture() },
                    stopPosCapture = {
                        stopService(Intent(this, PosCaptureService::class.java))
                        posCaptureRunning = false
                    },
                    launchDmss = { launchPackage(DmssNotificationListener.DMSS_PACKAGE) },
                    launchAnsi = { launchPackage(PosCaptureService.ANSI_POS_PACKAGE) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        notificationListenerGranted = isNotificationListenerEnabled()
        posCaptureRunning = PosCaptureService.running.get()
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
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.contains(packageName)
    }
}

@Composable
private fun Dashboard(
    vm: DashboardViewModel,
    notificationListenerGranted: Boolean,
    posCaptureRunning: Boolean,
    openNotificationSettings: () -> Unit,
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
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("무인가게 관리 허브 v0.2 Safe", style = MaterialTheme.typography.headlineSmall)
            Text("민감한 접근성 권한 없이, Android가 매번 명시적으로 승인하는 화면 공유 세션에서만 POS 거래를 OCR 수집합니다.")
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("DMSS 이벤트 수집", style = MaterialTheme.typography.titleMedium)
                    Text(if (notificationListenerGranted) "알림 접근 허용됨" else "알림 접근 설정 필요")
                    Text("StoreGuard 코드는 DMSS 패키지의 알림만 저장하도록 제한되어 있습니다.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = openNotificationSettings) {
                            Text(if (notificationListenerGranted) "알림 접근 설정" else "알림 접근 허용")
                        }
                        OutlinedButton(onClick = launchDmss) { Text("DMSS 열기") }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("안시포스 거래 수집", style = MaterialTheme.typography.titleMedium)
                    Text(if (posCaptureRunning) "수집 세션 실행 중" else "수집 세션 중지됨")
                    Text("시작을 누르면 Android 화면 공유 승인창이 표시됩니다. 승인 후 15분 동안 화면을 주기적으로 OCR하며, 원본 화면과 원문 OCR 결과는 저장하지 않습니다.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!posCaptureRunning) {
                            Button(onClick = startPosCapture) { Text("POS 수집 시작") }
                        } else {
                            Button(onClick = stopPosCapture) { Text("POS 수집 중지") }
                        }
                        OutlinedButton(onClick = launchAnsi) { Text("안시포스 열기") }
                    }
                    Text("수집 중에는 시스템 알림이 계속 표시되며 알림의 '중지' 버튼으로 즉시 종료할 수 있습니다.")
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
                                suggestion = value?.let { "추천 보정값: ${it}ms" }
                                    ?: "추천값 계산에 필요한 근접 표본이 3개 미만입니다."
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
                    Text(
                        "${NumberFormat.getIntegerInstance().format(p.amountWon)}원",
                        style = MaterialTheme.typography.titleMedium
                    )
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
            Text("안전 경계: StoreGuard는 얼굴 신원을 식별하지 않고, 결제 사실만으로 정상/절도를 확정하지 않습니다. 이후 이상징후 기능도 '검토 후보'만 생성하도록 유지합니다.")
        }
    }
}

private fun formatTime(millis: Long): String = Instant.ofEpochMilli(millis)
    .atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
