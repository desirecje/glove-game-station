// ════════════════════════════════════════════════════════════════════════════
// How to wire the updated GloveController into the Mario Kart main game file
// ════════════════════════════════════════════════════════════════════════════
//
// Control scheme (matches unified glove scheme across all three games):
//   Tilt left   → steer left   (KEY_Left)
//   Tilt right  → steer right  (KEY_Right)
//   Clench fist → accelerate   (KEY_A)
//   Open fist   → brake/coast  (KEY_B)
//
// ONE serial port, ONE ESP32, ONE unified firmware output format:
//   ROLL=-18.5 | I=320(112) M=290(95) R=260(78) P=240(68)
//
// ════════════════════════════════════════════════════════════════════════════

// ── Step 1: Include ───────────────────────────────────────────────────────
#include "GloveController.hpp"

// Global (or wrap in your app class):
GloveController* glove = nullptr;
Kart*            kart  = nullptr;

// ── Step 2: Init — ONE port now, not two ─────────────────────────────────
void Init()
{
    // ... your existing init code ...

    // Change "COM6" to whichever port the ESP32 enumerates as.
    // On Mac/Linux use e.g. "/dev/ttyUSB0" or "/dev/cu.usbserial-xxxx"
    glove = new GloveController("COM6", 115200);

    // kart = new Kart(...) — your existing kart creation
}

// ── Step 3: Game loop — feed glove keys into Kart before Calc() ──────────
void GameLoop(int elapsedMsec)
{
    if (glove && glove->IsConnected()) {
        unsigned int gloveKeys = glove->GetKeys();

        // Map each bitmask bit to a KeyPress or KeyRelease on the kart.
        // KEY_B (brake) is already set by GetKeys() when fist is open,
        // so the kart brakes/coasts whenever the player isn't clenching.
        auto applyKey = [&](unsigned int bit, Kart::Key kartKey) {
            if (gloveKeys & bit) kart->KeyPress(kartKey);
            else                 kart->KeyRelease(kartKey);
        };

        applyKey(GloveController::KEY_Left,  Kart::Key::KEY_Left);
        applyKey(GloveController::KEY_Right, Kart::Key::KEY_Right);
        applyKey(GloveController::KEY_A,     Kart::Key::KEY_A);
        applyKey(GloveController::KEY_B,     Kart::Key::KEY_B);
        // KEY_X (rear view) is not mapped to the glove — still keyboard only
    }

    kart->Calc(elapsedMsec);
    // ... rest of your game loop ...
}

// ── Step 4: Keyboard fallback — leave existing handlers untouched ─────────
// Your glutKeyboardFunc / glutSpecialFunc already call
// kart->KeyPress / kart->KeyRelease directly. No changes needed.
// Both glove and keyboard input work simultaneously.

// ── Step 5: Cleanup ───────────────────────────────────────────────────────
void Cleanup()
{
    delete glove;
    glove = nullptr;
    delete kart;
    kart = nullptr;
}

// ── Optional: Debug HUD overlay ──────────────────────────────────────────
// Call this from your HUD draw function to tune thresholds in real time.
void DrawDebugGlove(int screenX, int screenY)
{
    if (!glove) return;

    char buf[128];

    // Connection status
    snprintf(buf, sizeof(buf), "GLOVE: %s",
             glove->IsConnected() ? "CONNECTED" : "keyboard only");
    // drawText(screenX, screenY, buf);

    // IMU roll and proportional steering ratio
    snprintf(buf, sizeof(buf), "ROLL: %.1f  STEER: %.2f",
             glove->GetRoll(), glove->GetSteeringRatio());
    // drawText(screenX, screenY + 18, buf);

    // Flex deltas for threshold tuning
    snprintf(buf, sizeof(buf), "I:%d  M:%d  R:%d  P:%d  CLENCH:%s",
             glove->GetDeltaI(), glove->GetDeltaM(),
             glove->GetDeltaR(), glove->GetDeltaP(),
             glove->IsClenched() ? "YES" : "no");
    // drawText(screenX, screenY + 36, buf);

    // Active controls
    unsigned int keys = glove->GetKeys();
    snprintf(buf, sizeof(buf), "KEYS: %s%s%s%s",
             (keys & GloveController::KEY_Left)  ? "LEFT "  : "",
             (keys & GloveController::KEY_Right) ? "RIGHT " : "",
             (keys & GloveController::KEY_A)     ? "ACCEL " : "",
             (keys & GloveController::KEY_B)     ? "BRAKE"  : "");
    // drawText(screenX, screenY + 54, buf);

    // Uncomment the drawText calls and replace with your glutBitmapCharacter
    // or ImGui wrapper as appropriate.
}

// ── Notes on the two-port → one-port change ──────────────────────────────
//
// Previously GloveController took (imuPort, flexPort) — two ESP32s.
// The updated constructor takes a single port string because the unified
// firmware sends ROLL= and flex delta fields on the same serial line.
//
// If you still have the old two-port hardware wired up, use the old
// GloveController. If you've flashed the new unified firmware, use this one.
//
// Threshold tuning guide:
//   ROLL_DEADZONE  (default 8°)  — raise if steering flickers at rest
//   ROLL_FULL_STEER (default 30°) — lower for hair-trigger steering
//   FLEX_ON_THRESH  (default 60)  — lower if clench isn't registering
//   FLEX_OFF_THRESH (default 20)  — raise if brake triggers too easily
