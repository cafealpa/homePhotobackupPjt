package com.chochocho.homephotoclient.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chochocho.homephotoclient.backup.BackupScheduler
import com.chochocho.homephotoclient.data.ApiFactory
import com.chochocho.homephotoclient.data.SettingsRepository
import com.chochocho.homephotoclient.data.toFriendlyMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(repository: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("") }
    var autoBackup by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf<String?>(null) }
    var resultOk by remember { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 알림 거부해도 자동 백업 자체는 동작 */ }

    LaunchedEffect(Unit) {
        val saved = repository.settings.first()
        serverUrl = saved.serverUrl
        apiKey = saved.apiKey
        deviceName = saved.deviceName
        autoBackup = saved.autoBackupEnabled
        loaded = true
    }

    if (!loaded) return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
            Text("서버 설정", style = MaterialTheme.typography.headlineSmall)

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("서버 주소 (예: http://192.168.0.2:8080)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API 키") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = deviceName,
                onValueChange = { deviceName = it },
                label = { Text("기기 이름 (예: 아빠 폰 — 사진 소유자 구분용)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        repository.save(serverUrl, apiKey, deviceName)
                        resultText = "저장했습니다"
                        resultOk = true
                    }
                }) { Text("저장") }

                Button(
                    enabled = !testing,
                    onClick = {
                        testing = true
                        resultText = null
                        scope.launch {
                            try {
                                repository.save(serverUrl, apiKey, deviceName)
                                val api = ApiFactory.create(serverUrl.trim(), apiKey.trim())
                                val months = api.months()
                                val total = months.sumOf { it.count }
                                resultText = "연결 성공 — 서버에 ${months.size}개월, 총 ${total}장"
                                resultOk = true
                            } catch (e: Exception) {
                                resultOk = false
                                resultText = e.toFriendlyMessage()
                            } finally {
                                testing = false
                            }
                        }
                    },
                ) { Text(if (testing) "확인 중..." else "연결 테스트") }

                if (testing) CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
            }

            resultText?.let {
                Text(
                    text = it,
                    color = if (resultOk) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("자동 백업", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "6시간마다 · Wi-Fi · 배터리 여유 시",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = autoBackup,
                    onCheckedChange = { enabled ->
                        autoBackup = enabled
                        if (enabled && Build.VERSION.SDK_INT >= 33) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        scope.launch { repository.setAutoBackup(enabled) }
                        BackupScheduler.setEnabled(context, enabled)
                    },
                )
            }
    }
}
