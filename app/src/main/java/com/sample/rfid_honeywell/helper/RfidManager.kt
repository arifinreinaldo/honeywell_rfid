package com.sample.rfid_honeywell.helper

import android.content.Context
import android.util.Log
import com.honeywell.rfidservice.EventListener
import com.honeywell.rfidservice.RfidManager
import com.honeywell.rfidservice.TriggerMode
import com.honeywell.rfidservice.rfid.*
import com.honeywell.rfidservice.utils.ByteUtils
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Consolidated RFID Manager for Honeywell RFID IH45 Module (Singleton)
 *
 * This class encapsulates all RFID functionality including:
 * - Device scanning and connection management
 * - Reader creation and configuration
 * - Tag scanning (Normal and Fast modes)
 * - Tag reading, writing, and locking
 * - Event handling and callbacks
 *
 * Usage Example:
 * ```
 * // Get the singleton instance
 * val rfidHelper = HoneywellRfidHelper.getInstance(context)
 *
 * // Launch coroutine
 * lifecycleScope.launch {
 *     // Initialize
 *     val success = rfidHelper.initialize()
 *     if (success) {
 *         // Connect to device with known address
 *         rfidHelper.connect("0C:23:69:19:96:46")
 *     }
 * }
 *
 * // Listen for connection state
 * rfidHelper.setConnectionStateListener { state ->
 *     when (state) {
 *         READER_READY -> {
 *             // Start scanning tags
 *             rfidHelper.startScan { tags ->
 *                 tags.forEach { tag ->
 *                     Log.d("RFID", "Found tag: ${tag.epc}")
 *                 }
 *             }
 *         }
 *     }
 * }
 *
 * // Stop scanning
 * rfidHelper.stopScan()
 *
 * // Cleanup
 * rfidHelper.cleanup()
 * ```
 */
class HoneywellRfidHelper private constructor(context: Context) {

    private val context: Context = context.applicationContext

    companion object {
        private const val TAG = "HoneywellRfidHelper"
        private const val NORMAL_READ_TIMEOUT_MS = 200
        private const val TARGET_SWITCH_INTERVAL_MS = 3000L

        @Volatile
        private var INSTANCE: HoneywellRfidHelper? = null

        /**
         * Get the singleton instance of HoneywellRfidHelper
         *
         * @param context Application or Activity context
         * @return Singleton instance
         */
        fun getInstance(context: Context): HoneywellRfidHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: HoneywellRfidHelper(context).also {
                    INSTANCE = it
                }
            }
        }
    }

    // RFID Manager and Reader
    private var rfidManager: RfidManager? = null
    private var rfidReader: RfidReader? = null

    // Scanning state
    private var isReading = false
    private var scanMode = ScanMode.NORMAL

    // Coroutines
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var scanJob: Job? = null

    // Tag data storage
    private val tagDataMap = ConcurrentHashMap<String, TagInfo>()

    // Callbacks
    private var onTagReadCallback: ((List<TagInfo>) -> Unit)? = null
    private var onConnectionStateChanged: ((ConnectionState) -> Unit)? = null
    private var onTriggerScanCallback: ((List<TagInfo>) -> Unit)? = null

    // Configuration
    private var targetSwitchInterval = TARGET_SWITCH_INTERVAL_MS
    private var currentTarget = 0 // 0 = A, 1 = B
    private var showAdditionData = false
    private var connectedDeviceAddress: String? = null

    // Connection state tracking
    private var currentConnectionState: ConnectionState = ConnectionState.DISCONNECTED

    // Inventory mode (trigger-based scanning)
    private var inventoryModeEnabled = false

    // ========================================
    // INITIALIZATION
    // ========================================

    /**
     * Initialize the RFID Manager (suspend function)
     */
    suspend fun initialize(): Boolean = suspendCancellableCoroutine { continuation ->
        RfidManager.create(context, object : RfidManager.CreatedCallback {
            override fun onCreated(manager: RfidManager?) {
                rfidManager = manager
                manager?.addEventListener(eventListener)

                Log.d(TAG, "RFID Manager initialized: ${manager != null}")
                continuation.resume(manager != null) {}
            }
        })
    }


    // ========================================
    // CONNECTION MANAGEMENT
    // ========================================

    /**
     * Connect to RFID device by Bluetooth address
     */
    fun connect(bluetoothAddress: String?): Boolean {
        if (rfidManager == null) {
            Log.e(TAG, "RFID Manager not initialized")
            return false
        }

        if (isConnected()) {
            Log.w(TAG, "Already connected to a device")
            return false
        }

        Log.d(TAG, "Connecting to device: $bluetoothAddress")
        connectedDeviceAddress = bluetoothAddress
        return rfidManager?.connect(bluetoothAddress) ?: false
    }

    /**
     * Connect to serial RFID device
     */
    fun connectSerial(): Boolean {
        if (rfidManager == null) {
            Log.e(TAG, "RFID Manager not initialized")
            return false
        }

        Log.d(TAG, "Connecting to serial device")
        return rfidManager?.connect(null) ?: false
    }

    /**
     * Disconnect from current RFID device
     */
    fun disconnect() {
        stopScan()
        rfidManager?.disconnect()
        rfidReader = null
        connectedDeviceAddress = null
        Log.d(TAG, "Disconnected from device")
    }

    /**
     * Check if device is connected
     */
    fun isConnected(): Boolean {
        return rfidManager?.isConnected ?: false
    }

    /**
     * Check if reader is available for operations
     */
    fun isReaderAvailable(): Boolean {
        return rfidReader?.available() ?: false
    }

    // ========================================
    // READER CREATION & CONFIGURATION
    // ========================================

    /**
     * Create RFID reader after connection
     */
    fun createReader(delayMs: Long = 1000) {
        scope.launch {
            delay(delayMs)
            withContext(Dispatchers.IO) {
                rfidManager?.createReader()
                Log.d(TAG, "Creating RFID reader...")
            }
        }
    }

    /**
     * Configure reader work mode
     */
    fun setWorkMode(
        session: Gen2.Session = Gen2.Session.Session1,
        profile: Int = 4,
        target: Int = 0,
        qValue: Int = -1
    ) {
        rfidReader?.setWorkMode(session, profile, target, qValue)
        Log.d(TAG, "Work mode set: session=$session, profile=$profile, target=$target, q=$qValue")
    }

    /**
     * Set RFID region
     */
    fun setRegion(region: Region) {
        try {
            rfidReader?.setRegion(region)
            Log.d(TAG, "Region set to: $region")
        } catch (e: RfidReaderException) {
            Log.e(TAG, "Failed to set region", e)
        }
    }

    /**
     * Set antenna power
     */
    fun setAntennaPower(readPower: Int, writePower: Int, antennaId: Int = 1) {
        try {
            val antennaPower = AntennaPower(antennaId, readPower, writePower)
            rfidReader?.antennaPower = arrayOf(antennaPower)
            Log.d(TAG, "Antenna power set: read=$readPower, write=$writePower")
        } catch (e: RfidReaderException) {
            Log.e(TAG, "Failed to set antenna power", e)
        }
    }

    /**
     * Set target for tag selection
     */
    fun setTarget(target: Int) {
        rfidReader?.setTarget(target)
        currentTarget = target
        Log.d(TAG, "Target set to: ${if (target == 0) "A" else "B"}")
    }

    fun setTriggerMode(mode: TriggerMode) {
        rfidManager?.triggerMode = mode
    }

    fun getTriggerMode(): TriggerMode? {
        return rfidManager?.triggerMode
    }
    // ========================================
    // TAG SCANNING/READING
    // ========================================

    /**
     * Start scanning/reading RFID tags
     *
     * @param mode Scan mode (NORMAL or FAST)
     * @param additionData Additional tag data to read
     * @param callback Callback invoked when tags are read
     */
    fun startScan(
        mode: ScanMode = ScanMode.NORMAL,
        additionData: TagAdditionData = TagAdditionData.NONE,
        callback: (List<TagInfo>) -> Unit
    ): String {
        if (!isReaderAvailable()) {
            return "Reader not available"
        }

        if (isReading) {
            return "Already scanning"
        }

        scanMode = mode
        onTagReadCallback = callback
        tagDataMap.clear()
        isReading = true

        Log.d(TAG, "Starting scan in $mode mode")

        when (mode) {
            ScanMode.NORMAL -> startNormalScan(additionData)
            ScanMode.FAST -> startFastScan(additionData)
        }
        return ""
    }

    /**
     * Normal (synchronous) scan mode using coroutines
     */
    private fun startNormalScan(additionData: TagAdditionData) {
        scanJob = scope.launch(Dispatchers.IO) {
            var lastReadTime = System.currentTimeMillis()

            while (isActive && isReading) {
                try {
                    val tags = rfidReader?.syncRead(additionData, NORMAL_READ_TIMEOUT_MS)

                    if (tags != null && tags.isNotEmpty()) {
                        processTagData(tags)
                        lastReadTime = System.currentTimeMillis()
                    } else {
                        // Handle target switching for multi-inventory mode
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastReadTime > targetSwitchInterval) {
                            switchTarget()
                            lastReadTime = currentTime
                        }
                    }
                } catch (e: CancellationException) {
                    Log.d(TAG, "Normal scan cancelled")
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error during normal scan", e)
                }
            }

            Log.d(TAG, "Normal scan completed")
        }
    }

    /**
     * Fast (asynchronous) scan mode
     */
    private fun startFastScan(additionData: TagAdditionData) {
        rfidReader?.setOnTagReadListener(tagReadListener)
        rfidReader?.read(additionData)
        Log.d(TAG, "Fast scan started")
    }

    /**
     * Stop scanning/reading tags
     */
    fun stopScan() {
        if (!isReading) return

        isReading = false

        when (scanMode) {
            ScanMode.NORMAL -> stopNormalScan()
            ScanMode.FAST -> stopFastScan()
        }

        Log.d(TAG, "Scan stopped. Total tags: ${tagDataMap.size}")
    }

    private fun stopNormalScan() {
        scanJob?.cancel()
        scanJob = null
    }

    private fun stopFastScan() {
        rfidReader?.stopRead()
        rfidReader?.removeOnTagReadListener(tagReadListener)
    }

    /**
     * Switch target between A and B for multi-inventory
     */
    private fun switchTarget() {
        currentTarget = if (currentTarget == 0) 1 else 0
        rfidReader?.setTarget(currentTarget)
        Log.d(TAG, "Switched target to: ${if (currentTarget == 0) "A" else "B"}")
    }

    // ========================================
    // TAG DATA PROCESSING
    // ========================================

    private val tagReadListener = object : OnTagReadListener {
        override fun onTagRead(tags: Array<TagReadData>) {
            processTagData(tags)
        }
    }

    private fun processTagData(tags: Array<TagReadData>) {
        synchronized(tagDataMap) {
            for (tag in tags) {
                val epc = tag.epcHexStr
                val additionHex = if (showAdditionData) {
                    ByteUtils.bytes2HexStr(tag.additionData)
                } else ""

                val key = epc + additionHex

                val tagInfo = tagDataMap.getOrPut(key) {
                    TagInfo(
                        epc = epc,
                        additionData = additionHex,
                        rawData = tag,
                        count = 0,
                        firstSeenTime = System.currentTimeMillis()
                    )
                }
                tagInfo.count++
                tagInfo.lastSeenTime = System.currentTimeMillis()
            }

            // Notify callback on main thread using coroutine
            scope.launch(Dispatchers.Main) {
                onTagReadCallback?.invoke(tagDataMap.values.toList())
            }
        }
    }

    // ========================================
    // TAG READ/WRITE/LOCK OPERATIONS
    // ========================================

    /**
     * Read tag data from specific memory bank
     */
    fun readTagData(
        epc: String,
        bank: Int,
        startAddress: Int,
        blockCount: Int,
        password: String = "00000000"
    ): String? {
        return try {
            rfidReader?.readTagData(epc, bank, startAddress, blockCount, password)
        } catch (e: RfidReaderException) {
            Log.e(TAG, "Error reading tag data", e)
            null
        }
    }

    /**
     * Write data to tag
     */
    fun writeTagData(
        epc: String,
        bank: Int,
        startAddress: Int,
        data: String,
        password: String = "00000000"
    ): Boolean {
        return try {
            rfidReader?.writeTagData(epc, bank, startAddress, password, data)
            true
        } catch (e: RfidReaderException) {
            Log.e(TAG, "Error writing tag data", e)
            false
        }
    }

    /**
     * Lock tag memory
     */
    fun lockTag(
        epc: String,
        lockBank: Gen2.LockBank,
        lockType: Gen2.LockType,
        password: String = "00000000"
    ): Boolean {
        return try {
            rfidReader?.lockTag(epc, lockBank, lockType, password)
            true
        } catch (e: RfidReaderException) {
            Log.e(TAG, "Error locking tag", e)
            false
        }
    }

    // ========================================
    // EVENT HANDLING
    // ========================================

    private val eventListener = object : EventListener {
        override fun onDeviceConnected(data: Any?) {
            connectedDeviceAddress = data as? String
            updateConnectionState(ConnectionState.CONNECTED)
            scope.launch(Dispatchers.Main) {
                onConnectionStateChanged?.invoke(ConnectionState.CONNECTED)
                Log.d(TAG, "Device connected: $data")
            }
        }

        override fun onDeviceDisconnected(data: Any?) {
            rfidReader = null
            updateConnectionState(ConnectionState.DISCONNECTED)
            scope.launch(Dispatchers.Main) {
                onConnectionStateChanged?.invoke(ConnectionState.DISCONNECTED)
                Log.d(TAG, "Device disconnected")
            }
        }

        override fun onReaderCreated(success: Boolean, reader: RfidReader?) {
            if (success && reader != null) {
                rfidReader = reader

                // Apply default configuration
                setWorkMode()

                updateConnectionState(ConnectionState.READER_READY)
                scope.launch(Dispatchers.Main) {
                    onConnectionStateChanged?.invoke(ConnectionState.READER_READY)
                    Log.d(TAG, "Reader created successfully")
                }
            } else {
                Log.e(TAG, "Failed to create reader")
            }
        }

        override fun onRfidTriggered(triggered: Boolean) {
            // Handle hardware trigger button
            if (!inventoryModeEnabled) return

            if (triggered) {
                if (!isReading) {
                    Log.d(TAG, "Trigger pressed - starting scan")
                    startScan(
                        mode = ScanMode.NORMAL,
                        callback = { tags ->
                            onTriggerScanCallback?.invoke(tags)
                        }
                    )
                }
            } else {
                Log.d(TAG, "Trigger released - stopping scan")
                stopScan()
            }
        }

        override fun onTriggerModeSwitched(mode: TriggerMode?) {
            Log.d(TAG, "Trigger mode switched: $mode")
        }

        override fun onReceivedFindingTag(data: Int) {
            // Handle tag finding event
        }

        override fun onUsbDeviceAttached(data: Any?) {
            Log.d(TAG, "USB device attached")
        }

        override fun onUsbDeviceDetached(data: Any?) {
            Log.d(TAG, "USB device detached")
        }
    }

    /**
     * Set connection state change listener
     */
    fun setConnectionStateListener(listener: (ConnectionState) -> Unit) {
        onConnectionStateChanged = listener
    }

    /**
     * Enable/disable inventory mode (trigger-based scanning)
     *
     * @param enabled True to enable trigger scanning, false to disable
     * @param callback Callback invoked when tags are read via trigger
     */
    fun setInventoryMode(enabled: Boolean, callback: ((List<TagInfo>) -> Unit)? = null) {
        inventoryModeEnabled = enabled
        onTriggerScanCallback = callback
        Log.d(TAG, "Inventory mode ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Check if inventory mode is enabled
     */
    fun isInventoryModeEnabled(): Boolean {
        return inventoryModeEnabled
    }

    /**
     * Update connection state internally
     */
    private fun updateConnectionState(newState: ConnectionState) {
        currentConnectionState = newState
    }

    /**
     * Get current connection state
     *
     * @return Current ConnectionState (DISCONNECTED, CONNECTED, or READER_READY)
     */
    fun getConnectionState(): ConnectionState {
        return currentConnectionState
    }

    // ========================================
    // UTILITY METHODS
    // ========================================

    /**
     * Get all scanned tags
     */
    fun getAllTags(): List<TagInfo> {
        return tagDataMap.values.toList()
    }

    /**
     * Clear tag data
     */
    fun clearTags() {
        tagDataMap.clear()
        Log.d(TAG, "Tag data cleared")
    }

    /**
     * Get tag count
     */
    fun getTagCount(): Int {
        return tagDataMap.size
    }

    /**
     * Set target switch interval for multi-inventory mode
     */
    fun setTargetSwitchInterval(intervalMs: Long) {
        targetSwitchInterval = intervalMs
    }

    /**
     * Enable/disable showing addition data in tag keys
     */
    fun setShowAdditionData(show: Boolean) {
        showAdditionData = show
    }

    /**
     * Get connected device address
     */
    fun getConnectedDeviceAddress(): String? {
        return connectedDeviceAddress
    }

    // ========================================
    // CLEANUP
    // ========================================

    /**
     * Cleanup resources
     */
    fun cleanup() {
        stopScan()
        disconnect()
        rfidManager?.removeEventListener(eventListener)
        scope.cancel()
        Log.d(TAG, "Cleanup completed")
    }

    // ========================================
    // DATA CLASSES
    // ========================================

    /**
     * Tag information with metadata
     */
    data class TagInfo(
        val epc: String,
        val additionData: String = "",
        val rawData: TagReadData,
        var count: Int = 0,
        val firstSeenTime: Long = System.currentTimeMillis(),
        var lastSeenTime: Long = System.currentTimeMillis()
    ) {
        val key: String get() = epc + additionData

        fun getRssi(): Int? = rawData.rssi
        fun getFrequency(): Int? = rawData.frequency
    }

    /**
     * Scan mode enumeration
     */
    enum class ScanMode {
        NORMAL,  // Synchronous scanning
        FAST     // Asynchronous scanning
    }

    /**
     * Connection state enumeration
     */
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTED,
        READER_READY
    }
}