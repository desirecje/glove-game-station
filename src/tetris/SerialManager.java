package com.zetcode;

import com.fazecast.jSerialComm.SerialPort;

public class SerialManager {

    private static SerialManager instance;

    // Raw deltas from ESP32
    private volatile int   deltaIndex  = 0;
    private volatile int   deltaMiddle = 0;
    private volatile int   deltaRing   = 0;
    private volatile int   deltaPinky  = 0;
    private volatile float rollAngle   = 0.0f;   // IMU wrist roll in degrees

    // Thresholds — set by calibration, or defaults
    private int onIndex  = 100, offIndex  = 55;
    private int onMiddle = 85,  offMiddle = 45;
    private int onRing   = 70,  offRing   = 45;
    private int onPinky  = 65,  offPinky  = 25;

    private boolean opened = false;

    private SerialManager() {}

    public static SerialManager getInstance() {
        if (instance == null) instance = new SerialManager();
        return instance;
    }

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
            System.out.println("Serial not available - keyboard only.");
            return;
        }
        System.out.println("Serial opened: " + portName);

        Thread t = new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[1];
            while (true) {
                try {
                    int read = port.getInputStream().read(buf);
                    if (read <= 0) continue;
                    int b = buf[0] & 0xFF;
                    if (b == 10) {
                        String line = sb.toString().trim();
                        sb.setLength(0);
                        if (line.isEmpty()) continue;
                        parseLine(line);
                    } else if (b != 13) {
                        sb.append((char) b);
                    }
                } catch (Exception e) { /* ignore timeouts */ }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void parseLine(String line) {
        try {
            // Parse ROLL= field: "ROLL=-18.5 | I=..."
            int rollPos = line.indexOf("ROLL=");
            if (rollPos >= 0) {
                int end = line.indexOf(' ', rollPos + 5);
                String rollStr = end < 0
                    ? line.substring(rollPos + 5)
                    : line.substring(rollPos + 5, end);
                rollAngle = Float.parseFloat(rollStr.trim());
            }

            // Parse finger deltas
            int iStart = line.indexOf("I=");
            int mStart = line.indexOf("M=");
            int rStart = line.indexOf("R=");
            int pStart = line.indexOf("P=");
            if (iStart < 0 || mStart < 0 || rStart < 0 || pStart < 0) return;

            deltaIndex  = parseDelta(line, iStart);
            deltaMiddle = parseDelta(line, mStart);
            deltaRing   = parseDelta(line, rStart);
            deltaPinky  = parseDelta(line, pStart);
        } catch (Exception e) { /* ignore parse errors */ }
    }

    private int parseDelta(String line, int pos) {
        int open  = line.indexOf('(', pos);
        int close = line.indexOf(')', open);
        if (open < 0 || close < 0) return 0;
        return Integer.parseInt(line.substring(open + 1, close).trim());
    }

    // Called by Tutorial after calibration
    public void setThresholds(int onI, int offI, int onM, int offM,
                               int onR, int offR, int onP, int offP) {
        onIndex  = onI;  offIndex  = offI;
        onMiddle = onM;  offMiddle = offM;
        onRing   = onR;  offRing   = offR;
        onPinky  = onP;  offPinky  = offP;
        System.out.println("Thresholds set: I=" + onI + "/" + offI
            + " M=" + onM + "/" + offM
            + " R=" + onR + "/" + offR
            + " P=" + onP + "/" + offP);
    }

    // Raw values
    public float getRollAngle()   { return rollAngle; }
    public int   getDeltaIndex()  { return deltaIndex; }
    public int   getDeltaMiddle() { return deltaMiddle; }
    public int   getDeltaRing()   { return deltaRing; }
    public int   getDeltaPinky()  { return deltaPinky; }

    // Clench: all four fingers bent simultaneously
    public boolean isClenched() {
        return deltaIndex  > onIndex
            && deltaMiddle > onMiddle
            && deltaRing   > onRing
            && deltaPinky  > onPinky;
    }

    // Finger state (kept for backward compat with calibration)
    public boolean isLeft()   { return deltaIndex  > onIndex; }
    public boolean isRight()  { return deltaMiddle > onMiddle; }
    public boolean isRotate() { return deltaRing   > onRing; }
    public boolean isDown()   { return isClenched(); }

    public int getOnIndex()   { return onIndex; }
    public int getOffIndex()  { return offIndex; }
    public int getOnMiddle()  { return onMiddle; }
    public int getOffMiddle() { return offMiddle; }
    public int getOnRing()    { return onRing; }
    public int getOffRing()   { return offRing; }
    public int getOnPinky()   { return onPinky; }
    public int getOffPinky()  { return offPinky; }
}
