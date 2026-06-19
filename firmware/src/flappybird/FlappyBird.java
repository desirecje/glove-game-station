import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import javax.swing.*;

/**
 * FlappyBird
 *
 * Control: bend ANY finger → flap (rising edge, one flap per clench)
 * Keyboard SPACE also works as fallback.
 *
 * Detection uses raw delta threshold directly — no calibration needed.
 * A finger is considered bent when its delta exceeds BEND_THRESH.
 */
public class FlappyBird extends JPanel implements ActionListener, KeyListener {

    // How much delta change counts as a bent finger — tune if needed
    private static final int BEND_THRESH = 90;   // firmer clench needed to flap
    private static final int MIN_FINGERS = 2;    // require at least 2 fingers bent together

    int boardWidth  = 360;
    int boardHeight = 640;

    Image backgroundImg, birdImg, topPipeImg, bottomPipeImg;

    int birdX = boardWidth / 8;
    int birdY = boardHeight / 2;
    int birdWidth = 34, birdHeight = 24;

    class Bird {
        int x = birdX, y = birdY;
        int width = birdWidth, height = birdHeight;
        Image img;
        Bird(Image img) { this.img = img; }
    }

    int pipeWidth = 64, pipeHeight = 512;

    class Pipe {
        int x = boardWidth, y = 0;
        int width = pipeWidth, height = pipeHeight;
        Image img;
        boolean passed = false;
        Pipe(Image img) { this.img = img; }
    }

    Bird bird;
    int velocityX = -2;
    ArrayList<Pipe> pipes = new ArrayList<>();
    Timer gameLoop, placePipeTimer;
    boolean gameOver = false;
    double score = 0;

    float floatVelocityY = 0;
    float floatGravity   = 0.35f;

    // Rising-edge detection
    private boolean prevBent = false;

    FlappyBird() {
        setPreferredSize(new Dimension(boardWidth, boardHeight));
        setFocusable(true);
        addKeyListener(this);

        // Images loaded from classpath root (assets/flappybird/ is on classpath)
        backgroundImg = new ImageIcon(getClass().getResource("flappybirdbg.png")).getImage();
        birdImg       = new ImageIcon(getClass().getResource("flappybird.png")).getImage();
        topPipeImg    = new ImageIcon(getClass().getResource("toppipe.png")).getImage();
        bottomPipeImg = new ImageIcon(getClass().getResource("bottompipe.png")).getImage();

        bird = new Bird(birdImg);

        placePipeTimer = new Timer(2200, e -> placePipes());
        placePipeTimer.start();

        gameLoop = new Timer(1000 / 60, this);
        gameLoop.start();

        // Poll glove at 50 Hz
        new Timer(20, e -> pollGlove()).start();
    }

    private void pollGlove() {
        SerialManager sm = SerialManager.getInstance();

        // Any finger bent past threshold = flap trigger
        int bentCount = 0;
        if (sm.getDeltaIndex()  > BEND_THRESH) bentCount++;
        if (sm.getDeltaMiddle() > BEND_THRESH) bentCount++;
        if (sm.getDeltaRing()   > BEND_THRESH) bentCount++;
        if (sm.getDeltaPinky()  > BEND_THRESH) bentCount++;
        boolean bent = bentCount >= MIN_FINGERS;

        // Rising edge only — one flap per clench
        if (bent && !prevBent) {
            jump();
        }
        prevBent = bent;
    }

    private void jump() {
        floatVelocityY = -6f;
        if (gameOver) restartGame();
    }

    private void restartGame() {
        bird.y = birdY;
        floatVelocityY = 0;
        pipes.clear();
        gameOver = false;
        score    = 0;
        gameLoop.start();
        placePipeTimer.start();
    }

    void placePipes() {
        int randomPipeY  = (int)(-(pipeHeight / 4) - Math.random() * (pipeHeight / 2));
        int openingSpace = boardHeight / 3;
        Pipe top = new Pipe(topPipeImg);    top.y = randomPipeY;
        Pipe bot = new Pipe(bottomPipeImg); bot.y = top.y + pipeHeight + openingSpace;
        pipes.add(top);
        pipes.add(bot);
    }

    public void move() {
        floatVelocityY += floatGravity;
        bird.y = Math.max(bird.y + (int) floatVelocityY, 0);

        for (Pipe pipe : pipes) {
            pipe.x += velocityX;
            if (!pipe.passed && bird.x > pipe.x + pipe.width) {
                score += 0.5;
                pipe.passed = true;
            }
            if (collision(bird, pipe)) gameOver = true;
        }
        if (bird.y > boardHeight) gameOver = true;
    }

    boolean collision(Bird a, Pipe b) {
        return a.x < b.x + b.width  && a.x + a.width  > b.x &&
               a.y < b.y + b.height && a.y + a.height > b.y;
    }

    @Override
    public void paintComponent(Graphics g) { super.paintComponent(g); draw(g); }

    public void draw(Graphics g) {
        g.drawImage(backgroundImg, 0, 0, boardWidth, boardHeight, null);
        g.drawImage(birdImg, bird.x, bird.y, bird.width, bird.height, null);
        for (Pipe pipe : pipes)
            g.drawImage(pipe.img, pipe.x, pipe.y, pipe.width, pipe.height, null);

        g.setColor(Color.white);
        g.setFont(new Font("Arial", Font.PLAIN, 32));
        if (gameOver)
            g.drawString("Game Over: " + (int)score, 10, 35);
        else
            g.drawString(String.valueOf((int)score), 10, 35);

        // Live glove hint
        SerialManager sm = SerialManager.getInstance();
        int bentCount = 0;
        if (sm.getDeltaIndex()  > BEND_THRESH) bentCount++;
        if (sm.getDeltaMiddle() > BEND_THRESH) bentCount++;
        if (sm.getDeltaRing()   > BEND_THRESH) bentCount++;
        if (sm.getDeltaPinky()  > BEND_THRESH) bentCount++;
        boolean bent = bentCount >= MIN_FINGERS;
        g.setFont(new Font("Arial", Font.PLAIN, 14));
        g.setColor(new Color(255, 255, 255, 180));
        g.drawString(bent ? "✊ CLENCH!" : "✊ clench fist to flap", 10, boardHeight - 10);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        move();
        repaint();
        if (gameOver) {
            placePipeTimer.stop();
            gameLoop.stop();
        }
    }

    @Override public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_SPACE) jump();
    }
    @Override public void keyTyped(KeyEvent e) {}
    @Override public void keyReleased(KeyEvent e) {}
}
