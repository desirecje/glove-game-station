import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * GameLauncher — home screen for the Glove Game Station.
 *
 * Connects to the glove once at startup, then lets the player
 * pick any game. Switching games does not disconnect the serial port.
 *
 * Layout:
 *   ┌─────────────────────────────────┐
 *   │   🎮  GLOVE GAME STATION        │
 *   │                                 │
 *   │  [Flappy Bird]  [Tetris]        │
 *   │                                 │
 *   │       [Racing Game]             │
 *   │                                 │
 *   │  Controller: ● Connected        │
 *   │  [Recalibrate IMU]              │
 *   └─────────────────────────────────┘
 */
public class GameLauncher extends JFrame {

    // ── Config ────────────────────────────────────────────────────────────
    private static final String SERIAL_PORT = "COM6";   // ← change to your port
    private static final int    BAUD_RATE   = 115200;

    // ── UI colours ────────────────────────────────────────────────────────
    private static final Color BG          = new Color(18, 18, 30);
    private static final Color CARD_BG     = new Color(30, 30, 50);
    private static final Color ACCENT      = new Color(99, 102, 241);
    private static final Color TEXT_LIGHT  = new Color(230, 230, 255);
    private static final Color GREEN_DOT   = new Color(34, 197, 94);
    private static final Color RED_DOT     = new Color(239, 68, 68);

    private JLabel statusLabel;
    private JPanel contentPanel;   // swaps between home and game panels

    public GameLauncher() {
        setTitle("Glove Game Station");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(480, 520);
        setLocationRelativeTo(null);
        setResizable(false);
        getContentPane().setBackground(BG);
        setLayout(new BorderLayout());

        // NOTE: launcher does NOT open the serial port.
        // Each game opens COM6 itself when launched so it gets full glove control.
        // Only one process can hold the port at a time.

        buildHomeScreen();
        setVisible(true);

        // Poll connection status every second
        new Timer(1000, e -> updateStatus()).start();
    }

    // ── Home screen ───────────────────────────────────────────────────────
    private void buildHomeScreen() {
        getContentPane().removeAll();

        // Title
        JLabel title = new JLabel("🎮  GLOVE GAME STATION", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 24));
        title.setForeground(TEXT_LIGHT);
        title.setBorder(BorderFactory.createEmptyBorder(30, 0, 10, 0));
        add(title, BorderLayout.NORTH);

        // Game cards
        JPanel grid = new JPanel(new GridLayout(2, 2, 20, 20));
        grid.setBackground(BG);
        grid.setBorder(BorderFactory.createEmptyBorder(20, 40, 20, 40));

        grid.add(gameCard("🐦", "Flappy Bird",
            "Clench to flap",    this::launchFlappyBird));
        grid.add(gameCard("🟦", "Tetris",
            "Tilt=move  Clench=rotate", this::launchTetris));
        grid.add(gameCard("🏎️", "Racing",
            "Tilt=steer  Clench=gas",   this::launchRacing));
        grid.add(infoCard());

        add(grid, BorderLayout.CENTER);

        // Status bar
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 8));
        bottom.setBackground(BG);

        statusLabel = new JLabel("● Connecting…");
        statusLabel.setFont(new Font("Arial", Font.PLAIN, 13));
        statusLabel.setForeground(Color.YELLOW);
        bottom.add(statusLabel);

        JButton recal = styledButton("Recalibrate IMU");
        recal.addActionListener(e -> {
            GloveController.getInstance().recalibrateRoll();
            JOptionPane.showMessageDialog(this,
                "Hold your wrist flat — recalibration sent to glove.",
                "IMU Recalibrate", JOptionPane.INFORMATION_MESSAGE);
        });
        bottom.add(recal);

        add(bottom, BorderLayout.SOUTH);

        revalidate();
        repaint();
    }

    private JPanel gameCard(String emoji, String name, String hint,
                             Runnable launcher) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(CARD_BG);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ACCENT, 1, true),
            BorderFactory.createEmptyBorder(16, 12, 16, 12)));

        JLabel emojiLabel = new JLabel(emoji, SwingConstants.CENTER);
        emojiLabel.setFont(new Font("Arial", Font.PLAIN, 36));
        emojiLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel nameLabel = new JLabel(name, SwingConstants.CENTER);
        nameLabel.setFont(new Font("Arial", Font.BOLD, 16));
        nameLabel.setForeground(TEXT_LIGHT);
        nameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel hintLabel = new JLabel("<html><center>" + hint + "</center></html>",
            SwingConstants.CENTER);
        hintLabel.setFont(new Font("Arial", Font.PLAIN, 11));
        hintLabel.setForeground(new Color(150, 150, 200));
        hintLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JButton play = styledButton("▶  Play");
        play.setAlignmentX(Component.CENTER_ALIGNMENT);
        play.addActionListener(e -> launcher.run());

        card.add(emojiLabel);
        card.add(Box.createVerticalStrut(6));
        card.add(nameLabel);
        card.add(Box.createVerticalStrut(4));
        card.add(hintLabel);
        card.add(Box.createVerticalStrut(12));
        card.add(play);
        return card;
    }

    private JPanel infoCard() {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(CARD_BG);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(60, 60, 80), 1, true),
            BorderFactory.createEmptyBorder(16, 12, 16, 12)));

        String[] lines = {
            "✊  Clench = ACTION",
            "↔  Tilt  = DIRECTION",
            "Open fist = BRAKE / REST"
        };
        for (String line : lines) {
            JLabel l = new JLabel(line);
            l.setFont(new Font("Arial", Font.PLAIN, 12));
            l.setForeground(new Color(160, 160, 210));
            l.setAlignmentX(Component.CENTER_ALIGNMENT);
            card.add(l);
            card.add(Box.createVerticalStrut(6));
        }
        return card;
    }

    private JButton styledButton(String text) {
        JButton b = new JButton(text);
        b.setBackground(ACCENT);
        b.setForeground(Color.WHITE);
        b.setFont(new Font("Arial", Font.BOLD, 13));
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 18));
        return b;
    }

    // ── Status update ─────────────────────────────────────────────────────
    private void updateStatus() {
        if (statusLabel == null) return;
        // Launcher does not hold the serial port — the launched game does.
        statusLabel.setText("● Glove connects when a game launches");
        statusLabel.setForeground(GREEN_DOT);
    }

    // ── Game launchers ────────────────────────────────────────────────────
    private void launchFlappyBird() {
        try {
            String sep = java.io.File.pathSeparator;
            String ps  = java.io.File.separator;
            String cp  = "out" + sep + "lib" + ps + "jSerialComm-2.10.4.jar"
                       + sep + "assets" + ps + "flappybird";
            new ProcessBuilder("java", "-cp", cp, "App")
                .inheritIO()
                .start();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not launch Flappy Bird: " + ex.getMessage());
        }
    }

    private void launchTetris() {
        try {
            String sep = java.io.File.pathSeparator;
            String cp  = "out" + sep + "lib" + java.io.File.separator + "jSerialComm-2.10.4.jar";
            new ProcessBuilder("java", "-cp", cp, "com.zetcode.Tetris")
                .inheritIO()
                .start();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not launch Tetris: " + ex.getMessage());
        }
    }

    private void launchRacing() {
        // TODO: wire up your Racing panel here
        JOptionPane.showMessageDialog(this,
            "Racing coming soon — wire up your Racing panel in GameLauncher.launchRacing()",
            "Racing", JOptionPane.INFORMATION_MESSAGE);
    }

    // ── Entry point ───────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        SwingUtilities.invokeLater(GameLauncher::new);
    }
}
