# SleepSense: Pebble Sleep Cycle Tracker

**SleepSense** is a sleep cycle tracking and smart wake alarm app for Pebble smartwatches (`aplite`, `basalt`, `chalk`, `diorite`, `emery`, `flint`, `gabbro`). It uses multi-sensor fusion combining the **accelerometer**, **ambient light sensor**, and **companion microphone** to classify sleep into **Awake**, **Light (N1/N2)**, **Deep (N3/Slow-Wave)**, and **REM** stages across ~90-minute ultradian cycles.

---

## Key Features

- **Multi-Sensor Fusion Actigraphy**:
  - **Accelerometer**: Continuous Vector Magnitude Counts (`vmc`) and 3D jerk variance via Pebble Health (`health_service_get_minute_history`) with real-time `accel_data_service` fallback for legacy models (`aplite`).
  - **Ambient Light Sensor**: Minute-level ambient light level classification (`AmbientLightLevel`: `VeryDark`, `Dark`, `Light`, `VeryLight`) to detect room illumination changes (sleep onset, waking up, mid-night disruptions).
  - **Microphone & Audio**:
    - **Continuous Room Noise Monitoring**: PebbleKit JS phone companion samples ambient sound levels (dB) and relays disturbance events to the watch.
    - **Voice Dream Journal**: On watches with microphones (`basalt`, `chalk`, `diorite`, `emery`), double-pressing the Select button activates a `DictationSession` to dictate morning dream notes directly into the companion log.
- **Smart Wake Alarm**:
  - Configurable target wake-up time and flexible smart wake window (15m, 30m, 45m).
  - Triggers a gentle, progressive vibration as soon as you enter **Light Sleep** during the smart window, preventing sleep inertia.
  - Hard alarm deadline cutoff if deep sleep continues until the exact alarm time.
- **On-Wrist Hypnogram**:
  - Real-time color-coded hypnogram timeline graph rendering the last 2 hours of sleep stages.
  - Live metrics: Sleep duration, cycles completed, sleep score efficiency, ambient light category, and sound level.

---

## Watch Controls

Changes need a deliberate **hold** (about a second; the watch buzzes once to confirm), so a stray press
while you sleep does nothing. A ringing alarm is the exception: any press stops it, and Down snoozes it.

| Button | Hold | Press / double-press | While the alarm rings |
| :--- | :--- | :--- | :--- |
| **SELECT** | Start / stop sleep tracking | Double-press: Voice Dream Journal (microphone dictation) | Press: stop the alarm |
| **UP** | Turn the smart alarm on / off | Nothing | Press: stop the alarm |
| **DOWN** | Cycle the smart wake window (15m &rarr; 30m &rarr; 45m) | Nothing | Press: snooze (when enabled) |

---

## Project Structure

```
PebbleSleepTracker/
├── package.json                   # App UUID, capabilities ("health", "configurable"), and messageKeys
├── wscript                        # Pebble build tool configuration
├── src/
│   ├── c/
│   │   ├── pebble_sleep_tracker.c # App lifecycle entry point & orchestrator
│   │   ├── model/
│   │   │   └── sleep_types.h      # SleepStage, AppLightLevel, SleepEpoch, SleepSession
│   │   ├── engine/
│   │   │   ├── sleep_engine.h     # Actigraphy & light classification interface
│   │   │   ├── sleep_engine.c     # Multi-sensor fusion engine & minute history processing
│   │   │   ├── smart_alarm.h      # Smart wake algorithm & gentle vibration patterns
│   │   │   └── smart_alarm.c      # Window calculation & vibe scheduler
│   │   ├── comm/
│   │   │   ├── comm.h             # AppMessage & DictationSession interface
│   │   │   └── comm.c             # AppMessage exchange & dream voice recording
│   │   └── ui/
│   │       ├── ui_main.h          # Window and layer management
│   │       └── ui_main.c          # Display dashboard, hypnogram rendering, and input handlers
│   └── pkjs/
│       └── index.js               # PebbleKit JS companion (settings webview, audio dB bridge)
```

---

## Building and Installing

### Build for all target platforms:
```sh
pebble build
```

### Install in emulator:
```sh
# Color rectangular (Pebble Time)
pebble install --emulator basalt

# Color round (Pebble Time Round)
pebble install --emulator chalk

# Monochrome with Heart Rate & Mic (Pebble 2 HR)
pebble install --emulator diorite

# Large color display (Pebble Time 2)
pebble install --emulator emery
```

### Install to a physical watch:
```sh
pebble install --phone <PHONE_IP_ADDRESS>
```
