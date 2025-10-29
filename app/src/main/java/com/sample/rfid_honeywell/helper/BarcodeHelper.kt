package com.sample.rfid_honeywell.helper

import android.content.Context
import android.util.Log
import com.honeywell.aidc.*
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Barcode Scanner Manager for Honeywell devices (Singleton)
 *
 * This class handles barcode scanning using the Honeywell AIDC library:
 * - Scanner initialization and connection
 * - Hardware trigger support
 * - Software trigger control
 * - Barcode read events
 *
 * Usage Example:
 * ```
 * val barcodeHelper = BarcodeHelper.getInstance(context)
 *
 * lifecycleScope.launch {
 *     val success = barcodeHelper.initialize()
 *     if (success) {
 *         // Listen for barcode scans
 *         barcodeHelper.setBarcodeListener { barcode ->
 *             Log.d("Barcode", "Scanned: ${barcode.data}")
 *         }
 *     }
 * }
 *
 * // Enable trigger mode
 * barcodeHelper.setTriggerMode(true)
 *
 * // Cleanup
 * barcodeHelper.cleanup()
 * ```
 */
class BarcodeHelper private constructor(context: Context) {

    private val context: Context = context.applicationContext

    companion object {
        private const val TAG = "BarcodeHelper"

        @Volatile
        private var INSTANCE: BarcodeHelper? = null

        /**
         * Get the singleton instance of BarcodeHelper
         */
        fun getInstance(context: Context): BarcodeHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BarcodeHelper(context).also {
                    INSTANCE = it
                }
            }
        }
    }

    // AIDC Manager and Reader
    private var aidcManager: AidcManager? = null
    private var barcodeReader: BarcodeReader? = null

    // Coroutines
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // State tracking
    private var isInitialized = false
    private var isClaimed = false
    private var triggerModeEnabled = false

    // Callbacks
    private var onBarcodeScanned: ((BarcodeData) -> Unit)? = null
    private var onScannerStateChanged: ((ScannerState) -> Unit)? = null
    private var onTriggerStateChanged: ((Boolean) -> Unit)? = null

    // Barcode list
    private val barcodeList = mutableListOf<BarcodeData>()

    // ========================================
    // INITIALIZATION
    // ========================================

    /**
     * Initialize the barcode scanner (suspend function)
     */
    suspend fun initialize(): Boolean = suspendCoroutine { continuation ->
        try {
            AidcManager.create(context, object : AidcManager.CreatedCallback {
                override fun onCreated(manager: AidcManager?) {
                    aidcManager = manager

                    if (manager != null) {
                        try {
                            // Create default barcode reader
                            barcodeReader = manager.createBarcodeReader()

                            // Claim the scanner
                            barcodeReader?.claim()
                            isClaimed = true

                            // Set up listeners
                            setupListeners()

                            // Configure default properties
                            configureDefaultProperties()

                            isInitialized = true
                            updateState(ScannerState.READY)

                            Log.d(TAG, "Barcode scanner initialized successfully")
                            continuation.resume(true)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to create barcode reader", e)
                            continuation.resume(false)
                        }
                    } else {
                        Log.e(TAG, "AIDC Manager is null")
                        continuation.resume(false)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize barcode scanner", e)
            continuation.resume(false)
        }
    }

    /**
     * Set up barcode and trigger listeners
     */
    private fun setupListeners() {
        barcodeReader?.addBarcodeListener(object : BarcodeReader.BarcodeListener {
            override fun onBarcodeEvent(event: BarcodeReadEvent) {
                val barcodeData = BarcodeData(
                    data = event.barcodeData,
                    codeId = event.codeId,
                    aimId = event.aimId,
                    timestamp = event.timestamp,
                    count = 1
                )

                // Add to list or update count
                val existing = barcodeList.find { it.data == barcodeData.data }
                if (existing != null) {
                    existing.count++
                } else {
                    barcodeList.add(barcodeData)
                }

                scope.launch {
                    onBarcodeScanned?.invoke(barcodeData)
                }

                Log.d(TAG, "Barcode scanned: ${barcodeData.data} (${barcodeData.codeId})")
            }

            override fun onFailureEvent(event: BarcodeFailureEvent) {
                Log.w(TAG, "Barcode scan failed")
            }
        })

        barcodeReader?.addTriggerListener(object : BarcodeReader.TriggerListener {
            override fun onTriggerEvent(event: TriggerStateChangeEvent) {
                val pressed = event.state
                scope.launch {
                    onTriggerStateChanged?.invoke(pressed)
                }
                Log.d(TAG, "Trigger ${if (pressed) "pressed" else "released"}")
            }
        })
    }

    /**
     * Configure default scanner properties
     */
    private fun configureDefaultProperties() {
        try {
            barcodeReader?.apply {
                // Enable trigger control
                setProperty(BarcodeReader.PROPERTY_TRIGGER_CONTROL_MODE,
                    BarcodeReader.TRIGGER_CONTROL_MODE_AUTO_CONTROL)

                // Set trigger scan mode to continuous
                setProperty(BarcodeReader.PROPERTY_TRIGGER_SCAN_MODE,
                    BarcodeReader.TRIGGER_SCAN_MODE_ONESHOT)

                // Enable good read notification
                setProperty(BarcodeReader.PROPERTY_NOTIFICATION_GOOD_READ_ENABLED, true)
                setProperty(BarcodeReader.PROPERTY_NOTIFICATION_BAD_READ_ENABLED, true)

                // Enable common barcode types
                setProperty(BarcodeReader.PROPERTY_CODE_128_ENABLED, true)
                setProperty(BarcodeReader.PROPERTY_CODE_39_ENABLED, true)
                setProperty(BarcodeReader.PROPERTY_EAN_13_ENABLED, true)
                setProperty(BarcodeReader.PROPERTY_EAN_8_ENABLED, true)
                setProperty(BarcodeReader.PROPERTY_UPC_A_ENABLE, true)
                setProperty(BarcodeReader.PROPERTY_UPC_E_ENABLED, true)
                setProperty(BarcodeReader.PROPERTY_QR_CODE_ENABLED, true)
                setProperty(BarcodeReader.PROPERTY_DATAMATRIX_ENABLED, true)
                setProperty(BarcodeReader.PROPERTY_PDF_417_ENABLED, true)

                Log.d(TAG, "Default properties configured")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure properties", e)
        }
    }

    // ========================================
    // TRIGGER CONTROL
    // ========================================

    /**
     * Enable/disable trigger mode for barcode scanning
     */
    fun setTriggerMode(enabled: Boolean) {
        triggerModeEnabled = enabled

        if (enabled && isClaimed) {
            updateState(ScannerState.SCANNING)
            Log.d(TAG, "Trigger mode enabled - Press trigger to scan")
        } else {
            updateState(ScannerState.READY)
            Log.d(TAG, "Trigger mode disabled")
        }
    }

    /**
     * Trigger a software scan
     */
    fun softwareTrigger(pressed: Boolean) {
        if (!isClaimed) {
            Log.w(TAG, "Scanner not claimed")
            return
        }

        try {
            barcodeReader?.softwareTrigger(pressed)
            Log.d(TAG, "Software trigger: $pressed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger software scan", e)
        }
    }

    // ========================================
    // SCANNER CONTROL
    // ========================================

    /**
     * Check if scanner is ready
     */
    fun isReady(): Boolean {
        return isInitialized && isClaimed
    }

    /**
     * Get available barcode devices
     */
    fun listDevices(): List<String> {
        return try {
            aidcManager?.listBarcodeDevices()?.map { it.name } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list devices", e)
            emptyList()
        }
    }

    // ========================================
    // DATA MANAGEMENT
    // ========================================

    /**
     * Get all scanned barcodes
     */
    fun getAllBarcodes(): List<BarcodeData> {
        return barcodeList.toList()
    }

    /**
     * Clear barcode list
     */
    fun clearBarcodes() {
        barcodeList.clear()
        Log.d(TAG, "Barcode list cleared")
    }

    /**
     * Get barcode count
     */
    fun getBarcodeCount(): Int {
        return barcodeList.size
    }

    // ========================================
    // CALLBACKS
    // ========================================

    /**
     * Set barcode scan listener
     */
    fun setBarcodeListener(listener: (BarcodeData) -> Unit) {
        onBarcodeScanned = listener
    }

    /**
     * Set scanner state listener
     */
    fun setScannerStateListener(listener: (ScannerState) -> Unit) {
        onScannerStateChanged = listener
    }

    /**
     * Set trigger state listener
     */
    fun setTriggerListener(listener: (Boolean) -> Unit) {
        onTriggerStateChanged = listener
    }

    /**
     * Update scanner state
     */
    private fun updateState(newState: ScannerState) {
        scope.launch {
            onScannerStateChanged?.invoke(newState)
        }
    }

    // ========================================
    // CLEANUP
    // ========================================

    /**
     * Cleanup resources
     */
    fun cleanup() {
        try {
            triggerModeEnabled = false

            barcodeReader?.release()
            barcodeReader?.close()
            barcodeReader = null

            aidcManager?.close()
            aidcManager = null

            isClaimed = false
            isInitialized = false

            scope.cancel()

            Log.d(TAG, "Barcode scanner cleanup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    // ========================================
    // DATA CLASSES
    // ========================================

    /**
     * Barcode scan data
     */
    data class BarcodeData(
        val data: String,
        val codeId: String,
        val aimId: String,
        val timestamp: String,
        var count: Int = 1
    ) {
        fun getSymbologyName(): String {
            return when {
                codeId.startsWith("d") -> "Data Matrix"
                codeId.startsWith("e") -> "EAN-13"
                codeId.startsWith("E") -> "EAN-8"
                codeId.startsWith("j") -> "Code 128"
                codeId.startsWith("b") -> "Code 39"
                codeId.startsWith("s") -> "QR Code"
                codeId.startsWith("r") -> "PDF417"
                codeId.startsWith("A") -> "UPC-A"
                codeId.startsWith("E") -> "UPC-E"
                else -> "Unknown ($codeId)"
            }
        }
    }

    /**
     * Scanner state enumeration
     */
    enum class ScannerState {
        DISCONNECTED,
        READY,
        SCANNING
    }
}
