package com.zetcode;

import com.zetcode.Shape.Tetrominoe;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class Board extends JPanel {

    private final int BOARD_WIDTH     = 8;
    private final int BOARD_HEIGHT    = 16;
    private final int PERIOD_INTERVAL = 700;

    // Detection thresholds — raw delta, no calibration needed
    private static final int   BEND_THRESH      = 95;   // clench detection (lowered for easier rotate)
    private static final int   MIN_FINGERS       = 2;    // 2 fingers bent = clench

    // IMU steering parameters
    private static final float ROLL_DEADZONE     = 22.0f;  // degrees — wide centre zone, only clear tilts move blocks
    private static final int   REPEAT_DELAY      = 550;    // ms before first repeat (slower)
    private static final int   REPEAT_INTERVAL   = 350;    // ms between repeats (slower)

    private static final int[] LINE_POINTS = {0, 100, 300, 500, 800};

    private Font fontSidebarTitle()  { return new Font("Microsoft YaHei", Font.BOLD,   sidebarWidth()/12); }
    private Font fontSidebarLabel()  { return new Font("Microsoft YaHei", Font.PLAIN,  sidebarWidth()/17); }
    private Font fontSidebarValue()  { return new Font("Microsoft YaHei", Font.BOLD,   sidebarWidth()/12); }
    private Font fontSidebarHint()   { return new Font("Microsoft YaHei", Font.PLAIN,  sidebarWidth()/20); }

    private static final Font FONT_GAMEOVER_TITLE = new Font("Microsoft YaHei", Font.BOLD,  36);
    private static final Font FONT_GAMEOVER_SCORE = new Font("Microsoft YaHei", Font.PLAIN, 24);
    private static final Font FONT_GAMEOVER_HS    = new Font("Microsoft YaHei", Font.BOLD,  22);
    private static final Font FONT_GAMEOVER_BTN   = new Font("Microsoft YaHei", Font.BOLD,  20);
    private static final Font FONT_STATUS         = new Font("Microsoft YaHei", Font.PLAIN, 14);

    private Timer timer;
    private boolean isFallingFinished = false;
    private boolean isPaused   = false;
    private boolean isGameOver = false;
    private int numLinesRemoved = 0;
    private int score     = 0;
    private int highScore = 0;
    private boolean newHighScore = false;
    private int curX = 0;
    private int curY = 0;
    private JLabel statusbar;
    private Shape curPiece;
    private Shape nextPiece;
    private Tetrominoe[] board;
    private Rectangle restartBtn = new Rectangle();

    // IMU tilt key-repeat state
    private boolean prevTiltLeft  = false;
    private boolean prevTiltRight = false;
    private long    tiltLeftSince  = 0;
    private long    tiltRightSince = 0;
    private long    lastRepeatLeft  = 0;
    private long    lastRepeatRight = 0;

    // Clench rotate — rising-edge only (one rotate per clench)
    private boolean prevClenched = false;

    public Board(Tetris parent) {
        initBoard(parent);
    }

    private void initBoard(Tetris parent) {
        setFocusable(true);
        setBackground(new Color(20, 20, 20));
        statusbar = parent.getStatusBar();
        statusbar.setFont(FONT_STATUS);
        addKeyListener(new TAdapter());
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (isGameOver && restartBtn.contains(e.getPoint())) restart();
            }
        });
        addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                setCursor(isGameOver && restartBtn.contains(e.getPoint())
                    ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    : Cursor.getDefaultCursor());
            }
        });
        // Poll glove at 50 Hz
        new Timer(20, e -> handleGloveInput()).start();
    }

    // ── New unified glove input ───────────────────────────────────────────
    // IMU roll  → move block left / right  (with key-repeat)
    // Clench    → rotate block             (one trigger per clench)
    // Drop removed entirely
    private void handleGloveInput() {
        if (isPaused || isGameOver || curPiece == null
                || curPiece.getShape() == Tetrominoe.NoShape) return;

        SerialManager sm  = SerialManager.getInstance();
        float roll        = sm.getRollAngle();
        boolean tiltLeft  = roll >  ROLL_DEADZONE;   // swapped: positive roll = move left
        boolean tiltRight = roll < -ROLL_DEADZONE;   // swapped: negative roll = move right
        // Clench: any finger bent past raw threshold — works without calibration
        int bentCount = 0;
        if (sm.getDeltaIndex()  > BEND_THRESH) bentCount++;
        if (sm.getDeltaMiddle() > BEND_THRESH) bentCount++;
        if (sm.getDeltaRing()   > BEND_THRESH) bentCount++;
        if (sm.getDeltaPinky()  > BEND_THRESH) bentCount++;
        boolean clenched  = bentCount >= MIN_FINGERS;
        long now          = System.currentTimeMillis();

        // ── Tilt left with key-repeat ─────────────────────────────────────
        if (tiltLeft && !prevTiltLeft) {
            // Rising edge — move immediately
            tryMove(curPiece, curX - 1, curY);
            tiltLeftSince  = now;
            lastRepeatLeft = now;
        } else if (tiltLeft && prevTiltLeft) {
            // Held — key-repeat after REPEAT_DELAY
            if (now - tiltLeftSince > REPEAT_DELAY
                    && now - lastRepeatLeft > REPEAT_INTERVAL) {
                tryMove(curPiece, curX - 1, curY);
                lastRepeatLeft = now;
            }
        }

        // ── Tilt right with key-repeat ────────────────────────────────────
        if (tiltRight && !prevTiltRight) {
            tryMove(curPiece, curX + 1, curY);
            tiltRightSince  = now;
            lastRepeatRight = now;
        } else if (tiltRight && prevTiltRight) {
            if (now - tiltRightSince > REPEAT_DELAY
                    && now - lastRepeatRight > REPEAT_INTERVAL) {
                tryMove(curPiece, curX + 1, curY);
                lastRepeatRight = now;
            }
        }

        // ── Clench → rotate (rising edge only) ───────────────────────────
        if (clenched && !prevClenched) {
            tryMove(curPiece.rotateLeft(), curX, curY);
        }

        prevTiltLeft  = tiltLeft;
        prevTiltRight = tiltRight;
        prevClenched  = clenched;
    }

    // ── Layout helpers ────────────────────────────────────────────────────
    private int sidebarWidth()    { return getWidth() / 5; }
    private int boardAreaWidth()  { return getWidth() - sidebarWidth(); }
    private int squareWidth()     {
        int cellByW = boardAreaWidth() / BOARD_WIDTH;
        int cellByH = getHeight()      / BOARD_HEIGHT;
        return Math.min(cellByW, cellByH);
    }
    private int squareHeight()    { return squareWidth(); }
    private int boardPixelWidth() { return squareWidth() * BOARD_WIDTH; }
    private Tetrominoe shapeAt(int x, int y) { return board[(y * BOARD_WIDTH) + x]; }

    private int ghostY() {
        int gY = curY;
        while (gY > 0) {
            boolean canMove = true;
            for (int i = 0; i < 4; i++) {
                int x = curX + curPiece.x(i);
                int y = (gY - 1) - curPiece.y(i);
                if (x < 0 || x >= BOARD_WIDTH || y < 0 || y >= BOARD_HEIGHT) { canMove = false; break; }
                if (shapeAt(x, y) != Tetrominoe.NoShape) { canMove = false; break; }
            }
            if (!canMove) break;
            gY--;
        }
        return gY;
    }

    // ── Game lifecycle ────────────────────────────────────────────────────
    void start() {
        curPiece  = new Shape();
        nextPiece = new Shape();
        nextPiece.setRandomShape();
        board = new Tetrominoe[BOARD_WIDTH * BOARD_HEIGHT];
        clearBoard();
        score = 0; numLinesRemoved = 0;
        isGameOver = false; newHighScore = false;
        updateStatus();
        newPiece();
        timer = new Timer(PERIOD_INTERVAL, new GameCycle());
        timer.start();
    }

    void restart() {
        if (timer != null) timer.stop();
        start();
        repaint();
        requestFocusInWindow();
    }

    private void pause() {
        isPaused = !isPaused;
        statusbar.setText(isPaused ? "  暂停" : "  分数: " + score + "   最高分: " + highScore);
        repaint();
    }

    private void updateStatus() {
        statusbar.setText("  分数: " + score + "   行数: " + numLinesRemoved + "   最高分: " + highScore);
    }

    // ── Drawing ───────────────────────────────────────────────────────────
    @Override
    public void paintComponent(Graphics g) {
        super.paintComponent(g);
        doDrawing(g);
        drawSidebar(g);
        if (isGameOver) drawGameOver(g);
    }

    private void doDrawing(Graphics g) {
        int boardTop = getHeight() - BOARD_HEIGHT * squareHeight();
        g.setColor(new Color(40, 40, 40));
        for (int i = 0; i <= BOARD_HEIGHT; i++)
            g.drawLine(0, boardTop + i * squareHeight(), boardPixelWidth(), boardTop + i * squareHeight());
        for (int j = 0; j <= BOARD_WIDTH; j++)
            g.drawLine(j * squareWidth(), boardTop, j * squareWidth(), getHeight());

        for (int i = 0; i < BOARD_HEIGHT; i++)
            for (int j = 0; j < BOARD_WIDTH; j++) {
                Tetrominoe shape = shapeAt(j, BOARD_HEIGHT - i - 1);
                if (shape != Tetrominoe.NoShape)
                    drawSquare(g, j * squareWidth(), boardTop + i * squareHeight(), shape);
            }

        if (curPiece != null && curPiece.getShape() != Tetrominoe.NoShape) {
            // Ghost piece
            int gY = ghostY();
            for (int i = 0; i < 4; i++) {
                int x = curX + curPiece.x(i);
                int y = gY  - curPiece.y(i);
                drawGhostSquare(g, x * squareWidth(), boardTop + (BOARD_HEIGHT - y - 1) * squareHeight());
            }
            // Current piece
            for (int i = 0; i < 4; i++) {
                int x = curX + curPiece.x(i);
                int y = curY - curPiece.y(i);
                drawSquare(g, x * squareWidth(), boardTop + (BOARD_HEIGHT - y - 1) * squareHeight(), curPiece.getShape());
            }
        }
    }

    private void drawSidebar(Graphics g) {
        int sideX = boardPixelWidth() + 10;
        int w     = sidebarWidth() - 10;
        int h     = getHeight();

        g.setColor(new Color(30, 30, 30));
        g.fillRect(boardPixelWidth(), 0, getWidth() - boardPixelWidth(), h);

        g.setFont(fontSidebarTitle());
        g.setColor(new Color(200, 200, 255));
        FontMetrics fm = g.getFontMetrics();
        String title = "俄罗斯方块";
        g.drawString(title, sideX + (w - fm.stringWidth(title)) / 2, 40);

        // Next piece preview
        int previewY = 70;
        g.setFont(fontSidebarLabel());
        g.setColor(new Color(140, 140, 140));
        fm = g.getFontMetrics();
        String next = "下一个";
        g.drawString(next, sideX + (w - fm.stringWidth(next)) / 2, previewY);

        if (nextPiece != null && nextPiece.getShape() != Tetrominoe.NoShape) {
            int cell = Math.min(w / 6, 18);

            // Shape coords can be negative — find bounds to center the piece
            int minX = nextPiece.x(0), maxX = nextPiece.x(0);
            int minY = nextPiece.y(0), maxY = nextPiece.y(0);
            for (int i = 1; i < 4; i++) {
                minX = Math.min(minX, nextPiece.x(i));
                maxX = Math.max(maxX, nextPiece.x(i));
                minY = Math.min(minY, nextPiece.y(i));
                maxY = Math.max(maxY, nextPiece.y(i));
            }
            int pieceW = (maxX - minX + 1) * cell;
            int px = sideX + (w - pieceW) / 2;
            int py = previewY + 15;

            for (int i = 0; i < 4; i++) {
                int sx = px + (nextPiece.x(i) - minX) * cell;
                int sy = py + (nextPiece.y(i) - minY) * cell;
                g.setColor(pieceColor(nextPiece.getShape()));
                g.fillRoundRect(sx + 1, sy + 1, cell - 2, cell - 2, 4, 4);
            }
        }

        int infoY      = h / 3;
        int infoSpacing = h / 8;
        drawSidebarLabel(g, "分数",  String.valueOf(score),           sideX, w, infoY);
        drawSidebarLabel(g, "行数",  String.valueOf(numLinesRemoved), sideX, w, infoY + infoSpacing);
        drawSidebarLabel(g, "最高分", String.valueOf(highScore),      sideX, w, infoY + infoSpacing * 2);

        // Control hints — updated for new scheme
        int hintY = h - 100;
        g.setFont(fontSidebarHint());
        g.setColor(new Color(100, 100, 100));
        String[] hints = {"↔ 倾斜手腕=移动", "✊ 握拳=旋转", "← → 键=移动", "↑ 键=旋转", "P=暂停"};
        for (int i = 0; i < hints.length; i++) {
            fm = g.getFontMetrics();
            g.drawString(hints[i], sideX + (w - fm.stringWidth(hints[i])) / 2, hintY + i * 18);
        }

        // Live glove indicator
        SerialManager sm = SerialManager.getInstance();
        boolean clenched = sm.isClenched();
        float   roll     = sm.getRollAngle();
        g.setColor(clenched ? new Color(100, 220, 100) : new Color(60, 60, 60));
        g.fillOval(sideX + w / 2 - 5, h - 14, 10, 10);
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 10));
        g.setColor(new Color(80, 80, 80));
        String rollStr = String.format("%.0f°", roll);
        fm = g.getFontMetrics();
        g.drawString(rollStr, sideX + (w - fm.stringWidth(rollStr)) / 2, h - 2);
    }

    private void drawSidebarLabel(Graphics g, String label, String value, int sideX, int w, int y) {
        g.setFont(fontSidebarLabel());
        g.setColor(new Color(140, 140, 140));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(label, sideX + (w - fm.stringWidth(label)) / 2, y);
        g.setFont(fontSidebarValue());
        g.setColor(Color.WHITE);
        fm = g.getFontMetrics();
        g.drawString(value, sideX + (w - fm.stringWidth(value)) / 2, y + 24);
    }

    private void drawGhostSquare(Graphics g, int x, int y) {
        g.setColor(new Color(255, 255, 255, 40));
        g.fillRect(x + 1, y + 1, squareWidth() - 2, squareHeight() - 2);
        g.setColor(new Color(255, 255, 255, 100));
        g.drawRect(x + 1, y + 1, squareWidth() - 2, squareHeight() - 2);
    }

    private void drawGameOver(Graphics g) {
        int w = getWidth(), h = getHeight();
        g.setColor(new Color(0, 0, 0, 190));
        g.fillRect(0, 0, w, h);

        int lineHeight = 40, btnH = 50, btnW = 160;
        int totalH = 45 + lineHeight + (newHighScore ? lineHeight : 0) + 20 + btnH;
        int startY = (h - totalH) / 2;

        g.setFont(FONT_GAMEOVER_TITLE); g.setColor(Color.RED);
        FontMetrics fm = g.getFontMetrics();
        String go = "游戏结束";
        int y = startY + fm.getAscent();
        g.drawString(go, (w - fm.stringWidth(go)) / 2, y);

        y += lineHeight;
        g.setFont(FONT_GAMEOVER_SCORE); g.setColor(Color.WHITE);
        fm = g.getFontMetrics();
        String sc = "分数: " + score;
        g.drawString(sc, (w - fm.stringWidth(sc)) / 2, y);

        if (newHighScore) {
            y += lineHeight;
            g.setFont(FONT_GAMEOVER_HS); g.setColor(new Color(255, 215, 0));
            fm = g.getFontMetrics();
            String hs = "新纪录!";
            g.drawString(hs, (w - fm.stringWidth(hs)) / 2, y);
        }

        int btnX = (w - btnW) / 2, btnY = y + 20;
        restartBtn.setBounds(btnX, btnY, btnW, btnH);
        g.setColor(new Color(70, 130, 180));
        g.fillRoundRect(btnX, btnY, btnW, btnH, 12, 12);
        g.setColor(new Color(100, 160, 210));
        g.drawRoundRect(btnX, btnY, btnW, btnH, 12, 12);
        g.setFont(FONT_GAMEOVER_BTN); g.setColor(Color.WHITE);
        fm = g.getFontMetrics();
        String rb = "重新开始";
        g.drawString(rb, btnX + (btnW - fm.stringWidth(rb)) / 2,
            btnY + (btnH + fm.getAscent() - fm.getDescent()) / 2);
    }

    // ── Game logic ────────────────────────────────────────────────────────
    private void dropDown() {
        int newY = curY;
        while (newY > 0) { if (!tryMove(curPiece, curX, newY - 1)) break; newY--; }
        pieceDropped();
    }

    private void oneLineDown() {
        if (!tryMove(curPiece, curX, curY - 1)) pieceDropped();
    }

    private void clearBoard() {
        for (int i = 0; i < BOARD_HEIGHT * BOARD_WIDTH; i++) board[i] = Tetrominoe.NoShape;
    }

    private void pieceDropped() {
        for (int i = 0; i < 4; i++) {
            int x = curX + curPiece.x(i);
            int y = curY - curPiece.y(i);
            board[(y * BOARD_WIDTH) + x] = curPiece.getShape();
        }
        removeFullLines();
        if (!isFallingFinished) newPiece();
    }

    private void newPiece() {
        curPiece.setShape(nextPiece.getShape());
        nextPiece.setRandomShape();
        curX = BOARD_WIDTH / 2 + 1;
        curY = BOARD_HEIGHT - 1 + curPiece.minY();
        if (!tryMove(curPiece, curX, curY)) {
            curPiece.setShape(Tetrominoe.NoShape);
            timer.stop();
            isGameOver = true;
            if (score > highScore) { highScore = score; newHighScore = true; }
            repaint();
        }
    }

    private boolean tryMove(Shape newPiece, int newX, int newY) {
        for (int i = 0; i < 4; i++) {
            int x = newX + newPiece.x(i);
            int y = newY - newPiece.y(i);
            if (x < 0 || x >= BOARD_WIDTH || y < 0 || y >= BOARD_HEIGHT) return false;
            if (shapeAt(x, y) != Tetrominoe.NoShape) return false;
        }
        curPiece = newPiece; curX = newX; curY = newY;
        repaint();
        return true;
    }

    private void removeFullLines() {
        int numFullLines = 0;
        for (int i = BOARD_HEIGHT - 1; i >= 0; i--) {
            boolean lineIsFull = true;
            for (int j = 0; j < BOARD_WIDTH; j++)
                if (shapeAt(j, i) == Tetrominoe.NoShape) { lineIsFull = false; break; }
            if (lineIsFull) {
                numFullLines++;
                for (int k = i; k < BOARD_HEIGHT - 1; k++)
                    for (int j = 0; j < BOARD_WIDTH; j++)
                        board[(k * BOARD_WIDTH) + j] = shapeAt(j, k + 1);
            }
        }
        if (numFullLines > 0) {
            numLinesRemoved += numFullLines;
            score += LINE_POINTS[Math.min(numFullLines, 4)];
            updateStatus();
            isFallingFinished = true;
            curPiece.setShape(Tetrominoe.NoShape);
        }
    }

    private Color pieceColor(Tetrominoe shape) {
        Color[] colors = {
            new Color(0, 0, 0),       new Color(204, 102, 102),
            new Color(102, 204, 102), new Color(102, 102, 204),
            new Color(204, 204, 102), new Color(204, 102, 204),
            new Color(102, 204, 204), new Color(218, 170, 0)
        };
        return colors[shape.ordinal()];
    }

    private void drawSquare(Graphics g, int x, int y, Tetrominoe shape) {
        Color[] colors = {
            new Color(0, 0, 0),     new Color(204, 102, 102),
            new Color(102, 204, 102), new Color(102, 102, 204),
            new Color(204, 204, 102), new Color(204, 102, 204),
            new Color(102, 204, 204), new Color(218, 170, 0)
        };
        var color = colors[shape.ordinal()];
        g.setColor(color);
        g.fillRect(x + 1, y + 1, squareWidth() - 2, squareHeight() - 2);
        g.setColor(color.brighter());
        g.drawLine(x, y + squareHeight() - 1, x, y);
        g.drawLine(x, y, x + squareWidth() - 1, y);
        g.setColor(color.darker());
        g.drawLine(x + 1, y + squareHeight() - 1, x + squareWidth() - 1, y + squareHeight() - 1);
        g.drawLine(x + squareWidth() - 1, y + squareHeight() - 1, x + squareWidth() - 1, y + 1);
    }

    private class GameCycle implements ActionListener {
        @Override public void actionPerformed(ActionEvent e) { update(); repaint(); }
    }

    private void update() {
        if (isPaused) return;
        if (isFallingFinished) { isFallingFinished = false; newPiece(); }
        else oneLineDown();
    }

    class TAdapter extends KeyAdapter {
        @Override
        public void keyPressed(KeyEvent e) {
            if (curPiece.getShape() == Tetrominoe.NoShape) return;
            switch (e.getKeyCode()) {
                case KeyEvent.VK_P     -> pause();
                case KeyEvent.VK_LEFT  -> tryMove(curPiece, curX - 1, curY);
                case KeyEvent.VK_RIGHT -> tryMove(curPiece, curX + 1, curY);
                case KeyEvent.VK_UP    -> tryMove(curPiece.rotateLeft(), curX, curY);
                case KeyEvent.VK_DOWN  -> oneLineDown();
                case KeyEvent.VK_SPACE -> dropDown();
            }
        }
    }
}
