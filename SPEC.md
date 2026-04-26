# TeslaBleLab - Specification Document

## 1. Project Overview

**Project Name:** TeslaBleLab
**Type:** Android Native Application (Kotlin)

**Core Functionality:** A minimal Tesla BLE data reading and verification app that connects to Tesla vehicles via Bluetooth Low Energy to read real-time vehicle status data (speed, power, shift state, odometer, battery level).

## 2. Technology Stack & Choices

### Framework & Language
- **Language:** Kotlin 1.9.x
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 34 (Android 14)
- **Compile SDK:** 34

### Key Libraries/Dependencies
- **Android BLE Framework:** Native Android Bluetooth LE API (android.bluetooth)
- **Coroutines:** kotlinx-coroutines-android for async operations
- **Lifecycle:** AndroidX Lifecycle ViewModel & LiveData
- **DataStore:** For persisting private keys and pairing state
- **Protobuf:** com.google.protobuf for Tesla BLE protocol messages
- **Security:** Android Keystore + AES-GCM for key storage
- **Logging:** timber for detailed logging

### State Management
- MVVM pattern with StateFlow/LiveData
- Repository pattern for data layer

### Architecture Pattern
- Clean Architecture with 3 layers:
  - **Presentation Layer:** Activities, ViewModels, UI components
  - **Domain Layer:** Use cases, business logic interfaces
  - **Data Layer:** BLE repository, local storage, protocol implementation

## 3. Feature List

### Core Features
1. **VIN Input** - Manual VIN entry for target vehicle
2. **BLE Scanning** - Scan for Tesla BLE service (Service UUID: 0000fff0-0000-1000-8000-00805f9b34fb)
3. **BLE Connection** - Connect/disconnect to vehicle
4. **Service Discovery** - Discover services and characteristics post-connection
5. **MTU Negotiation** - Request MTU 512 for larger payloads
6. **Pairing Flow** - Execute Tesla BLE pairing protocol with ECDH key exchange
7. **Key Persistence** - Save private key and pairing state to encrypted storage
8. **Session Authentication** - Establish encrypted authentication session
9. **Vehicle State Polling** - Loop read vehicle state using notification subscriptions
10. **Real-time Display** - Show speed_float, power, shift_state, odometer, battery_level
11. **Status Dashboard** - Display connection status, error logs, last update time, refresh interval
12. **Reconnection** - Support reconnecting to previously paired vehicle
13. **Pairing Clear** - Option to clear saved pairing data

### Debug Features
- Detailed BLE operation logs
- Protocol message logs (hex dump)
- Encryption/decryption logs
- Protobuf parsing logs
- Error tracking with timestamps

## 4. UI/UX Design Direction

### Overall Visual Style
- **Material Design 3** with minimal, functional aesthetic
- Dark theme primary (suitable for garage/workshop use)
- High contrast text for readability
- Debug console style layout

### Color Scheme
- Primary: Electric Blue (#2196F3) - Tesla brand association
- Secondary: Dark Gray (#212121) - background
- Surface: (#303030) - cards/panels
- Error: Red (#F44336)
- Success: Green (#4CAF50)
- Text: White/Light Gray

### Layout Approach
- **Single Activity** with scrollable content
- Sections:
  1. Header: Connection status indicator
  2. VIN Input Section
  3. BLE Control Buttons (Scan/Connect/Disconnect)
  4. Vehicle State Cards (Grid layout)
  5. Debug Log Console (scrollable, monospace)
  6. Action Buttons (Reconnect, Clear Pairing)

### UI Components
- EditText for VIN input
- Buttons for BLE operations
- Cards for vehicle state display
- RecyclerView/LazyColumn for debug logs
- Status chip/bar for connection state
