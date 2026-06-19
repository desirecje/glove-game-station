#pragma once
#include <string>
#include <thread>
#include <atomic>

#ifdef _WIN32
#include <windows.h>
#else
#include <fcntl.h>
#include <termios.h>
#include <unistd.h>
#endif

/**
 * GloveController — reads the unified glove serial stream for the racing game.
 *
 * ONE port, ONE ESP32, ONE serial format:
 *   ROLL=-18.5 | I=320(112) M=290(95) R=260(78) P=240(68)
 *
 * Control mapping (matches unified scheme across all three games):
 *   Tilt wrist left  (ROLL < -ROLL_DEADZONE)  → KEY_Left  (steer left)
 *   Tilt wrist right (ROLL >  ROLL_DEADZONE)  → KEY_Right (steer right)
 *   Clench fist      (all 4 fingers bent)     → KEY_A     (accelerate)
 *   Open fist        (all 4 fingers released) → KEY_B     (brake / coast)
 *
 * GetSteeringRatio() gives a proportional -1.0…+1.0 value for smoother
 * steering if your Kart supports analogue input later.
 *
 * Keyboard fallback: leave existing glutKeyboard handlers untouched.
 * Both glove and keyboard call KeyPress/KeyRelease on the Kart simultaneously.
 *
 * Tuning constants are public so you can tweak without recompiling the header.
 */
class GloveController {
public:
    // ── Tuning ────────────────────────────────────────────────────────────
    static constexpr float ROLL_DEADZONE     = 8.f;   // degrees, ignore micro-tilts
    static constexpr float ROLL_FULL_STEER   = 30.f;  // degrees → full lock left/right
    static constexpr int   FLEX_ON_THRESH    = 60;    // delta above → finger considered bent
    static constexpr int   FLEX_OFF_THRESH   = 20;    // delta below → finger considered open

    // ── Key bitmasks — must match Kart::Key enum values ──────────────────
    static constexpr unsigned int KEY_Left  = 1 << 0;
    static constexpr unsigned int KEY_Right = 1 << 1;
    static constexpr unsigned int KEY_A     = 1 << 2;   // accelerate (clench)
    static constexpr unsigned int KEY_B     = 1 << 3;   // brake      (open fist)
    static constexpr unsigned int KEY_X     = 1 << 4;   // rear view  (optional)

    /**
     * Opens the serial port and starts the background reader thread.
     * @param port     Serial port name, e.g. "COM6" (Windows) or "/dev/ttyUSB0" (Linux/Mac)
     * @param baudRate Must match firmware — default 115200
     */
    explicit GloveController(const std::string& port, int baudRate = 115200);
    ~GloveController();

    // ── Primary API — call GetKeys() every frame ──────────────────────────

    /**
     * Returns the current synthesised key bitmask.
     * Call once per frame, then apply each bit to Kart::KeyPress / KeyRelease.
     */
    unsigned int GetKeys() const;

    /**
     * Proportional steering: -1.0 (full left) … 0.0 (dead zone) … +1.0 (full right).
     * Dead zone and clamp applied. Use this if Kart gains analogue steering later.
     */
    float GetSteeringRatio() const;

    // ── Status / debug ────────────────────────────────────────────────────
    bool  IsConnected()  const { return connected.load(); }
    float GetRoll()      const { return roll.load(); }
    int   GetDeltaI()    const { return deltaIndex.load(); }
    int   GetDeltaM()    const { return deltaMiddle.load(); }
    int   GetDeltaR()    const { return deltaRing.load(); }
    int   GetDeltaP()    const { return deltaPinky.load(); }

    /** True while all four fingers are bent above FLEX_ON_THRESH (with hysteresis). */
    bool  IsClenched()   const { return clenched.load(); }

private:
    void ReaderThread(const std::string& port, int baudRate);
    void ParseLine(const std::string& line);
    void UpdateClench();

    std::thread       readerThread;
    std::atomic<bool> running   {true};
    std::atomic<bool> connected {false};

    // Raw sensor values — written by reader thread, read by main thread
    std::atomic<float> roll        {0.f};
    std::atomic<int>   deltaIndex  {0};
    std::atomic<int>   deltaMiddle {0};
    std::atomic<int>   deltaRing   {0};
    std::atomic<int>   deltaPinky  {0};

    // Hysteresis-latched finger states
    std::atomic<bool>  bentIndex  {false};
    std::atomic<bool>  bentMiddle {false};
    std::atomic<bool>  bentRing   {false};
    std::atomic<bool>  bentPinky  {false};
    std::atomic<bool>  clenched   {false};   // all four bent simultaneously
};
