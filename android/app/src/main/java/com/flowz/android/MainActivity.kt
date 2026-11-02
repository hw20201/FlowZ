package com.flowz.android

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flowz.android.core.CoreState
import com.flowz.android.core.HubState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val status by CoreState.status.collectAsState()
                val reason by CoreState.failureReason.collectAsState()
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        CoreState.startService(this)
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("FlowZ", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("安卓版 · 骨架", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(24.dp))
                    Text(
                        when (status) {
                            HubState.IDLE -> "未连接"
                            HubState.CONNECTING -> "连接中…"
                            HubState.CONNECTED -> "已连接（直连演示）"
                            HubState.FAILED -> "连接失败 · 点击重试"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (status == HubState.FAILED && reason.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(reason, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = {
                            when (status) {
                                HubState.CONNECTED, HubState.CONNECTING -> {
                                    CoreState.stopService(this)
                                }
                                else -> {
                                    val intent = VpnService.prepare(this)
                                    if (intent == null) {
                                        CoreState.startService(this)
                                    } else {
                                        launcher.launch(intent)
                                    }
                                }
                            }
                        }
                    ) {
                        Text(
                            when (status) {
                                HubState.CONNECTED, HubState.CONNECTING -> "断开"
                                HubState.FAILED -> "重试"
                                else -> "连接"
                            }
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "骨架版：TUN + sing-box 核真实启动，走直连演示配置。订阅、节点列表、真实出站下个版本。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
