# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Codec2 Talkie turns Android phones into Amateur Radio HF/VHF/UHF APRS-enabled Codec2/OPUS digital voice handheld transceivers. The app supports voice communication over FreeDV modes, APRS data communication, and integrates with hardware/software modems via KISS protocol, USB audio, or sound modem functionality.

- **Language**: Java (Android)
- **Min SDK**: 23 (Android 6.0)
- **Target SDK**: 35 (Android 15.0)
- **Build System**: Gradle with native C/C++ components via CMake

## Build Commands

### Building the APK

```bash
# Set environment variables (if not using Android Studio)
ANDROID_HOME=~/Android/Sdk JAVA_HOME=~/.jdks/jbr-17 ./gradlew assembleRelease

# Debug build
./gradlew assembleDebug

# Clean build
./gradlew clean
```

### Testing

```bash
# Run unit tests
./gradlew test

# Run instrumented tests
./gradlew connectedAndroidTest
```

### Linting

```bash
# Run lint checks
./gradlew lint
```

**Note**: The CI workflow uses JDK 17 and NDK 21.4.7075529. Native codec2 libraries are built automatically via the `compileCodec2` Gradle task.

## Architecture

### Module Structure

The project consists of three Gradle modules:

- **codec2talkie**: Main Android application
- **libcodec2-android**: JNI wrapper for Codec2 codec with native C build via CMake
- **libopus-android**: JNI wrapper for OPUS codec with native C build

### Core Components

#### 1. AppService & AppWorker
- **AppService** ([codec2talkie/src/main/java/com/radio/codec2talkie/app/AppService.java](codec2talkie/src/main/java/com/radio/codec2talkie/app/AppService.java)): Foreground service managing the application lifecycle
- **AppWorker** ([codec2talkie/src/main/java/com/radio/codec2talkie/app/AppWorker.java](codec2talkie/src/main/java/com/radio/codec2talkie/app/AppWorker.java)): Handles protocol and transport layer processing
- Communication via Message/Handler pattern

#### 2. Transport Layer
Located in [codec2talkie/src/main/java/com/radio/codec2talkie/transport/](codec2talkie/src/main/java/com/radio/codec2talkie/transport/)

Implements various physical layer transports:
- **USB** (UsbSerial): USB serial connections to KISS modems
- **Bluetooth** (Bluetooth): Classic Bluetooth serial
- **BLE** (Ble): Bluetooth Low Energy
- **TCP/IP** (TcpIp): Network connections (e.g., to Direwolf)
- **Sound Modem** (SoundModemFsk, SoundModemRaw): Uses phone audio hardware as software modem
- **Loopback**: Testing transport

Created via **TransportFactory** based on user settings.

#### 3. Protocol Layer
Located in [codec2talkie/src/main/java/com/radio/codec2talkie/protocol/](codec2talkie/src/main/java/com/radio/codec2talkie/protocol/)

Protocol stack handles framing and encoding:
- **RAW**: Direct codec2 frames
- **HDLC**: HDLC framing for sound modem mode
- **KISS**: KISS protocol for hardware modems
- **KISS_BUFFERED**: Buffered KISS mode
- **KISS_PARROT**: Parrot/echo mode
- **FREEDV**: FreeDV protocol

Protocols are decorated/chained with:
- **Codec2** or **Opus**: Audio codec layer
- **APRS**: APRS data encapsulation ([protocol/aprs/](codec2talkie/src/main/java/com/radio/codec2talkie/protocol/aprs/))
- **AX.25**: Packet framing ([protocol/ax25/](codec2talkie/src/main/java/com/radio/codec2talkie/protocol/ax25/))
- **Scrambler**: Optional encryption/scrambling

Created via **ProtocolFactory** which builds the appropriate protocol stack based on settings.

#### 4. RigCtl (Radio Control)
Located in [codec2talkie/src/main/java/com/radio/codec2talkie/rigctl/](codec2talkie/src/main/java/com/radio/codec2talkie/rigctl/)

PTT (Push-to-Talk) control for external transceivers:
- **Icom** series: IC-7000, IC-7100, IC-7200, IC-7300
- **Yaesu FT-817**
- **PhoneTorch**: Uses phone flashlight as visual PTT indicator

Created via **RigCtlFactory** based on radio type.

#### 5. Storage Layer
Located in [codec2talkie/src/main/java/com/radio/codec2talkie/storage/](codec2talkie/src/main/java/com/radio/codec2talkie/storage/)

Uses Android Room database for:
- APRS log entries
- Message storage
- Station tracking

#### 6. UI Components
- **MainActivity**: Main UI with PTT button and status display
- **MapActivity**: APRS station mapping using OSMDroid
- **SettingsActivity**: Application configuration
- **Connect Activities**: BLE/Bluetooth/USB/TCP connection management

### Data Flow

1. User presses PTT → MainActivity → AppService → AppWorker
2. AppWorker → Audio recording → Protocol encode (Codec2/OPUS + APRS/AX.25 + KISS/HDLC) → Transport send
3. Receive: Transport → Protocol decode → Audio playback / APRS processing → UI update

### Native Code Build

The codec2 library is compiled from source during the Gradle build:

1. **compileCodec2** task builds codec2 for Linux (for codebook generation)
2. Then builds codec2 for each Android ABI (x86, x86_64, armeabi-v7a, arm64-v8a)
3. Output `.so` files are packaged into the APK

ABI filters can be customized in `buildconfig.default.properties` or `buildconfig.local.properties`.

## Key Settings and Configuration

Settings are managed via SharedPreferences:
- Transport type (USB/Bluetooth/BLE/TCP/Sound Modem)
- Protocol type (RAW/KISS/HDLC/FREEDV)
- Codec selection (Codec2 modes, OPUS)
- APRS configuration (callsign, SSID, path)
- Audio parameters (sample rate, buffer size)
- Radio control (rig type, PTT method)

See [PreferenceKeys.java](codec2talkie/src/main/java/com/radio/codec2talkie/settings/PreferenceKeys.java) for all setting keys.

## Development Notes

### Working with Protocols
When adding or modifying protocol handlers, remember the decorator pattern: protocols are chained together, each calling `receiveCallback.onReceiveData()` or `sendCallback.onSendData()` to pass data up/down the stack.

### Working with Transports
All transports implement the `Transport` interface. New transport implementations must handle:
- `read()` / `write()` for data transfer
- Connection lifecycle management
- Error handling and reconnection

### APRS Integration
APRS functionality is deeply integrated. The protocol layer automatically handles:
- Position reports from GPS
- Text messaging
- Station tracking and database storage
- APRS-IS gateway functionality

### Audio Processing
Audio is handled by:
- **AudioPlayer**: Playback of decoded voice
- **Recorder**: Audio capture during transmit
- Codec2/OPUS native libraries for encoding/decoding

### USB/CAT Control
USB serial is used for both:
1. KISS modem communication (data path)
2. CAT commands for PTT control (via RigCtl)

Some radios support VOX instead of CAT PTT.
