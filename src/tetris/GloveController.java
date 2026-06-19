package com.zetcode;

/**
 * GloveController — unified virtual controller for all three games.
 *
 * Sits between SerialManager (raw hardware) and each game (logic).
 * Translates raw IMU roll + flex deltas into clean, game-ready signals.
 *
 * Control scheme (same physical gestures, different game meanings):
 *
 *   Gesture            │ Virtual signal     │ Tetris      │ Flappy Bird │ Racing
 *   ───────────────────┼────────────────────┼─────────────┼─────────────┼──────────────
 *   Tilt left  (IMU)   │ STEER_LEFT         │ Move left   │ (ignored)   │ Steer left
 *   Tilt right (IMU)   │ STEER_RIGHT        │ Move right  │ (ignored)   │ Steer right
 *   Clench fist        │ CLENCH (start)     │ Rotate      │ Flap up     │ Accelerate
 *   Hold clench        │ CLENCH (held)      │ (ignored)   │ (ignored)   │ Keep accel
 *   Open fist          │ UNCLENCH (start)   │ (ignored)   │ (ignored)   │ Brake
 *
 * IMU steering:
 *   - Dead zone: ±DEAD_ZONE_DEG degrees around neutral → no steering signal
 *   - Beyond dead zone: maps linearly to -1.0 … +1.0
 *   - Clamped at ±CLAMP_DEG (beyond that = full lock)
 *
 * Tetris tilt uses key-repeat: move once, wait REPEAT_DELAY_MS, then REPEAT_INTERVAL_MS.
 * Racing steering is continuous (use getSteeringAngle() each frame).
 * Flappy Bird ignores IMU entirely.
 *
 * Usage:
 *   GloveController gc = GloveController.getInstance();
 *   // In your game loop or timer:
 *   gc.update();
 *   if (gc.isClenchStart())   { jump(); }
 *   if (gc.isTiltLeft())      { moveBlockLeft(); }
 *   float steer = gc.getSteeringAngle();  // -1.0 … +1.0
 */
public class GloveController {

    private static GloveController instance;

    // ── IMU steering parameters ───────────────────────────────────────────
    /** Degrees of roll within which we treat the wrist as "neutral". */
    private static final float DEAD_ZONE_DEG = 8.0f;
    /** Degrees of roll at which steering reaches ±1.0 (full lock). */
    private static final float CLAMP_DEG     = 30.0f;

    // ── Tetris key-repeat parameters ──────────────────────────────────────
    /** ms before first repeat fires after holding a tilt. */
    private static final long REPEAT_DELAY_MS    = 300;
    /** ms between subsequent repeats while tilt is held. */
    private static final long REPEAT_INTERVAL_MS = 150;

    // ── Internal state ────────────────────────────────────────────────────
    private boolean prevClenched    = false;

    // Edge flags — set by update(), consumed by game on next poll
    private boolean clenchStart   = false;   // rising edge of clench
    private boolean unclenchStart = false;   // falling edge of clench

    // Tilt state for key-repeat
    private boolean prevTiltLeft  = false;
    private boolean prevTiltRight = false;
    private long    tiltLeftSince  = 0;
    private long    tiltRightSince = 0;
    private long    lastRepeatLeft  = 0;
    private long    lastRepeatRight = 0;
    // Pulse flags for Tetris-style discrete moves
    private boolean tiltLeftPulse  = false;
    private boolean tiltRightPulse = false;

    private GloveController() {}

    public static GloveController getInstance() {
        if (instance == null) instance = new GloveController();
        return instance;
    }

    // ── Core update — call once per frame / timer tick ────────────────────
    /**
     * Must be called once per game loop tick (or from a dedicated timer).
     * Computes edge signals from the latest SerialManager state.
     */
    public void update() {
        SerialManager sm = SerialManager.getInstance();
        long now = System.currentTimeMillis();

        // ── Clench edges ──────────────────────────────────────────────────
        boolean clenched = sm.isClenched();
        clenchStart   = clenched && !prevClenched;
        unclenchStart = !clenched && prevClenched;
        prevClenched  = clenched;

        // ── IMU tilt ──────────────────────────────────────────────────────
        float roll      = sm.getRollAngle();
        boolean tiltL   = roll < -DEAD_ZONE_DEG;
        boolean tiltR   = roll >  DEAD_ZONE_DEG;

        // Rising edges reset timers
        if (tiltL && !prevTiltLeft) {
            tiltLeftSince  = now;
            lastRepeatLeft = now;
            tiltLeftPulse  = true;   // immediate first pulse
        }
        if (tiltR && !prevTiltRight) {
            tiltRightSince  = now;
            lastRepeatRight = now;
            tiltRightPulse  = true;
        }

        // Key-repeat pulses while tilt is held
        if (tiltL && prevTiltLeft) {
            long elapsed = now - tiltLeftSince;
            if (elapsed > REPEAT_DELAY_MS &&
                    now - lastRepeatLeft > REPEAT_INTERVAL_MS) {
                tiltLeftPulse  = true;
                lastRepeatLeft = now;
            }
        }
        if (tiltR && prevTiltRight) {
            long elapsed = now - tiltRightSince;
            if (elapsed > REPEAT_DELAY_MS &&
                    now - lastRepeatRight > REPEAT_INTERVAL_MS) {
                tiltRightPulse  = true;
                lastRepeatRight = now;
            }
        }

        // Clear pulses when tilt released
        if (!tiltL) tiltLeftPulse  = false;
        if (!tiltR) tiltRightPulse = false;

        prevTiltLeft  = tiltL;
        prevTiltRight = tiltR;
    }

    // ── Clench signals ────────────────────────────────────────────────────

    /**
     * True on the single tick the fist closes.
     * Use for: Flappy Bird flap, Tetris rotate (one trigger per clench).
     */
    public boolean isClenchStart() {
        boolean v = clenchStart;
        clenchStart = false;   // consume — one-shot
        return v;
    }

    /**
     * True on the single tick the fist opens.
     * Use for: Racing brake trigger.
     */
    public boolean isUnclenchStart() {
        boolean v = unclenchStart;
        unclenchStart = false;
        return v;
    }

    /**
     * True continuously while fist is clenched.
     * Use for: Racing sustained acceleration.
     */
    public boolean isClenched() {
        return SerialManager.getInstance().isClenched();
    }

    // ── Tilt / steering signals ───────────────────────────────────────────

    /**
     * True on the first tick of a left tilt, then repeats with key-repeat timing.
     * Use for: Tetris discrete block moves.
     */
    public boolean isTiltLeftPulse() {
        boolean v = tiltLeftPulse;
        tiltLeftPulse = false;
        return v;
    }

    /**
     * True on the first tick of a right tilt, then repeats with key-repeat timing.
     * Use for: Tetris discrete block moves.
     */
    public boolean isTiltRightPulse() {
        boolean v = tiltRightPulse;
        tiltRightPulse = false;
        return v;
    }

    /**
     * Continuous steering value: -1.0 (full left) … 0.0 (neutral) … +1.0 (full right).
     * Dead zone and clamp applied. Use for: Racing proportional steering.
     */
    public float getSteeringAngle() {
        float roll = SerialManager.getInstance().getRollAngle();
        if (Math.abs(roll) < DEAD_ZONE_DEG) return 0.0f;
        float sign  = roll < 0 ? -1.0f : 1.0f;
        float abs   = Math.abs(roll) - DEAD_ZONE_DEG;
        float range = CLAMP_DEG - DEAD_ZONE_DEG;
        return sign * Math.min(abs / range, 1.0f);
    }

    /**
     * True whenever the wrist is tilted left beyond the dead zone.
     * Continuous (not pulsed). Use for: Racing steering checks.
     */
    public boolean isTiltLeft() {
        return SerialManager.getInstance().getRollAngle() < -DEAD_ZONE_DEG;
    }

    /**
     * True whenever the wrist is tilted right beyond the dead zone.
     * Continuous (not pulsed). Use for: Racing steering checks.
     */
    public boolean isTiltRight() {
        return SerialManager.getInstance().getRollAngle() > DEAD_ZONE_DEG;
    }

    // ── IMU recalibration ─────────────────────────────────────────────────
    /**
     * Resets the IMU roll neutral on the ESP32 firmware side.
     * Player holds wrist flat, then this is called (e.g. from a menu button).
     */
    public void recalibrateRoll() {
        SerialManager.getInstance().sendCommand('R');
        System.out.println("[GloveController] Roll recalibration requested.");
    }

    // ── Debug ─────────────────────────────────────────────────────────────
    public String debugString() {
        SerialManager sm = SerialManager.getInstance();
        return String.format(
            "ROLL=%.1f steer=%.2f clench=%b I=%d M=%d R=%d P=%d",
            sm.getRollAngle(), getSteeringAngle(), sm.isClenched(),
            sm.getDeltaIndex(), sm.getDeltaMiddle(),
            sm.getDeltaRing(), sm.getDeltaPinky());
    }
}
