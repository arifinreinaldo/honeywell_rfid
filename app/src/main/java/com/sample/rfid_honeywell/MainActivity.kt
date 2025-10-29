package com.sample.rfid_honeywell

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.sample.rfid_honeywell.helper.HoneywellRfidHelper
import com.sample.rfid_honeywell.ui.theme.RFIDHoneywellTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var rfidHelper: HoneywellRfidHelper
    private var isInitialized = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            initializeRfid()
        } else {
            Log.e("MainActivity", "Permissions denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        rfidHelper = HoneywellRfidHelper.getInstance(this)

        setContent {
            RFIDHoneywellTheme {
                RfidScreen(rfidHelper = rfidHelper)
            }
        }

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            // Android 11 and below
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.BLUETOOTH)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            }
        }

        // Location permissions (required for Bluetooth scanning)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        } else {
            initializeRfid()
        }
    }

    private fun initializeRfid() {
        if (!isInitialized) {
            lifecycleScope.launch {
                val success = rfidHelper.initialize()
                isInitialized = success
                Log.d("MainActivity", "RFID initialized: $success")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        rfidHelper.cleanup()
    }
}

@Composable
fun RfidScreen(rfidHelper: HoneywellRfidHelper) {
    var connectionState by remember { mutableStateOf(HoneywellRfidHelper.ConnectionState.DISCONNECTED) }
    var isScanning by remember { mutableStateOf(false) }
    var tagList by remember { mutableStateOf<List<HoneywellRfidHelper.TagInfo>>(emptyList()) }
    var statusMessage by remember { mutableStateOf("Ready") }
    var bluetoothAddress by remember { mutableStateOf("0C:23:69:19:AB:FB") }
    var inventoryModeEnabled by remember { mutableStateOf(false) }

    // Set up connection state listener
    LaunchedEffect(Unit) {
        rfidHelper.setConnectionStateListener { state ->
            connectionState = state
            statusMessage = when (state) {
                HoneywellRfidHelper.ConnectionState.DISCONNECTED -> "Disconnected"
                HoneywellRfidHelper.ConnectionState.CONNECTED -> "Connected - Creating reader..."
                HoneywellRfidHelper.ConnectionState.READER_READY -> "Reader ready"
            }

            // Auto-create reader after connection
            if (state == HoneywellRfidHelper.ConnectionState.CONNECTED) {
                rfidHelper.createReader(delayMs = 1000)
            }

            // Disable inventory mode on disconnect
            if (state == HoneywellRfidHelper.ConnectionState.DISCONNECTED) {
                inventoryModeEnabled = false
                rfidHelper.setInventoryMode(false)
            }
        }
    }

    // Handle inventory mode toggle
    LaunchedEffect(inventoryModeEnabled) {
        rfidHelper.setInventoryMode(inventoryModeEnabled) { tags ->
            tagList = tags
            isScanning = true
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Title
            Text(
                text = "RFID Honeywell Scanner",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Status
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (connectionState) {
                        HoneywellRfidHelper.ConnectionState.READER_READY -> MaterialTheme.colorScheme.primaryContainer
                        HoneywellRfidHelper.ConnectionState.CONNECTED -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Status: $statusMessage")
                    Text("Connection: ${connectionState.name}")
                    Text("Mode: ${if (inventoryModeEnabled) "Inventory (Trigger)" else "Manual"}")
                    Text("Scanning: ${if (isScanning) "Active" else "Stopped"}")
                    Text("Tags found: ${tagList.size}")
                }
            }

            // Bluetooth address input (for Bluetooth connection)
            OutlinedTextField(
                value = bluetoothAddress,
                onValueChange = { bluetoothAddress = it },
                label = { Text("Bluetooth Address (optional)") },
                placeholder = { Text("e.g., 0C:23:69:19:96:46") },
                modifier = Modifier.fillMaxWidth(),
                enabled = connectionState == HoneywellRfidHelper.ConnectionState.DISCONNECTED
            )

            // Inventory Mode Toggle
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (inventoryModeEnabled)
                        MaterialTheme.colorScheme.tertiaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Inventory Mode",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (inventoryModeEnabled)
                                "Use trigger key to scan"
                            else
                                "Use buttons to scan",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = inventoryModeEnabled,
                        onCheckedChange = {
                            inventoryModeEnabled = it
                            if (it) {
                                statusMessage = "Inventory mode: Press trigger to scan"
                            } else {
                                statusMessage = "Manual mode: Use buttons to scan"
                                isScanning = false
                            }
                        },
                        enabled = connectionState == HoneywellRfidHelper.ConnectionState.READER_READY
                    )
                }
            }

            // Connect Button
            Button(
                onClick = {
                    if (connectionState == HoneywellRfidHelper.ConnectionState.DISCONNECTED) {
                        val success = if (bluetoothAddress.isNotBlank()) {
                            rfidHelper.connect(bluetoothAddress)
                        } else {
                            // Try serial connection (built-in device)
                            rfidHelper.connectSerial()
                        }
                        statusMessage = if (success) "Connecting..." else "Connection failed"
                    } else {
                        rfidHelper.disconnect()
                        tagList = emptyList()
                        isScanning = false
                        statusMessage = "Disconnected"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (connectionState == HoneywellRfidHelper.ConnectionState.DISCONNECTED)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
            ) {
                Text(
                    if (connectionState == HoneywellRfidHelper.ConnectionState.DISCONNECTED)
                        "Connect"
                    else
                        "Disconnect"
                )
            }

            // Start Scan Button (only show in manual mode)
            if (!inventoryModeEnabled) {
                Button(
                    onClick = {
                        if (!isScanning) {
                            val result = rfidHelper.startScan(
                                mode = HoneywellRfidHelper.ScanMode.NORMAL
                            ) { tags ->
                                tagList = tags
                            }
                            if (result.isEmpty()) {
                                isScanning = true
                                statusMessage = "Scanning..."
                            } else {
                                statusMessage = result
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = connectionState == HoneywellRfidHelper.ConnectionState.READER_READY && !isScanning
                ) {
                    Text("Start Scan")
                }

                // Stop Scan Button
                Button(
                    onClick = {
                        rfidHelper.stopScan()
                        isScanning = false
                        statusMessage = "Scan stopped. Found ${tagList.size} tags"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isScanning,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("Stop Scan")
                }
            } else {
                // Inventory mode info card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Press and hold the trigger key on your device to scan",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Clear Tags Button
            if (tagList.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        rfidHelper.clearTags()
                        tagList = emptyList()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Clear Tags")
                }
            }

            // Tag List
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = "Scanned Tags:",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(8.dp)
                    )
                    HorizontalDivider()
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(tagList) { tag ->
                            TagItem(tag)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TagItem(tag: HoneywellRfidHelper.TagInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "EPC: ${tag.epc}",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Count: ${tag.count}",
                    style = MaterialTheme.typography.bodySmall
                )
                tag.getRssi()?.let { rssi ->
                    Text(
                        text = "RSSI: $rssi dBm",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}