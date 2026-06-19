package com.zetcode;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.EventQueue;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

public class Tetris extends JFrame {

    private JLabel statusbar;
    private Board board;
    private Tutorial tutorial;
    private JPanel cardPanel;
    private CardLayout cardLayout;

    private static final String CARD_TUTORIAL = "tutorial";
    private static final String CARD_GAME     = "game";

    public Tetris() {
        initUI();
    }

    private void initUI() {
        // Open serial ONCE here — shared by Tutorial and Board
        SerialManager.getInstance().open("COM6", 115200);

        statusbar = new JLabel("  分数: 0   行数: 0   最高分: 0");
        add(statusbar, BorderLayout.SOUTH);

        cardLayout = new CardLayout();
        cardPanel  = new JPanel(cardLayout);

        tutorial = new Tutorial(() -> SwingUtilities.invokeLater(this::startGame));
        cardPanel.add(tutorial, CARD_TUTORIAL);

        board = new Board(this);
        cardPanel.add(board, CARD_GAME);

        add(cardPanel);
        cardLayout.show(cardPanel, CARD_TUTORIAL);

        // ESC to exit full screen
        cardPanel.setFocusable(true);
        cardPanel.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE) {
                    java.awt.GraphicsEnvironment ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment();
                    java.awt.GraphicsDevice gd = ge.getDefaultScreenDevice();
                    gd.setFullScreenWindow(null);
                    dispose();
                    System.exit(0);
                }
            }
        });

        setTitle("俄罗斯方块");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setResizable(true);

        // Full screen
        java.awt.GraphicsEnvironment ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment();
        java.awt.GraphicsDevice gd = ge.getDefaultScreenDevice();
        if (gd.isFullScreenSupported()) {
            setUndecorated(true);
            gd.setFullScreenWindow(this);
        } else {
            // Fallback: maximise window
            setExtendedState(JFrame.MAXIMIZED_BOTH);
            setLocationRelativeTo(null);
        }
    }

    private void startGame() {
        cardLayout.show(cardPanel, CARD_GAME);
        board.start();
        board.requestFocusInWindow();
    }

    JLabel getStatusBar() {
        return statusbar;
    }

    public static void main(String[] args) {
        EventQueue.invokeLater(() -> {
            var game = new Tetris();
            game.setVisible(true);
        });
    }
}
