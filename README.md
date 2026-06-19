# 🎮 Glove Game Station

A wearable glove controller (ESP32-S3 + flex sensors + MPU-6050 IMU) that lets you play three games using natural hand gestures. Built for accessibility demos with elderly users.

---

## Control scheme

The same two gestures work across all games — IMU handles **direction**, fingers handle **action**.

| Gesture | Signal | Flappy Bird | Tetris | Racing |
|---|---|---|---|---|
| Tilt wrist left | STEER_LEFT | — | Move block left | Steer left |
| Tilt wrist right | STEER_RIGHT | — | Move block right | Steer right |
| Clench fist | CLENCH | Flap up | Rotate block | Accelerate |
| Open fist | UNCLENCH | — | — | Brake |

Keyboard fallback is always active: **Space** = clench, **←→** = tilt.

---

## Repo structure

```
glove-game-station/
├── firmware/                  # ESP32 PlatformIO project
│   ├── platformio.ini
│   └── src/
│       └── main.cpp           # Reads flex sensors + IMU, outputs serial
│
├── src/
│   ├── controller/
│   │   ├── SerialManager.java # Parses serial from ESP32 (singleton)
│   │   └── GloveController.java  # Unified virtual controller (singleton)
│   ├── launcher/
│   │   └── GameLauncher.java  # Home screen — pick a game
│   ├── flappybird/
│   │   └── FlappyBird.java
│   ├── tetris/
│   │   └── Tetris.java        # (wire up your existing Tetris here)
│   └── racing/
│       └── Racing.java        # (wire up your existing Racing here)
│
├── assets/
│   ├── flappybird/            # flappybird.png, toppipe.png, etc.
│   ├── tetris/
│   └── racing/
│
├── lib/
│   └── jSerialComm-2.11.0.jar # Download from GitHub releases
│
└── README.md
```

---

## Hardware wiring

### Flex sensors → ESP32-S3

Each flex sensor sits in a voltage divider with a 10kΩ fixed resistor:

```
3.3V ── [Flex sensor] ──┬── [10kΩ] ── GND
                        └── GPIO pin (analog read)
```
For Prototype 1:
COM6
| Finger | GPIO |
|--------|------|
| Index  | 4    |
| Middle | 5    |
| Ring   | 2    |
| Pinky  | 1    |

### MPU-6050 IMU → ESP32-S3 (XIAO)

| MPU-6050 | XIAO ESP32-S3 |
|----------|---------------|
| VCC      | 3.3V          |
| GND      | GND           |
| SDA      | GPIO 7        |
| SCL      | GPIO 6        |
| AD0      | GND (address = 0x68) |

For Prototype 2 (exo-skeleton glove):
COM7
| Finger | GPIO |
|--------|------|
| Index  | 1    |
| Middle | 2    |
| Ring   | 3    |
| Pinky  | 4    |

### MPU-6050 IMU → ESP32-S3 (XIAO)

| MPU-6050 | XIAO ESP32-S3 |
|----------|---------------|
| VCC      | 3.3V          |
| GND      | GND           |
| SDA      | GPIO 5        |
| SCL      | GPIO 6        |
| AD0      | GND (address = 0x68) |

---

## Firmware setup

1. Install [PlatformIO](https://platformio.org/) (VS Code extension or CLI)
2. Open `firmware/` as a PlatformIO project
3. Connect your XIAO ESP32-S3 via USB
4. Flash:
   ```
   cd firmware
   pio run --target upload
   ```
5. Open serial monitor to verify output:
   ```
   pio device monitor --baud 115200
   ```
   You should see lines like:
   ```
   CALIBRATING — hold hand open and flat...
   CALIBRATED base I=1820 M=1750 R=1680 P=1600 ROLL=0.2
   READY
   ROLL=-2.1 | I=1823(3) M=1748(-2) R=1681(1) P=1602(2)
   ROLL=-18.4 | I=1820(0) M=1750(0) R=2100(420) P=1900(300)
   ```

6. **Recalibrate IMU at any time** — send `R` over serial, or click the button in the launcher.

---

## Java setup

### Prerequisites
- JDK 17+ (`java -version`)
- `jSerialComm-2.11.0.jar` in the `lib/` folder  
  Download: https://github.com/Fazecast/jSerialComm/releases

### Set your COM port

Edit `src/launcher/GameLauncher.java` line:
```java
private static final String SERIAL_PORT = "COM6";   // Windows
// or "/dev/ttyUSB0" on Linux
// or "/dev/cu.usbserial-xxxx" on Mac
```

### Compile

**Windows:**
```cmd
javac -cp ".;lib/jSerialComm-2.10.4.jar" src/controller/*.java src/launcher/*.java src/flappybird/*.java -d out
```

**Mac / Linux:**
```bash
javac -cp ".:lib/jSerialComm-2.10.4.jar" src/controller/*.java src/launcher/*.java src/flappybird/*.java -d out
```

### Run

**Windows:**
```cmd
java -cp "out;lib/jSerialComm-2.10.4.jar;assets" GameLauncher
```

**Mac / Linux:**
```bash
java -cp "out:lib/jSerialComm-2.10.4.jar:assets" GameLauncher
```

---

## Adding Tetris and Racing

1. Put your game panel class in `src/tetris/Tetris.java` (or `src/racing/Racing.java`)
2. Replace the `TODO` blocks in `GameLauncher.launchTetris()` / `launchRacing()`
3. Inside your game, use `GloveController` instead of talking to `SerialManager` directly:

```java
// Java games (Flappy Bird, Tetris) — GloveController.java
GloveController gc = GloveController.getInstance();
gc.update();

// Tetris
if (gc.isTiltLeftPulse())  moveBlockLeft();
if (gc.isTiltRightPulse()) moveBlockRight();
if (gc.isClenchStart())    rotateBlock();

// Flappy Bird
if (gc.isClenchStart())    jump();
```

```cpp
// C++ Racing game — GloveController.hpp/.cpp (single port)
// In your Init():
glove = new GloveController("COM6", 115200);   // one port only

// In your game loop, before Kart::Calc():
unsigned int keys = glove->GetKeys();
auto applyKey = [&](unsigned int bit, Kart::Key k) {
    if (keys & bit) kart->KeyPress(k); else kart->KeyRelease(k);
};
applyKey(GloveController::KEY_Left,  Kart::Key::KEY_Left);
applyKey(GloveController::KEY_Right, Kart::Key::KEY_Right);
applyKey(GloveController::KEY_A,     Kart::Key::KEY_A);   // clench = accel
applyKey(GloveController::KEY_B,     Kart::Key::KEY_B);   // open   = brake
```

---

## GloveController API reference

| Method | Returns | Use for |
|--------|---------|---------|
| `update()` | void | Call once per game tick |
| `isClenchStart()` | boolean | One-shot fist close — flap / rotate |
| `isUnclenchStart()` | boolean | One-shot fist open — brake trigger |
| `isClenched()` | boolean | Sustained hold — accelerate |
| `isTiltLeftPulse()` | boolean | Discrete left move with key-repeat |
| `isTiltRightPulse()` | boolean | Discrete right move with key-repeat |
| `isTiltLeft()` | boolean | Continuous left tilt |
| `isTiltRight()` | boolean | Continuous right tilt |
| `getSteeringAngle()` | float -1…+1 | Proportional steering |
| `recalibrateRoll()` | void | Reset IMU neutral on ESP32 |

---

## Serial format reference

```
ROLL=-18.5 | I=320(112) M=290(95) R=260(78) P=240(68)
```

| Field | Meaning |
|-------|---------|
| `ROLL` | Wrist roll in degrees, relative to calibrated neutral. Negative = left, positive = right |
| `I(delta)` | Index finger raw ADC and delta from baseline |
| `M(delta)` | Middle finger |
| `R(delta)` | Ring finger |
| `P(delta)` | Pinky finger |

Higher delta = more bent. Clench threshold: all four deltas exceed their `on` threshold simultaneously.

---

## Troubleshooting

**Serial not opening** — check Device Manager (Windows) or `ls /dev/tty*` (Mac/Linux) for the correct port name. Only one program can hold the port at a time — close Arduino Serial Monitor or PlatformIO monitor before running Java.

**Jumping without input** — IMU noise or flex sensor drift. Re-run the launcher to trigger a fresh calibration, or send `R` to recalibrate roll only.

**Steering always pulling one way** — wrist wasn't flat during calibration. Click **Recalibrate IMU** in the launcher while holding your wrist level.

**Game works but glove doesn't** — spacebar still works as clench fallback. Check the console for `RAW:` lines to confirm data is arriving.
