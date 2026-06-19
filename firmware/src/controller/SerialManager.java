import com.fazecast.jSerialComm.SerialPort;

/**
 * SerialManager — singleton serial reader for the ESP32 glove.
 * No package — usable by both FlappyBird (default package) and com.zetcode (Tetris).
 *
 * Parses the unified firmware serial format:
 *   ROLL=-18.5 | I=320(112) M=290(95) R=260(78) P=240(68)
 *
 * ROLL  = wrist roll angle relative to calibrated neutral (degrees)
 *         negative = left tilt, positive = right tilt
 * I/M/R/P = raw ADC (delta from baseline) for Index/Middle/Ring/Pinky
 */
public class SerialManager {

    private static SerialManager instance;

    // ── Raw values from ESP32 ──────────────────────────────────────────────
    private volatile int   deltaIndex  = 0;
    private volatile int   deltaMiddle = 0;
    private volatile int   deltaRing   = 0;
    private volatile int   deltaPinky  = 0;
    private volatile float rollAngle   = 0.0f;   // NEW: wrist roll in degrees

    // ── Finger bend thresholds (on / off hysteresis) ──────────────────────
    private int onIndex  = 100, offIndex  = 55;
    private int onMiddle =  85, offMiddle = 45;
    private int onRing   =  70, offRing   = 45;
    private int onPinky  =  65, offPinky  = 25;

    // ── Finger latched states (hysteresis) ────────────────────────────────
    private volatile boolean bentIndex  = false;
    private volatile boolean bentMiddle = false;
    private volatile boolean bentRing   = false;
    private volatile boolean bentPinky  = false;

    private boolean opened = false;
    private SerialPort activePort = null;

    private SerialManager() {}

    public static SerialManager getInstance() {
        if (instance == null) instance = new SerialManager();
        return instance;
    }

    // ── Port management ───────────────────────────────────────────────────
    public void open(String portName, int baudRate) {
        if (opened) return;
        opened = true;

        SerialPort port = SerialPort.getCommPort(portName);
        port.setBaudRate(baudRate);
        port.setNumDataBits(8);
        port.setNumStopBits(SerialPort.ONE_STOP_BIT);
        port.setParity(SerialPort.NO_PARITY);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 100, 0);

        if (!port.openPort()) {
            System.out.println("[SerialManager] Port not available — keyboard/mouse only.");
            return;
        }
        activePort = port;
        System.out.println("[SerialManager] Opened: " + portName + " @ " + baudRate);

        Thread t = new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[1];
            while (true) {
                try {
                    int read = port.getInputStream().read(buf);
                    if (read <= 0) continue;
                    int b = buf[0] & 0xFF;
                    if (b == 10) {               // newline → process line
                        String line = sb.toString().trim();
                        sb.setLength(0);
                        if (!line.isEmpty()) parseLine(line);
                    } else if (b != 13) {        // skip CR
                        sb.append((char) b);
                    }
                } catch (Exception e) { /* ignore read timeouts */ }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /**
     * Send a command byte to the ESP32.
     * Use 'R' to recalibrate the IMU roll neutral point on the firmware side.
     */
    public void sendCommand(char cmd) {
        if (activePort == null || !activePort.isOpen()) return;
        try {
            activePort.getOutputStream().write((int) cmd);
            activePort.getOutputStream().flush();
        } catch (Exception e) {
            System.out.println("[SerialManager] sendCommand failed: " + e.getMessage());
        }
    }

    // ── Line parser ───────────────────────────────────────────────────────
    private void parseLine(String line) {
        try {
            // Parse ROLL field: "ROLL=-18.5"
            int rollPos = line.indexOf("ROLL=");
            if (rollPos >= 0) {
                int end = line.indexOf(' ', rollPos + 5);
                String rollStr = end < 0
                    ? line.substring(rollPos + 5)
                    : line.substring(rollPos + 5, end);
                rollAngle = Float.parseFloat(rollStr.trim());
            }

            // Parse finger deltas: I=320(112) M=290(95) R=260(78) P=240(68)
            int iPos = line.indexOf("I=");
            int mPos = line.indexOf("M=");
            int rPos = line.indexOf("R=");
            int pPos = line.indexOf("P=");
            if (iPos < 0 || mPos < 0 || rPos < 0 || pPos < 0) return;

            deltaIndex  = parseDelta(line, iPos);
            deltaMiddle = parseDelta(line, mPos);
            deltaRing   = parseDelta(line, rPos);
            deltaPinky  = parseDelta(line, pPos);

            // Update latched bend states with hysteresis
            bentIndex  = updateBent(bentIndex,  deltaIndex,  onIndex,  offIndex);
            bentMiddle = updateBent(bentMiddle, deltaMiddle, onMiddle, offMiddle);
            bentRing   = updateBent(bentRing,   deltaRing,   onRing,   offRing);
            bentPinky  = updateBent(bentPinky,  deltaPinky,  onPinky,  offPinky);

        } catch (Exception e) { /* ignore malformed lines */ }
    }

    /** Hysteresis latch: turns on at 'on' threshold, off at 'off' threshold. */
    private boolean updateBent(boolean current, int delta, int on, int off) {
        if (!current && delta > on)  return true;
        if (current  && delta < off) return false;
        return current;
    }

    private int parseDelta(String line, int pos) {
        int open  = line.indexOf('(', pos);
        int close = line.indexOf(')', open);
        if (open < 0 || close < 0) return 0;
        return Integer.parseInt(line.substring(open + 1, close).trim());
    }

    // ── Public API — raw values ───────────────────────────────────────────
    public float getRollAngle()   { return rollAngle; }
    public int   getDeltaIndex()  { return deltaIndex; }
    public int   getDeltaMiddle() { return deltaMiddle; }
    public int   getDeltaRing()   { return deltaRing; }
    public int   getDeltaPinky()  { return deltaPinky; }

    // ── Public API — latched finger states ────────────────────────────────
    public boolean isBentIndex()  { return bentIndex; }
    public boolean isBentMiddle() { return bentMiddle; }
    public boolean isBentRing()   { return bentRing; }
    public boolean isBentPinky()  { return bentPinky; }

    /** All four fingers bent = fist clenched. */
    public boolean isClenched() {
        return bentIndex && bentMiddle && bentRing && bentPinky;
    }

    // ── Threshold configuration ───────────────────────────────────────────
    public void setThresholds(int onI, int offI, int onM, int offM,
                               int onR, int offR, int onP, int offP) {
        onIndex  = onI;  offIndex  = offI;
        onMiddle = onM;  offMiddle = offM;
        onRing   = onR;  offRing   = offR;
        onPinky  = onP;  offPinky  = offP;
        System.out.printf("[SerialManager] Thresholds: I=%d/%d M=%d/%d R=%d/%d P=%d/%d%n",
            onI, offI, onM, offM, onR, offR, onP, offP);
    }

    public int getOnIndex()   { return onIndex; }
    public int getOffIndex()  { return offIndex; }
    public int getOnMiddle()  { return onMiddle; }
    public int getOffMiddle() { return offMiddle; }
    public int getOnRing()    { return onRing; }
    public int getOffRing()   { return offRing; }
    public int getOnPinky()   { return onPinky; }
    public int getOffPinky()  { return offPinky; }

    // ── Legacy compatibility (used by old FlappyBird.java) ────────────────
    /** @deprecated Use isBentIndex() and GloveController instead. */
    public boolean isLeft()   { return bentIndex; }
    /** @deprecated Use isBentMiddle() and GloveController instead. */
    public boolean isRight()  { return bentMiddle; }
    /** @deprecated Use isBentRing() and GloveController instead. */
    public boolean isRotate() { return bentRing; }
    /** @deprecated Use isClenched() and GloveController instead. */
    public boolean isDown()   { return isClenched(); }
}
