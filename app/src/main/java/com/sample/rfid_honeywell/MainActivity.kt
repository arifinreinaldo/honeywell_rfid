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
import com.honeywell.rfidservice.TriggerMode
import com.sample.rfid_honeywell.helper.BarcodeHelper
import com.sample.rfid_honeywell.helper.HoneywellRfidHelper
import com.sample.rfid_honeywell.ui.theme.RFIDHoneywellTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var rfidHelper: HoneywellRfidHelper
    private lateinit var barcodeHelper: BarcodeHelper
    private var isRfidInitialized = false
    private var isBarcodeInitialized = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            initializeDevices()
        } else {
            Log.e("MainActivity", "Permissions denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        rfidHelper = HoneywellRfidHelper.getInstance(this)
        barcodeHelper = BarcodeHelper.getInstance(this)

        setContent {
            RFIDHoneywellTheme {
                MainScreen(
                    rfidHelper = rfidHelper,
                    barcodeHelper = barcodeHelper
                )
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
            initializeDevices()
        }
    }

    private fun initializeDevices() {
        lifecycleScope.launch {
            // Initialize RFID
            if (!isRfidInitialized) {
                val rfidSuccess = rfidHelper.initialize()
                isRfidInitialized = rfidSuccess
                Log.d("MainActivity", "RFID initialized: $rfidSuccess")
            }

            // Initialize Barcode Scanner
            if (!isBarcodeInitialized) {
                val barcodeSuccess = barcodeHelper.initialize()
                isBarcodeInitialized = barcodeSuccess
                Log.d("MainActivity", "Barcode scanner initialized: $barcodeSuccess")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        rfidHelper.cleanup()
        barcodeHelper.cleanup()
    }
}

@Composable
fun MainScreen(rfidHelper: HoneywellRfidHelper, barcodeHelper: BarcodeHelper) {
    var scanMode by remember { mutableStateOf(ScanMode.RFID) }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Title
            Text(
                text = "Honeywell Scanner",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Mode Selector
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = { scanMode = ScanMode.RFID },
                        modifier = Modifier
                            .weight(1f)
                            .padding(4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (scanMode == ScanMode.RFID)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Text("RFID Mode")
                    }

                    Button(
                        onClick = { scanMode = ScanMode.BARCODE },
                        modifier = Modifier
                            .weight(1f)
                            .padding(4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (scanMode == ScanMode.BARCODE)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Text("Barcode Mode")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Display appropriate screen based on mode
            when (scanMode) {
                ScanMode.RFID -> RfidScreen(rfidHelper = rfidHelper)
                ScanMode.BARCODE -> BarcodeScreen(barcodeHelper = barcodeHelper)
            }
        }
    }
}

enum class ScanMode {
    RFID,
    BARCODE
}

@Composable
fun RfidScreen(rfidHelper: HoneywellRfidHelper) {
    var triggerMode by remember { mutableStateOf(TriggerMode.RFID) }
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

    Column(
        modifier = Modifier
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

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
                        text = "Trigger Mode",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (triggerMode == TriggerMode.RFID)
                            "RFID"
                        else
                            "BARCODE",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = triggerMode == TriggerMode.RFID,
                    onCheckedChange = {
                        var newState =
                            if (triggerMode == TriggerMode.RFID) TriggerMode.BARCODE else TriggerMode.RFID
                        rfidHelper.setTriggerMode(newState)
                        triggerMode = newState
                    },
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

@Composable
fun BarcodeScreen(barcodeHelper: BarcodeHelper) {
    var scannerState by remember { mutableStateOf(BarcodeHelper.ScannerState.DISCONNECTED) }
    var barcodeList by remember { mutableStateOf<List<BarcodeHelper.BarcodeData>>(emptyList()) }
    var statusMessage by remember { mutableStateOf("Ready") }
    var triggerModeEnabled by remember { mutableStateOf(false) }

    // Set up scanner state listener
    LaunchedEffect(Unit) {
        barcodeHelper.setScannerStateListener { state ->
            scannerState = state
            statusMessage = when (state) {
                BarcodeHelper.ScannerState.DISCONNECTED -> "Scanner not ready"
                BarcodeHelper.ScannerState.READY -> "Scanner ready"
                BarcodeHelper.ScannerState.SCANNING -> "Ready to scan - Press trigger"
            }
        }

        // Set up barcode scan listener
        barcodeHelper.setBarcodeListener { barcode ->
            barcodeList = barcodeHelper.getAllBarcodes()
        }
    }

    // Handle trigger mode toggle
    LaunchedEffect(triggerModeEnabled) {
        barcodeHelper.setTriggerMode(triggerModeEnabled)
    }

    Column(
        modifier = Modifier
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Status
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when (scannerState) {
                    BarcodeHelper.ScannerState.SCANNING -> MaterialTheme.colorScheme.primaryContainer
                    BarcodeHelper.ScannerState.READY -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Status: $statusMessage")
                Text("Scanner: ${scannerState.name}")
                Text("Mode: ${if (triggerModeEnabled) "Trigger Enabled" else "Trigger Disabled"}")
                Text("Barcodes scanned: ${barcodeList.size}")
            }
        }

        // Trigger Mode Toggle
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (triggerModeEnabled)
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
                        text = "Hardware Trigger",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (triggerModeEnabled)
                            "Press trigger key to scan"
                        else
                            "Enable to use hardware trigger",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = triggerModeEnabled,
                    onCheckedChange = {
                        triggerModeEnabled = it
                        if (it) {
                            statusMessage = "Press trigger to scan barcodes"
                        } else {
                            statusMessage = "Trigger disabled"
                        }
                    },
                    enabled = scannerState != BarcodeHelper.ScannerState.DISCONNECTED
                )
            }
        }

        // Software Trigger Buttons (when trigger mode is disabled)
        if (!triggerModeEnabled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { barcodeHelper.softwareTrigger(true) },
                    modifier = Modifier.weight(1f),
                    enabled = scannerState == BarcodeHelper.ScannerState.READY
                ) {
                    Text("Start Scan")
                }

                Button(
                    onClick = { barcodeHelper.softwareTrigger(false) },
                    modifier = Modifier.weight(1f),
                    enabled = scannerState == BarcodeHelper.ScannerState.READY,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("Stop Scan")
                }
            }
        } else {
            // Info card for trigger mode
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
                        text = "Press and hold the trigger key on your device to scan barcodes",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Clear Barcodes Button
        if (barcodeList.isNotEmpty()) {
            OutlinedButton(
                onClick = {
                    barcodeHelper.clearBarcodes()
                    barcodeList = emptyList()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Clear Barcodes")
            }
        }

        // Barcode List
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = "Scanned Barcodes:",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(8.dp)
                )
                HorizontalDivider()
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(barcodeList) { barcode ->
                        BarcodeItem(barcode)
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

@Composable
fun BarcodeItem(barcode: BarcodeHelper.BarcodeData) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Data: ${barcode.data}",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Type: ${barcode.getSymbologyName()}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Count: ${barcode.count}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                text = "Timestamp: ${barcode.timestamp}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}