#include "GloveController.hpp"
#include <iostream>
#include <string>
#include <cmath>

// ── Platform serial open ──────────────────────────────────────────────────
#ifdef _WIN32
static HANDLE OpenSerial(const std::string& port, int baud) {
    std::string full = "\\\\.\\" + port;
    HANDLE h = CreateFileA(full.c_str(), GENERIC_READ | GENERIC_WRITE, 0,
                           NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (h == INVALID_HANDLE_VALUE) return INVALID_HANDLE_VALUE;
    DCB dcb = {};
    dcb.DCBlength = sizeof(dcb);
    GetCommState(h, &dcb);
    dcb.BaudRate = baud;
    dcb.ByteSize = 8;
    dcb.StopBits = ONESTOPBIT;
    dcb.Parity   = NOPARITY;
    SetCommState(h, &dcb);
    COMMTIMEOUTS ct = {100, 0, 100, 0, 0};
    SetCommTimeouts(h, &ct);
    return h;
}
#else
#include <fcntl.h>
#include <termios.h>
#include <unistd.h>
static int OpenSerial(const std::string& port, int /*baud*/) {
    int fd = open(port.c_str(), O_RDONLY | O_NOCTTY);
    if (fd < 0) return -1;
    struct termios tty = {};
    tcgetattr(fd, &tty);
    cfsetispeed(&tty, B115200);
    tty.c_cflag  = (tty.c_cflag & ~CSIZE) | CS8;
    tty.c_iflag &= ~IGNBRK;
    tty.c_lflag  = 0;
    tty.c_oflag  = 0;
    tty.c_cc[VMIN]  = 0;
    tty.c_cc[VTIME] = 1;
    tcsetattr(fd, TCSANOW, &tty);
    return fd;
}
#endif

// ── Constructor / Destructor ──────────────────────────────────────────────
GloveController::GloveController(const std::string& port, int baudRate)
{
    readerThread = std::thread(&GloveController::ReaderThread, this, port, baudRate);
}

GloveController::~GloveController()
{
    running = false;
    if (readerThread.joinable()) readerThread.join();
}

// ── Background reader thread ──────────────────────────────────────────────
void GloveController::ReaderThread(const std::string& port, int baudRate)
{
#ifdef _WIN32
    HANDLE h = OpenSerial(port, baudRate);
    bool ok = (h != INVALID_HANDLE_VALUE);
#else
    int h = OpenSerial(port, baudRate);
    bool ok = (h >= 0);
#endif

    if (!ok) {
        std::cout << "[GloveController] Could not open " << port
                  << " — keyboard-only mode.\n";
        return;
    }
    connected = true;
    std::cout << "[GloveController] Connected on " << port
              << " @ " << baudRate << "\n";

    std::string buf;
    char ch;
    while (running) {
#ifdef _WIN32
        DWORD n = 0;
        ReadFile(h, &ch, 1, &n, NULL);
        if (n == 0) continue;
#else
        int n = read(h, &ch, 1);
        if (n <= 0) continue;
#endif
        if (ch == '\n') {
            if (!buf.empty()) ParseLine(buf);
            buf.clear();
        } else if (ch != '\r') {
            buf += ch;
        }
    }

#ifdef _WIN32
    CloseHandle(h);
#else
    close(h);
#endif
}

// ── Parse one serial line ─────────────────────────────────────────────────
// Unified format: ROLL=-18.5 | I=320(112) M=290(95) R=260(78) P=240(68)
//
// Each field is only updated if its marker is present in the line,
// so a partial line never clobbers valid previous values.
void GloveController::ParseLine(const std::string& line)
{
    // Helper: extract delta from "MARKER=raw(delta)" pattern
    auto findDelta = [&](const std::string& marker, std::atomic<int>& out) {
        size_t pos = line.find(marker);
        if (pos == std::string::npos) return;
        size_t open  = line.find('(', pos);
        size_t close = line.find(')', open);
        if (open == std::string::npos || close == std::string::npos) return;
        try { out = std::stoi(line.substr(open + 1, close - open - 1)); }
        catch (...) {}
    };

    // Helper: extract float from "KEY=value" pattern
    auto findFloat = [&](const std::string& key, std::atomic<float>& out) {
        size_t pos = line.find(key);
        if (pos == std::string::npos) return;
        pos += key.size();
        size_t end = line.find_first_of(" |,\r\n", pos);
        try { out = std::stof(line.substr(pos, end == std::string::npos
                                              ? line.size() - pos
                                              : end - pos)); }
        catch (...) {}
    };

    findFloat("ROLL=",  roll);
    findDelta("I=",     deltaIndex);
    findDelta("M=",     deltaMiddle);
    findDelta("R=",     deltaRing);
    findDelta("P=",     deltaPinky);

    UpdateClench();
}

// ── Hysteresis latch for clench detection ─────────────────────────────────
// Each finger turns ON when delta > FLEX_ON_THRESH, OFF when < FLEX_OFF_THRESH.
// Clenched = all four fingers simultaneously ON.
void GloveController::UpdateClench()
{
    auto updateFinger = [&](std::atomic<bool>& bent, int delta) {
        if (!bent.load() && delta > FLEX_ON_THRESH)  bent = true;
        if ( bent.load() && delta < FLEX_OFF_THRESH) bent = false;
    };

    updateFinger(bentIndex,  deltaIndex.load());
    updateFinger(bentMiddle, deltaMiddle.load());
    updateFinger(bentRing,   deltaRing.load());
    updateFinger(bentPinky,  deltaPinky.load());

    clenched = bentIndex.load() && bentMiddle.load()
            && bentRing.load()  && bentPinky.load();
}

// ── Synthesise key bitmask ────────────────────────────────────────────────
// Called every frame from the game loop.
unsigned int GloveController::GetKeys() const
{
    unsigned int keys = 0;

    // IMU roll → binary steering
    float r = roll.load();
    if      (r < -ROLL_DEADZONE) keys |= KEY_Left;
    else if (r >  ROLL_DEADZONE) keys |= KEY_Right;

    // Clench / open fist → accelerate / brake
    if (clenched.load())
        keys |= KEY_A;   // fist closed → accelerate
    else
        keys |= KEY_B;   // fist open   → brake / coast

    return keys;
}

// ── Proportional steering ratio ───────────────────────────────────────────
// Returns -1.0 (full left) … 0.0 (dead zone) … +1.0 (full right).
// Use this if Kart later gains analogue steering input.
float GloveController::GetSteeringRatio() const
{
    float r = roll.load();
    if (std::abs(r) < ROLL_DEADZONE) return 0.f;
    float sign  = (r < 0) ? -1.f : 1.f;
    float abs_r = std::abs(r) - ROLL_DEADZONE;
    float range = ROLL_FULL_STEER - ROLL_DEADZONE;
    float ratio = abs_r / range;
    return sign * (ratio > 1.f ? 1.f : ratio);
}
