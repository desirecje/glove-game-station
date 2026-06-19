"""
Glove Game Station — WebSocket Bridge Server
=============================================
Reads the ESP32 glove serial port and broadcasts live sensor data
to the web launcher over WebSocket.

Architecture:
  ESP32 (COM6) → this script → ws://localhost:8765 → browser

Install dependencies once:
  pip install websockets pyserial

Run:
  python bridge.py

Then open web/index.html in Chrome (or go to http://localhost:8766)
"""

import asyncio
import json
import re
import subprocess
import sys
import threading
import time
from http.server import HTTPServer, SimpleHTTPRequestHandler
from pathlib import Path

try:
    import serial
    import websockets
except ImportError:
    print("Missing dependencies. Run:  pip install websockets pyserial")
    sys.exit(1)

# ── Config ────────────────────────────────────────────────────────────────
SERIAL_PORT  = "COM6"        # ← change to your ESP32 port
BAUD_RATE    = 115200
WS_PORT      = 8765          # WebSocket  ws://localhost:8765
HTTP_PORT    = 8766          # Web server http://localhost:8766

# Game launch commands (adjust paths to your actual executables)
GAME_COMMANDS = {
    "flappy": [
        "java",
        "-cp", "out;lib/jSerialComm-2.10.4.jar;assets/flappybird",
        "App"
    ],
    "tetris": [
        "java",
        "-cp", "out;lib/jSerialComm-2.10.4.jar",
        "com.zetcode.Tetris"
    ],
    "racing": [
        # Windows: change to your actual .exe path
        # Mac/Linux: "./MarioKart"
        "MarioKart.exe"
    ],
}

# ── Shared glove state (updated by serial thread, read by WS broadcaster) ──
glove_state = {
    "connected": False,
    "roll":      0.0,
    "dI": 0, "dM": 0, "dR": 0, "dP": 0,
    "clenched":  False,
    "tiltLeft":  False,
    "tiltRight": False,
}
state_lock = threading.Lock()

# Thresholds (must match GloveController / SerialManager)
ROLL_DEADZONE   = 8.0
FLEX_ON_THRESH  = 60
FLEX_OFF_THRESH = 20

# Per-finger hysteresis latch state
_bent = {"I": False, "M": False, "R": False, "P": False}

LINE_RE = re.compile(
    r"ROLL=([-\d.]+)"
    r".*?I=\d+\((-?\d+)\)"
    r".*?M=\d+\((-?\d+)\)"
    r".*?R=\d+\((-?\d+)\)"
    r".*?P=\d+\((-?\d+)\)"
)

def parse_line(line: str):
    """Parse one serial line and update glove_state."""
    m = LINE_RE.search(line)
    if not m:
        return
    roll = float(m.group(1))
    dI, dM, dR, dP = int(m.group(2)), int(m.group(3)), int(m.group(4)), int(m.group(5))

    # Per-finger hysteresis
    for key, delta in (("I", dI), ("M", dM), ("R", dR), ("P", dP)):
        if not _bent[key] and delta > FLEX_ON_THRESH:
            _bent[key] = True
        elif _bent[key] and delta < FLEX_OFF_THRESH:
            _bent[key] = False

    clenched   = all(_bent.values())
    tilt_left  = roll < -ROLL_DEADZONE
    tilt_right = roll >  ROLL_DEADZONE

    with state_lock:
        glove_state.update({
            "connected":  True,
            "roll":       round(roll, 1),
            "dI": dI, "dM": dM, "dR": dR, "dP": dP,
            "clenched":   clenched,
            "tiltLeft":   tilt_left,
            "tiltRight":  tilt_right,
        })

def serial_reader():
    """Background thread: reads serial port forever, updates glove_state."""
    while True:
        try:
            print(f"[serial] Connecting to {SERIAL_PORT} @ {BAUD_RATE}…")
            ser = serial.Serial(SERIAL_PORT, BAUD_RATE, timeout=1)
            print(f"[serial] Connected.")
            buf = ""
            while True:
                chunk = ser.read(64).decode("utf-8", errors="ignore")
                buf += chunk
                while "\n" in buf:
                    line, buf = buf.split("\n", 1)
                    line = line.strip()
                    if line:
                        parse_line(line)
        except serial.SerialException as e:
            print(f"[serial] {e} — retrying in 3 s…")
            with state_lock:
                glove_state["connected"] = False
            time.sleep(3)
        except Exception as e:
            print(f"[serial] Unexpected error: {e}")
            time.sleep(3)

# ── Running game process ───────────────────────────────────────────────────
running_proc = None

def launch_game(game_id: str) -> str:
    global running_proc
    cmd = GAME_COMMANDS.get(game_id)
    if not cmd:
        return f"Unknown game: {game_id}"

    # Kill previous game if still running
    if running_proc and running_proc.poll() is None:
        running_proc.terminate()
        running_proc = None

    try:
        # Run from repo root (parent of web/)
        cwd = str(Path(__file__).parent.parent)
        running_proc = subprocess.Popen(cmd, cwd=cwd)
        return f"launched:{game_id}"
    except FileNotFoundError:
        return f"error:Game executable not found. Check GAME_COMMANDS in bridge.py"
    except Exception as e:
        return f"error:{e}"

# ── WebSocket handler ─────────────────────────────────────────────────────
connected_clients = set()

async def ws_handler(websocket):
    connected_clients.add(websocket)
    print(f"[ws] Client connected ({len(connected_clients)} total)")
    try:
        async for message in websocket:
            try:
                msg = json.loads(message)
                if msg.get("type") == "launch":
                    result = launch_game(msg.get("game", ""))
                    await websocket.send(json.dumps({"type": "launch_result", "result": result}))
                elif msg.get("type") == "recalibrate":
                    # Send 'R' command to ESP32 to recalibrate IMU roll
                    print("[ws] Recalibrate requested — send 'R' to serial port manually or implement serial write")
                    await websocket.send(json.dumps({"type": "recal_ack"}))
            except json.JSONDecodeError:
                pass
    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        connected_clients.discard(websocket)
        print(f"[ws] Client disconnected ({len(connected_clients)} remaining)")

async def broadcaster():
    """Push glove state to all connected clients at 20 Hz."""
    while True:
        if connected_clients:
            with state_lock:
                payload = json.dumps({"type": "glove", **glove_state})
            websockets.broadcast(connected_clients, payload)
        await asyncio.sleep(0.05)   # 20 Hz

# ── HTTP server (serves index.html) ──────────────────────────────────────
def run_http_server():
    web_dir = str(Path(__file__).parent)

    class Handler(SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=web_dir, **kwargs)
        def log_message(self, format, *args):
            pass  # suppress access logs

    server = HTTPServer(("localhost", HTTP_PORT), Handler)
    print(f"[http] Serving http://localhost:{HTTP_PORT}")
    server.serve_forever()

# ── Entry point ───────────────────────────────────────────────────────────
async def main():
    print("=" * 52)
    print("  Glove Game Station — Bridge Server")
    print("=" * 52)

    # Start serial reader in background thread
    t = threading.Thread(target=serial_reader, daemon=True)
    t.start()

    # Start HTTP server in background thread
    ht = threading.Thread(target=run_http_server, daemon=True)
    ht.start()

    print(f"[ws]   WebSocket  ws://localhost:{WS_PORT}")
    print(f"[http] Launcher   http://localhost:{HTTP_PORT}")
    print(f"[info] Change SERIAL_PORT in bridge.py if not COM6")
    print("Press Ctrl+C to stop.\n")

    async with websockets.serve(ws_handler, "localhost", WS_PORT):
        await broadcaster()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n[bridge] Stopped.")
