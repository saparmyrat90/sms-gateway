package com.gateway.sms

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gateway.sms.ui.theme.SmsGatewayTheme
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SmsGatewayTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    GatewayScreen(Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
private fun GatewayScreen(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { SettingsStore(context) }
    val sender = remember { AndroidSmsSender(context) }
    val scope = rememberCoroutineScope()
    val status by Gateway.store.status.collectAsState()

    var s by remember { mutableStateOf(store.load()) }
    var poll by remember { mutableStateOf(s.pollIntervalSec.toString()) }
    var cap by remember { mutableStateOf(s.maxPerMinute.toString()) }
    var message by remember { mutableStateOf<String?>(null) }
    var testPhone by remember { mutableStateOf("") }
    var sims by remember { mutableStateOf(sender.availableSims()) }

    val permissions = remember {
        buildList {
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        sims = sender.availableSims()
    }

    fun commit(enabled: Boolean = s.enabled): GatewaySettings {
        val updated = s.copy(
            pollIntervalSec = poll.toIntOrNull()?.coerceIn(1, 3600) ?: 3,
            maxPerMinute = cap.toIntOrNull()?.coerceIn(1, 600) ?: 30,
            enabled = enabled,
        )
        s = updated
        store.save(updated)
        return updated
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("SMS Gateway", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (status.running) "Running · sent ${status.sentCount}" else "Stopped",
            style = MaterialTheme.typography.titleMedium,
        )
        status.lastError?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        OutlinedTextField(
            value = s.serverUrl, onValueChange = { s = s.copy(serverUrl = it) },
            label = { Text("Server URL (https://...)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = s.gatewayKey, onValueChange = { s = s.copy(gatewayKey = it) },
            label = { Text("Gateway key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = poll, onValueChange = { poll = it.filter(Char::isDigit) },
                label = { Text("Idle poll (s)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = cap, onValueChange = { cap = it.filter(Char::isDigit) },
                label = { Text("Max SMS / min") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }

        Text("SIM", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = s.simSlot == -1, onClick = { s = s.copy(simSlot = -1) }, label = { Text("Default") })
            sims.forEach { sim ->
                FilterChip(
                    selected = s.simSlot == sim.slot,
                    onClick = { s = s.copy(simSlot = sim.slot) },
                    label = { Text(sim.label) },
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = s.startOnBoot, onCheckedChange = { s = s.copy(startOnBoot = it) })
            Text("Start on boot")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (status.running) {
                Button(onClick = { GatewayService.stop(context); s = s.copy(enabled = false) }) { Text("Stop") }
            } else {
                Button(onClick = {
                    val cur = commit(enabled = true)
                    val err = UrlPolicy.check(cur.serverUrl)
                        ?: if (cur.gatewayKey.isBlank()) "Gateway key is empty" else null
                    if (err != null) {
                        message = err
                        store.setEnabled(false)
                    } else {
                        message = null
                        GatewayService.start(context)
                    }
                }) { Text("Start") }
            }
            OutlinedButton(onClick = { commit(); message = null }) { Text("Save") }
        }

        OutlinedButton(onClick = { permLauncher.launch(permissions) }) { Text("Grant permissions") }
        OutlinedButton(onClick = {
            val pm = context.getSystemService(PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(context.packageName)) {
                message = "Battery optimization is already disabled"
            } else {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:${context.packageName}")),
                )
            }
        }) { Text("Ignore battery optimization") }

        HorizontalDivider()
        Text("Test SMS", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = testPhone, onValueChange = { testPhone = it },
            label = { Text("Phone (+993...)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = {
            val slot = s.simSlot
            scope.launch {
                val r = sender.send(testPhone.trim(), "SMS gateway test", slot)
                Gateway.store.addJob(JobLogEntry(0, testPhone.takeLast(4), r.ok, r.error, System.currentTimeMillis()))
            }
        }) { Text("Send test SMS") }

        HorizontalDivider()
        Text("Last jobs", style = MaterialTheme.typography.titleMedium)
        val fmt = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
        if (status.log.isEmpty()) Text("No jobs yet")
        status.log.forEach { e ->
            val label = if (e.id == 0L) "test" else "#${e.id}"
            Text("${fmt.format(Date(e.at))}  $label  ···${e.phoneTail}  " + if (e.ok) "sent" else "FAILED: ${e.error}")
        }
    }
}
