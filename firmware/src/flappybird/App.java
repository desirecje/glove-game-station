import javax.swing.*;

public class App {
    public static void main(String[] args) throws Exception {
        // Serial is opened here when running standalone.
        // When launched from bridge/GameLauncher, port may already be taken —
        // SerialManager.open() is a no-op if already opened.
        SerialManager.getInstance().open("COM6", 115200);

        int boardWidth  = 360;
        int boardHeight = 640;

        JFrame frame = new JFrame("Flappy Bird");
        frame.setSize(boardWidth, boardHeight);
        frame.setLocationRelativeTo(null);
        frame.setResizable(false);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        FlappyBird flappyBird = new FlappyBird();
        frame.add(flappyBird);
        frame.pack();
        flappyBird.requestFocus();
        frame.setVisible(true);
    }
}
