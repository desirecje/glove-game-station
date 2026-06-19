package com.zetcode;

import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.*;

/**
 * Tutorial — 3-lesson onboarding, NO calibration stage.
 *
 * Lessons:
 *   1. Tilt wrist LEFT  → block moves left
 *   2. Tilt wrist RIGHT → block moves right
 *   3. Clench fist      → rotate block
 *
 * Detection uses raw values directly:
 *   - Tilt: getRollAngle() from SerialManager (from firmware ROLL= field)
 *   - Clench: any finger delta > BEND_THRESH (no calibration needed)
 */
public class Tutorial extends JPanel {

    private static final Font FONT_TITLE = new Font("Microsoft YaHei", Font.BOLD,  22);
    private static final Font FONT_INSTR = new Font("Microsoft YaHei", Font.BOLD,  18);
    private static final Font FONT_DESC  = new Font("Microsoft YaHei", Font.PLAIN, 14);
    private static final Font FONT_HINT  = new Font("Microsoft YaHei", Font.PLAIN, 12);

    // Detection thresholds — no calibration needed
    private static final float ROLL_DEADZONE = 22.0f;  // match game deadzone
    private static final int   BEND_THRESH   = 90;     // clench detection (above resting noise)

    private static final int COLS = 6;
    private static final int ROWS = 10;

    // lesson 1=tilt-left, 2=tilt-right, 3=clench-rotate
    // phase  0=fall, 1=show-tip, 2=wait-gesture, 3=success
    private int phase  = 0;
    private int lesson = 1;

    private int[][] miniBoard = new int[ROWS][COLS];
    private int pieceRow = 0, pieceCol = COLS / 2 - 1;
    private int[][] pieceShape = {{0,0},{0,1},{1,0},{2,0}};
    private Color pieceColor;

    private boolean blinkState     = true;
    private boolean gestureDetected = false;
    private int fallStep = 0;
    private static final int MAX_FALL = 3;

    private static final String[] LESSON_GESTURE = {"向左倾斜手腕", "向右倾斜手腕", "握拳"};
    private static final String[] LESSON_ACTION  = {"向左移动", "向右移动", "旋转方块"};
    private static final String[] LESSON_EMOJI   = {"↰", "↱", "✊"};
    private static final Color[]  LESSON_COLORS  = {
        new Color(102, 204, 102),
        new Color(102, 102, 204),
        new Color(204, 204, 102)
    };

    private Runnable onComplete;
    private Timer mainTimer;

    public Tutorial(Runnable onComplete) {
        this.onComplete = onComplete;
        setBackground(new Color(15, 15, 25));
        new Timer(500, e -> { blinkState = !blinkState; repaint(); }).start();
        // Skip calibration — go straight to first lesson
        resetPiece();
        runPhase();
    }

    // ── Lesson flow ───────────────────────────────────────────────────────
    private void startLesson() {
        if (lesson > 3) { showAllDone(); return; }
        resetPiece();
        phase = 0; fallStep = 0; gestureDetected = false;
        runPhase();
    }

    private void showAllDone() {
        phase = 101; repaint();
        new Timer(2000, e -> { ((Timer)e.getSource()).stop(); onComplete.run(); }).start();
    }

    private void runPhase() {
        if (mainTimer != null) mainTimer.stop();
        switch (phase) {
            case 0 -> fallBlock();
            case 1 -> showTip();
            case 2 -> waitForGesture();
            case 3 -> showSuccess();
        }
    }

    private void fallBlock() {
        mainTimer = new Timer(350, e -> {
            if (fallStep < MAX_FALL) { pieceRow++; fallStep++; repaint(); }
            else { mainTimer.stop(); phase = 1; runPhase(); }
        });
        mainTimer.start();
    }

    private void showTip() {
        repaint();
        mainTimer = new Timer(1200, e -> { mainTimer.stop(); phase = 2; runPhase(); });
        mainTimer.start();
    }

    private void waitForGesture() {
        repaint();
        mainTimer = new Timer(80, e -> {
            if (!gestureDetected && detectGesture()) {
                gestureDetected = true;
                mainTimer.stop();
                applyMove();
                phase = 3;
                runPhase();
            }
        });
        mainTimer.start();
    }

    private void showSuccess() {
        repaint();
        mainTimer = new Timer(1500, e -> {
            mainTimer.stop();
            lesson++;
            startLesson();
        });
        mainTimer.start();
    }

    /** Returns true when the correct gesture for the current lesson is detected. */
    private boolean detectGesture() {
        SerialManager sm = SerialManager.getInstance();
        return switch (lesson) {
            case 1 -> sm.getRollAngle() >  ROLL_DEADZONE;   // tilt left (swapped to match game)
            case 2 -> sm.getRollAngle() < -ROLL_DEADZONE;   // tilt right (swapped to match game)
            case 3 -> sm.getDeltaIndex()  > BEND_THRESH     // clench — any finger
                   || sm.getDeltaMiddle() > BEND_THRESH
                   || sm.getDeltaRing()   > BEND_THRESH
                   || sm.getDeltaPinky()  > BEND_THRESH;
            default -> false;
        };
    }

    private void applyMove() {
        switch (lesson) {
            case 1 -> { if (pieceCol > 0) pieceCol--; }
            case 2 -> { if (pieceCol < COLS - 2) pieceCol++; }
            case 3 -> rotatePiece();
        }
    }

    private void rotatePiece() {
        int[][] rotated = new int[pieceShape.length][2];
        for (int i = 0; i < pieceShape.length; i++) {
            rotated[i][0] = pieceShape[i][1];
            rotated[i][1] = -pieceShape[i][0];
        }
        int minR = 0, minC = 0;
        for (int[] p : rotated) { minR = Math.min(minR, p[0]); minC = Math.min(minC, p[1]); }
        for (int[] p : rotated) { p[0] -= minR; p[1] -= minC; }
        pieceShape = rotated;
    }

    private void resetPiece() {
        pieceRow = 0; pieceCol = COLS / 2 - 1;
        pieceShape = new int[][]{{0,0},{0,1},{1,0},{2,0}};
        pieceColor = LESSON_COLORS[(lesson - 1) % 3];
        miniBoard  = new int[ROWS][COLS];
    }

    // ── Paint ─────────────────────────────────────────────────────────────
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        if (phase == 101) { drawAllDone(g2, w, h); return; }
        drawLesson(g2, w, h);
    }

    private void drawLesson(Graphics2D g2, int w, int h) {
        // Title
        g2.setFont(FONT_TITLE); g2.setColor(Color.WHITE);
        drawCentered(g2, "操作教程", w, 40);

        // Progress dots (3 lessons)
        for (int i = 1; i <= 3; i++) {
            int dotX = w / 2 - 35 + (i - 1) * 35;
            Color c = i < lesson  ? new Color(100, 200, 100)
                    : i == lesson ? LESSON_COLORS[i - 1]
                    :               new Color(50, 50, 50);
            g2.setColor(c);
            g2.fillOval(dotX, 55, 18, 18);
        }

        int li = lesson - 1;

        // Lesson header
        g2.setFont(FONT_INSTR); g2.setColor(LESSON_COLORS[li]);
        drawCentered(g2, LESSON_GESTURE[li] + " → " + LESSON_ACTION[li], w, 100);

        // Mini board
        int cellSize = 36, boardW = COLS * cellSize, boardH = ROWS * cellSize;
        int boardX = (w - boardW) / 2, boardY = 118;
        g2.setColor(new Color(25, 25, 40)); g2.fillRect(boardX, boardY, boardW, boardH);
        g2.setColor(new Color(50, 50, 70)); g2.drawRect(boardX, boardY, boardW, boardH);
        g2.setColor(new Color(35, 35, 55));
        for (int r = 1; r < ROWS; r++)
            g2.drawLine(boardX, boardY + r * cellSize, boardX + boardW, boardY + r * cellSize);
        for (int c = 1; c < COLS; c++)
            g2.drawLine(boardX + c * cellSize, boardY, boardX + c * cellSize, boardY + boardH);

        Color drawColor = (phase == 3) ? new Color(100, 220, 100) : pieceColor;
        for (int[] p : pieceShape) {
            int r = pieceRow + p[0], c = pieceCol + p[1];
            if (r >= 0 && r < ROWS && c >= 0 && c < COLS) {
                int px = boardX + c * cellSize, py = boardY + r * cellSize;
                g2.setColor(drawColor);
                g2.fillRoundRect(px + 2, py + 2, cellSize - 4, cellSize - 4, 6, 6);
                g2.setColor(drawColor.brighter());
                g2.drawRoundRect(px + 2, py + 2, cellSize - 4, cellSize - 4, 6, 6);
            }
        }

        // Live roll meter (small, at bottom of board)
        SerialManager sm = SerialManager.getInstance();
        float roll = sm.getRollAngle();
        int meterY = boardY + boardH + 12;
        int meterW = boardW, meterH = 6;
        g2.setColor(new Color(40, 40, 60));
        g2.fillRoundRect(boardX, meterY, meterW, meterH, 3, 3);
        float ratio = Math.max(-1f, Math.min(1f, roll / 30f));
        int fillW = (int)(Math.abs(ratio) * (meterW / 2));
        int fillX = ratio < 0 ? boardX + meterW / 2 - fillW : boardX + meterW / 2;
        g2.setColor(lesson == 1 || lesson == 2 ? LESSON_COLORS[li] : new Color(60, 60, 80));
        g2.fillRoundRect(fillX, meterY, fillW, meterH, 3, 3);
        g2.setFont(FONT_HINT); g2.setColor(new Color(80, 80, 80));
        g2.drawString(String.format("%.1f°", roll), boardX + boardW + 6, meterY + meterH);

        // Instruction text below board
        int instrY = meterY + 30;
        switch (phase) {
            case 0 -> {
                g2.setFont(FONT_DESC); g2.setColor(new Color(150, 150, 150));
                drawCentered(g2, "方块正在下落...", w, instrY);
            }
            case 1 -> {
                g2.setFont(new Font("Microsoft YaHei", Font.PLAIN, 48));
                g2.setColor(LESSON_COLORS[li]);
                drawCentered(g2, LESSON_EMOJI[li], w, instrY + 30);
                g2.setFont(FONT_INSTR); g2.setColor(LESSON_COLORS[li]);
                drawCentered(g2, LESSON_GESTURE[li], w, instrY + 65);
            }
            case 2 -> {
                g2.setFont(new Font("Microsoft YaHei", Font.PLAIN, 48));
                g2.setColor(LESSON_COLORS[li]);
                drawCentered(g2, LESSON_EMOJI[li], w, instrY + 30);
                if (blinkState) {
                    g2.setFont(FONT_HINT); g2.setColor(new Color(130, 130, 130));
                    drawCentered(g2, "等待手势...", w, instrY + 65);
                }
            }
            case 3 -> {
                g2.setFont(FONT_INSTR); g2.setColor(new Color(100, 220, 100));
                drawCentered(g2, "✓ 太好了！", w, instrY + 30);
                g2.setFont(FONT_DESC); g2.setColor(new Color(180, 180, 180));
                drawCentered(g2, LESSON_ACTION[li] + "！", w, instrY + 58);
            }
        }

        // Step counter
        g2.setFont(FONT_HINT); g2.setColor(new Color(80, 80, 80));
        drawCentered(g2, "步骤 " + lesson + " / 3", w, h - 15);
    }

    private void drawAllDone(Graphics2D g2, int w, int h) {
        g2.setFont(new Font("Microsoft YaHei", Font.PLAIN, 70));
        g2.setColor(new Color(218, 170, 0));
        drawCentered(g2, "★", w, h / 2 - 30);
        g2.setFont(FONT_TITLE); g2.setColor(new Color(218, 170, 0));
        drawCentered(g2, "教程完成！", w, h / 2 + 30);
        g2.setFont(FONT_DESC); g2.setColor(new Color(180, 180, 180));
        drawCentered(g2, "准备开始游戏！", w, h / 2 + 65);
    }

    private void drawCentered(Graphics2D g2, String text, int w, int y) {
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(text, (w - fm.stringWidth(text)) / 2, y);
    }
}
