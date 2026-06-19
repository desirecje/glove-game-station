/*
 * Glove Game Station — Unified ESP32-S3 Firmware
 * ════════════════════════════════════════════════
 *
 * Hardware (XIAO ESP32-S3):
 *   Flex sensors  →  GPIO1, GPIO2, GPIO4, GPIO5  (via 10kΩ voltage dividers)
 *   MPU-6050 SDA  →  GPIO7
 *   MPU-6050 SCL  →  GPIO6  (default XIAO SCL)
 *   MPU-6050 VCC  →  3.3V
 *   MPU-6050 GND  →  GND
 *   MPU-6050 AD0  →  GND  (I2C address 0x68)
 *
 * Serial output (115200 baud, ~50 Hz):
 *   ROLL=-18.5 | I=1823(112) M=1750(0) R=2100(420) P=1900(300)
 *
 *   ROLL        wrist roll in degrees, relative to calibrated neutral
 *               negative = tilted left, positive = tilted right
 *   I/M/R/P     raw ADC value and (delta from baseline) for
 *               Index / Middle / Ring / Pinky fingers
 *
 * Serial commands (send from PC):
 *   R  →  recalibrate IMU roll neutral (hold wrist flat first)
 *   C  →  recalibrate flex baselines   (hold hand open flat first)
 *
 * PlatformIO lib_deps:
 *   rfetick/MPU6050_light @ ^1.1.0
 */

#include <Arduino.h>
#include <Wire.h>
#include <MPU6050_light.h>

// ── Pin assignments ────────────────────────────────────────────────────────
#define PIN_INDEX   1   // Flex sensor — Index finger
#define PIN_MIDDLE  2    // Flex sensor — Middle finger
#define PIN_RING    4    // Flex sensor — Ring finger   (skips GPIO3)
#define PIN_PINKY   5    // Flex sensor — Pinky finger

// I2C for MPU-6050
#define I2C_SDA     7    // SDA on GPIO7
#define I2C_SCL     6    // SCL stays on GPIO6 (XIAO default)

// ── IMU ───────────────────────────────────────────────────────────────────
MPU6050 mpu(Wire);
float   imuRollNeutral = 0.0f;

// ── Flex baselines ────────────────────────────────────────────────────────
int baseIndex  = 0;
int baseMiddle = 0;
int baseRing   = 0;
int basePinky  = 0;

// ── Timing ────────────────────────────────────────────────────────────────
const int LOOP_HZ      = 50;
const int LOOP_MS      = 1000 / LOOP_HZ;
const int FLEX_SAMPLES = 50;

// ── IMU calibration ───────────────────────────────────────────────────────
void calibrateIMU() {
    Serial.println("IMU: keep still — calibrating offsets...");
    mpu.calcOffsets(true, true);   // ~3 s, calculates gyro + accel offsets
    mpu.update();
    imuRollNeutral = mpu.getAngleY();
    Serial.printf("IMU calibrated. Neutral roll = %.1f deg\n", imuRollNeutral);
}

// ── Flex calibration ──────────────────────────────────────────────────────
void calibrateFlex() {
    Serial.println("FLEX: hold hand open and flat...");
    delay(800);

    long sumI = 0, sumM = 0, sumR = 0, sumP = 0;
    for (int i = 0; i < FLEX_SAMPLES; i++) {
        sumI += analogRead(PIN_INDEX);
        sumM += analogRead(PIN_MIDDLE);
        sumR += analogRead(PIN_RING);
        sumP += analogRead(PIN_PINKY);
        delay(20);
    }
    baseIndex  = (int)(sumI / FLEX_SAMPLES);
    baseMiddle = (int)(sumM / FLEX_SAMPLES);
    baseRing   = (int)(sumR / FLEX_SAMPLES);
    basePinky  = (int)(sumP / FLEX_SAMPLES);

    Serial.printf("FLEX calibrated: I=%d M=%d R=%d P=%d\n",
        baseIndex, baseMiddle, baseRing, basePinky);
}

// ── Setup ─────────────────────────────────────────────────────────────────
void setup() {
    Serial.begin(115200);
    while (!Serial) delay(10);
    Serial.println("\n=== Glove Game Station Firmware ===");
    Serial.printf("Flex pins: INDEX=%d MIDDLE=%d RING=%d PINKY=%d\n",
        PIN_INDEX, PIN_MIDDLE, PIN_RING, PIN_PINKY);
    Serial.printf("I2C: SDA=%d SCL=%d\n", I2C_SDA, I2C_SCL);

    // Flex sensor analog inputs
    analogReadResolution(12);   // 0-4095 on ESP32-S3
    pinMode(PIN_INDEX,  INPUT);
    pinMode(PIN_MIDDLE, INPUT);
    pinMode(PIN_RING,   INPUT);
    pinMode(PIN_PINKY,  INPUT);

    // MPU-6050 on custom SDA=7, SCL=6
    Wire.begin(I2C_SDA, I2C_SCL);
    byte status = mpu.begin();
    if (status != 0) {
        Serial.printf("WARN: MPU-6050 init failed (status=%d)\n", status);
        Serial.println("Check: SDA->GPIO7, SCL->GPIO6, VCC->3.3V, GND->GND, AD0->GND");
    } else {
        Serial.println("MPU-6050 OK");
        calibrateIMU();
    }

    calibrateFlex();
    Serial.println("READY — outputting at 50 Hz");
    Serial.println("Commands:  R = recal IMU roll   C = recal flex");
}

// ── Loop ──────────────────────────────────────────────────────────────────
void loop() {
    unsigned long t = millis();

    // IMU roll
    mpu.update();
    float roll    = mpu.getAngleY();   // Y axis = wrist roll (left/right tilt)
    float relRoll = constrain(roll - imuRollNeutral, -90.0f, 90.0f);

    // Flex sensors
    int rawI = analogRead(PIN_INDEX);
    int rawM = analogRead(PIN_MIDDLE);
    int rawR = analogRead(PIN_RING);
    int rawP = analogRead(PIN_PINKY);

    int dI = rawI - baseIndex;
    int dM = rawM - baseMiddle;
    int dR = rawR - baseRing;
    int dP = rawP - basePinky;

    // Unified serial output
    Serial.printf("ROLL=%.1f | I=%d(%d) M=%d(%d) R=%d(%d) P=%d(%d)\n",
        relRoll,
        rawI, dI,
        rawM, dM,
        rawR, dR,
        rawP, dP);

    // Handle commands from PC
    while (Serial.available()) {
        char cmd = (char)Serial.read();
        if (cmd == 'R' || cmd == 'r') {
            mpu.update();
            imuRollNeutral = mpu.getAngleY();
            Serial.printf("ROLL_RECAL neutral=%.1f\n", imuRollNeutral);
        } else if (cmd == 'C' || cmd == 'c') {
            calibrateFlex();
        }
    }

    // Pace to 50 Hz
    int elapsed = (int)(millis() - t);
    if (elapsed < LOOP_MS) delay(LOOP_MS - elapsed);
}
