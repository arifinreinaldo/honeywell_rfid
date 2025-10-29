# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an Android application for interfacing with Honeywell RFID scanners (specifically the IH45 module). The app uses Jetpack Compose for UI and integrates with the Honeywell RFID SDK to scan RFID tags via Bluetooth or serial connection.

## Build Commands

```bash
# Build the project
gradlew.bat build

# Build with full stacktrace (useful for debugging)
gradlew.bat build --stacktrace

# Clean and build
gradlew.bat clean build

# Install on connected device
gradlew.bat installDebug

# Run unit tests
gradlew.bat test

# Run instrumented tests (requires device/emulator)
gradlew.bat connectedAndroidTest
```

## Architecture

### Core Components

**HoneywellRfidHelper (Singleton)**
- Location: `app/src/main/java/com/sample/rfid_honeywell/helper/RfidManager.kt`
- Centralized manager for all RFID operations
- Manages connection lifecycle, tag scanning, and event handling
- Key responsibilities:
  - Device connection (Bluetooth and serial)
  - Reader initialization and configuration
  - Tag scanning in two modes: NORMAL (synchronous) and FAST (asynchronous)
  - Tag read/write/lock operations
  - Event callbacks for connection state and tag reads

**MainActivity**
- Location: `app/src/main/java/com/sample/rfid_honeywell/MainActivity.kt`
- Handles runtime permissions (Bluetooth, Location)
- Manages RFID helper lifecycle
- Hosts the Compose UI

### RFID Scanning Modes

The app supports two scanning paradigms:

1. **Manual Mode**: User-initiated scanning with Start/Stop buttons
2. **Inventory Mode**: Hardware trigger-based scanning (press device trigger to scan)

### Connection States

The RFID system transitions through these states:
- `DISCONNECTED` → `CONNECTED` → `READER_READY`

After reaching `CONNECTED`, the app automatically creates the reader (with 1-second delay) to reach `READER_READY` state.

## Dependencies

### External SDKs

The project requires two Honeywell SDK AAR files (not included in repo):
- `honeywell_rfid_sdk.aar`
- `DataCollection.aar`

These should be placed in `app/libs/` directory. The build.gradle.kts references them as:
```kotlin
implementation(files("libs/honeywell_rfid_sdk.aar"))
implementation(files("libs/DataCollection.aar"))
```

### Key Libraries

- Jetpack Compose (Material3 design)
- Kotlin Coroutines (for async RFID operations)
- AndroidX Core, Lifecycle, Activity

## Required Permissions

The app requires runtime permissions for:
- Bluetooth scanning and connection (Android 12+ uses `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`)
- Location access (`ACCESS_FINE_LOCATION` - required for Bluetooth scanning)
- USB permissions (for USB-connected readers)

These are requested in `MainActivity.checkAndRequestPermissions()`.

## SDK Configuration

Default RFID reader settings in `HoneywellRfidHelper`:
- Session: Session1
- Profile: 4
- Target: 0 (alternates between A and B for multi-inventory)
- Normal read timeout: 200ms
- Target switch interval: 3000ms

## Development Notes

### Working with RFID Operations

When adding new RFID features:
1. Check if reader is available: `rfidHelper.isReaderAvailable()`
2. Ensure connection state is `READER_READY`
3. Use coroutines for long-running operations (scanning happens on IO dispatcher)
4. Tag data is stored in a ConcurrentHashMap for thread-safe access

### Compose UI State Management

The UI uses `remember` and `mutableStateOf` for local state. Connection state and tag lists are updated via callbacks from `HoneywellRfidHelper`.

### Testing on Physical Devices

This app requires actual Honeywell RFID hardware. Common test scenarios:
- Bluetooth connection with known MAC address (default: `0C:23:69:19:AB:FB`)
- Serial connection for built-in RFID modules
- Trigger key functionality (inventory mode)

## Package Structure

```
com.sample.rfid_honeywell/
├── MainActivity.kt           # Main activity and Compose UI
├── helper/
│   └── RfidManager.kt       # HoneywellRfidHelper singleton
└── ui/theme/                # Compose theme files
    ├── Color.kt
    ├── Theme.kt
    └── Type.kt
```

## Common Operations

### Connect to Device
```kotlin
// Bluetooth
rfidHelper.connect("0C:23:69:19:AB:FB")

// Serial (built-in)
rfidHelper.connectSerial()
```

### Scan Tags
```kotlin
// Start scanning
rfidHelper.startScan(mode = ScanMode.NORMAL) { tags ->
    // Handle tag list
}

// Stop scanning
rfidHelper.stopScan()
```

### Enable Inventory Mode
```kotlin
rfidHelper.setInventoryMode(true) { tags ->
    // Called when trigger is pressed
}
```