import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Scanner;
import javax.swing.*;

public class AutoBarnaby {

    // --- PROGRAM CONSTANTS ---
    private static final String VERSION = "1.1.0";

    // 1. Boundaries of the square game board
    private static final int GAME_LEFT = 463;
    private static final int GAME_TOP = 53;
    private static final int GAME_RIGHT = 1456;
    private static final int GAME_BOTTOM = 1024;

    // 2. Barnaby's Colour: rgb(206, 223, 140)
    private static final int BARNABY_C_R = 206;
    private static final int BARNABY_C_G = 223;
    private static final int BARNABY_C_B = 140;

    // 3. Engine Tuning
    private static final int MAX_BARNABY_Y = 250;
    private static final double COLOR_TOLERANCE_PERCENT = 8;
    private static final double TARGET_PERCENT = 0.52;
    private static final long CLICK_COOLDOWN_MS = 350;
    private static final int ASSUMED_GAP_SCREEN_SIZE = 300;
    private static final int DROP_TOLERANCE = 90;
    private static final int DANGER_ZONE_WIDTH = 600;

    // 4. System Variables
    private static final Scanner sc = new Scanner(System.in);
    private long lastClickAt = 0L;
    private Robot rb;
    private int nextObstacleId = 1;

    // Debug Viewport Settings
    private volatile double diagnosticZoom = 0.60;
    private JFrame debugFrame;
    private JLabel debugImageLabel;

    // --- DATA MODELS ---

    private static class RawBlock {
        final boolean isTop;
        final int minX;
        final int maxX;
        final int extremeY;

        RawBlock(boolean isTop, int minX, int maxX, int extremeY) {
            this.isTop = isTop;
            this.minX = minX;
            this.maxX = maxX;
            this.extremeY = extremeY;
        }
    }

    private static class TrackedObstacle {
        final int id;
        final boolean isTop;
        int x;
        int width;
        int extremeY;
        boolean locked = false;
        int framesLost = 0;
        int framesTracked = 0;

        TrackedObstacle(int id, boolean isTop, int x, int width, int extremeY) {
            this.id = id;
            this.isTop = isTop;
            this.x = x;
            this.width = width;
            this.extremeY = extremeY;
        }

        Rectangle getRelativeBounds(int originX, int originY) {
            int relX = x - originX;
            if (isTop) {
                int relY = GAME_TOP - originY;
                int h = extremeY - GAME_TOP;
                return new Rectangle(relX, relY, width, Math.max(10, h));
            } else {
                int relY = extremeY - originY;
                int h = GAME_BOTTOM - extremeY;
                return new Rectangle(relX, relY, width, Math.max(10, h));
            }
        }
    }

    // --- MAIN & CONSOLE BOOTSTRAP ---

    public static void main(String[] args) {
        try {
            if (System.console() == null && !GraphicsEnvironment.isHeadless()) {
                String os = System.getProperty("os.name").toLowerCase();
                File jarFile = new File(AutoBarnaby.class.getProtectionDomain().getCodeSource().getLocation().toURI());

                if (os.contains("win")) {
                    new ProcessBuilder("cmd", "/c", "start", "java", "-jar", jarFile.getAbsolutePath()).start();
                    System.exit(0);
                } else if (os.contains("mac")) {
                    new ProcessBuilder("open", "-a", "Terminal", jarFile.getAbsolutePath()).start();
                    System.exit(0);
                }
            }
            new AutoBarnaby().run();
        } catch (Throwable t) {
            try {
                File logFile = new File("crash_log.txt");
                PrintWriter pw = new PrintWriter(logFile);
                t.printStackTrace(pw);
                pw.close();
                JOptionPane.showMessageDialog(null, "Program crashed! Check crash_log.txt for details.", "AutoBarnaby Error", JOptionPane.ERROR_MESSAGE);
            } catch (Exception ignored) {
            }
            System.exit(1);
        }
    }

    public void run() {
        int terminalWidth = 60;
        System.out.println("=".repeat(terminalWidth) + "\n" + " ".repeat(terminalWidth / 3) + "AUTO BARNABY\n"
                + " ".repeat(terminalWidth / 3) + "Swimmy Barnaby Beater\n"
                + "=".repeat(terminalWidth));
        System.out.println("\nVersion " + VERSION);

        try {
            commandPrompt();
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private void commandPrompt() throws AWTException {
        System.out.println("\nInstructions:");
        System.out.println("- Default skin on Finn is required for accurate color matching.");
        System.out.println("- Screen resolution must be 1920x1080 with Swimmy Barnaby on the primary monitor.");
        System.out.println("- The bot auto-detects round transitions; no restarts required between games.");
        System.out.println("\nControls:");
        System.out.println("- Type 's' to start AutoBarnaby (Standard high-performance mode).");
        System.out.println("- Type 'd' to start AutoBarnaby with Live Visual Debugging.");
        System.out.println("- Type 'q' to quit.");

        while (true) {
            System.out.print("Option:");
            String input = sc.nextLine().trim();

            if (input.equalsIgnoreCase("s")) {
                startEngine(false);
            } else if (input.equalsIgnoreCase("d")) {
                startEngine(true);
            } else if (input.equalsIgnoreCase("q")) {
                System.out.println("Quitting.");
                System.exit(0);
            }
        }
    }

    // --- CORE BOT ENGINE ---

    private void startEngine(boolean debugMode) throws AWTException {
        rb = new Robot();
        int captureW = GAME_RIGHT - GAME_LEFT;
        int captureH = GAME_BOTTOM - GAME_TOP;
        Rectangle gameBox = new Rectangle(GAME_LEFT, GAME_TOP, captureW, captureH);
        List<TrackedObstacle> trackedObstacles = new ArrayList<>();

        if (debugMode) {
            initDebugGui(captureW, captureH);
            System.out.println("Debug Vision Monitor online. Active playing enabled.");
        } else {
            System.out.println("Engine started in headless mode. Press CTRL+C to stop.");
        }

        while (true) {
            long loopStart = System.currentTimeMillis();

            if (debugMode && (debugFrame == null || !debugFrame.isVisible())) {
                System.out.println("Diagnostic window closed. Returning to menu.");
                break;
            }

            BufferedImage capture = rb.createScreenCapture(gameBox);
            int[] pixels = capture.getRGB(0, 0, captureW, captureH, null, 0, captureW);

            // 1. Locate Barnaby
            Point barnaby = locateBarnaby(pixels, captureW);

            // 2. Scan & track rigid obstacles
            List<RawBlock> rawBlocks = scanRawSeaweedBlocks(pixels, captureW);
            updateTrackedObstacles(trackedObstacles, rawBlocks);

            // 3. Compute flight corridor target
            int targetY = calculateTargetY(trackedObstacles, barnaby);

            // 4. Autonomous Jump Evaluation
            evaluateAutopilot(barnaby, targetY);

            // 5. Render live diagnostics if enabled
            if (debugMode) {
                renderDebugView(capture, pixels, trackedObstacles, barnaby, targetY, captureW, captureH);
                long loopTime = System.currentTimeMillis() - loopStart;
                if (loopTime < 33) {
                    try {
                        Thread.sleep(33 - loopTime);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
    }

    // --- VISION & TRACKING SUBSYSTEMS ---

    private Point locateBarnaby(int[] pixels, int captureW) {
        double tol = 255 * (COLOR_TOLERANCE_PERCENT / 100.0);
        int maxScanX = (captureW * 30) / 100;

        for (int screenY = Math.max(GAME_TOP, MAX_BARNABY_Y); screenY < GAME_BOTTOM; screenY += 5) {
            for (int screenX = GAME_LEFT; screenX < GAME_LEFT + maxScanX; screenX += 5) {
                int sx = screenX - GAME_LEFT;
                int sy = screenY - GAME_TOP;
                int rgb = pixels[sy * captureW + sx];

                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                if (Math.abs(r - BARNABY_C_R) <= tol && Math.abs(g - BARNABY_C_G) <= tol && Math.abs(b - BARNABY_C_B) <= tol) {
                    return new Point(screenX, screenY);
                }
            }
        }
        return null;
    }

    private List<RawBlock> scanRawSeaweedBlocks(int[] pixels, int captureW) {
        List<RawBlock> rawBlocks = new ArrayList<>();
        int scanStep = 10;

        int curTopMinX = -1, curTopMaxX = -1, curTopMaxY = -1;
        int curBotMinX = -1, curBotMaxX = -1, curBotMinY = Integer.MAX_VALUE;

        for (int screenX = GAME_LEFT; screenX <= GAME_RIGHT; screenX += scanStep) {
            // Roof Hugging Validation
            boolean connectsToRoof = false;
            for (int screenY = GAME_TOP + 15; screenY <= GAME_TOP + 45; screenY += 5) {
                int rgb = pixels[(screenY - GAME_TOP) * captureW + (screenX - GAME_LEFT)];
                if (isSeaweedPixel((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF)) {
                    connectsToRoof = true;
                    break;
                }
            }

            int colTopY = -1;
            if (connectsToRoof) {
                colTopY = GAME_TOP + 15;
                for (int screenY = GAME_TOP + 15; screenY < GAME_BOTTOM - 50; screenY += 5) {
                    int rgb = pixels[(screenY - GAME_TOP) * captureW + (screenX - GAME_LEFT)];
                    if (isSeaweedPixel((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF)) {
                        colTopY = screenY;
                    } else if (screenY > colTopY + 150) {
                        break;
                    }
                }
            }

            // Floor Hugging Validation
            boolean connectsToFloor = false;
            for (int screenY = GAME_BOTTOM - 15; screenY >= GAME_BOTTOM - 45; screenY -= 5) {
                int rgb = pixels[(screenY - GAME_TOP) * captureW + (screenX - GAME_LEFT)];
                if (isSeaweedPixel((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF)) {
                    connectsToFloor = true;
                    break;
                }
            }

            int colBotY = -1;
            if (connectsToFloor) {
                colBotY = GAME_BOTTOM - 15;
                for (int screenY = GAME_BOTTOM - 15; screenY > GAME_TOP + 50; screenY -= 5) {
                    int rgb = pixels[(screenY - GAME_TOP) * captureW + (screenX - GAME_LEFT)];
                    if (isSeaweedPixel((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF)) {
                        colBotY = screenY;
                    } else if (screenY < colBotY - 150) {
                        break;
                    }
                }
            }

            // Group contiguous runs
            if (colTopY != -1) {
                if (curTopMinX == -1) {
                    curTopMinX = screenX;
                    curTopMaxY = colTopY;
                }
                curTopMaxX = screenX + scanStep;
                curTopMaxY = Math.max(curTopMaxY, colTopY);
            } else if (curTopMinX != -1) {
                if (curTopMaxX - curTopMinX >= 15) {
                    rawBlocks.add(new RawBlock(true, curTopMinX, curTopMaxX, curTopMaxY));
                }
                curTopMinX = -1;
            }

            if (colBotY != -1) {
                if (curBotMinX == -1) {
                    curBotMinX = screenX;
                    curBotMinY = colBotY;
                }
                curBotMaxX = screenX + scanStep;
                curBotMinY = Math.min(curBotMinY, colBotY);
            } else if (curBotMinX != -1) {
                if (curBotMaxX - curBotMinX >= 15) {
                    rawBlocks.add(new RawBlock(false, curBotMinX, curBotMaxX, curBotMinY));
                }
                curBotMinX = -1;
            }
        }

        if (curTopMinX != -1 && (curTopMaxX - curTopMinX >= 15)) {
            rawBlocks.add(new RawBlock(true, curTopMinX, curTopMaxX, curTopMaxY));
        }
        if (curBotMinX != -1 && (curBotMaxX - curBotMinX >= 15)) {
            rawBlocks.add(new RawBlock(false, curBotMinX, curBotMaxX, curBotMinY));
        }

        return rawBlocks;
    }

    private void updateTrackedObstacles(List<TrackedObstacle> trackedObstacles, List<RawBlock> rawBlocks) {
        boolean[] rawMatched = new boolean[rawBlocks.size()];

        for (TrackedObstacle tracked : trackedObstacles) {
            int bestMatchIdx = -1;
            int bestOverlapDist = Integer.MAX_VALUE;

            for (int i = 0; i < rawBlocks.size(); i++) {
                if (rawMatched[i]) continue;
                RawBlock raw = rawBlocks.get(i);

                if (raw.isTop == tracked.isTop) {
                    int dist = Math.abs(raw.minX - tracked.x);
                    boolean overlaps = (raw.minX <= tracked.x + tracked.width + 40)
                            && (raw.maxX >= tracked.x - 40);

                    if (overlaps && dist < bestOverlapDist) {
                        bestOverlapDist = dist;
                        bestMatchIdx = i;
                    }
                }
            }

            if (bestMatchIdx != -1) {
                RawBlock matchedRaw = rawBlocks.get(bestMatchIdx);
                rawMatched[bestMatchIdx] = true;
                tracked.framesLost = 0;
                tracked.framesTracked++;

                if (!tracked.locked) {
                    tracked.x = matchedRaw.minX;
                    tracked.width = Math.max(tracked.width, matchedRaw.maxX - matchedRaw.minX);

                    if (tracked.isTop) {
                        tracked.extremeY = Math.max(tracked.extremeY, matchedRaw.extremeY);
                    } else {
                        tracked.extremeY = Math.min(tracked.extremeY, matchedRaw.extremeY);
                    }

                    // Freeze dimensions once clear of the spawner boundary
                    boolean fullyInside = matchedRaw.maxX < (GAME_RIGHT - 35);
                    if (fullyInside && tracked.framesTracked >= 4 && tracked.width >= 30) {
                        tracked.locked = true;
                    }
                } else {
                    // Locked: Only translate horizontally, never resize
                    tracked.x = matchedRaw.minX;
                }
            } else {
                tracked.framesLost++;
                tracked.x -= 4; // Predict leftward scroll
            }
        }

        // Register incoming obstacles
        for (int i = 0; i < rawBlocks.size(); i++) {
            if (!rawMatched[i]) {
                RawBlock raw = rawBlocks.get(i);
                if (raw.maxX - raw.minX >= 20) {
                    trackedObstacles.add(new TrackedObstacle(nextObstacleId++, raw.isTop, raw.minX, raw.maxX - raw.minX, raw.extremeY));
                }
            }
        }

        // Evict expired obstacles
        Iterator<TrackedObstacle> it = trackedObstacles.iterator();
        while (it.hasNext()) {
            TrackedObstacle to = it.next();
            if (to.framesLost > 8 || to.x + to.width < GAME_LEFT - 20) {
                it.remove();
            }
        }
    }

    private int calculateTargetY(List<TrackedObstacle> obstacles, Point barnaby) {
        int scanStartX = (barnaby != null) ? barnaby.x : GAME_LEFT;
        int scanEndX = Math.min(GAME_RIGHT, scanStartX + DANGER_ZONE_WIDTH);

        int worstTopY = GAME_TOP;
        int worstBottomY = GAME_BOTTOM;
        boolean foundTop = false;
        boolean foundBottom = false;

        for (TrackedObstacle obs : obstacles) {
            if (obs.x + obs.width >= scanStartX && obs.x <= scanEndX) {
                if (obs.isTop) {
                    worstTopY = Math.max(worstTopY, obs.extremeY);
                    foundTop = true;
                } else {
                    worstBottomY = Math.min(worstBottomY, obs.extremeY);
                    foundBottom = true;
                }
            }
        }

        int targetY = 450;
        if (foundTop && foundBottom) {
            targetY = worstTopY + (int) ((worstBottomY - worstTopY) * TARGET_PERCENT);
        } else if (foundTop) {
            targetY = worstTopY + (int) (ASSUMED_GAP_SCREEN_SIZE * TARGET_PERCENT);
        } else if (foundBottom) {
            targetY = worstBottomY - (int) (ASSUMED_GAP_SCREEN_SIZE * (1.0 - TARGET_PERCENT));
        }

        return Math.max(GAME_TOP + 10, Math.min(GAME_BOTTOM - 10, targetY));
    }

    private void evaluateAutopilot(Point barnaby, int targetY) {
        if (barnaby == null) return;

        long now = System.currentTimeMillis();
        boolean shouldClick = (targetY > 0 && barnaby.y > targetY + DROP_TOLERANCE)
                || (barnaby.y > GAME_BOTTOM - 100);

        if (shouldClick && now - lastClickAt >= CLICK_COOLDOWN_MS) {
            clickBarnaby();
            lastClickAt = now;
        }
    }

    private void clickBarnaby() {
        try {
            rb.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            Thread.sleep(25);
            rb.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        } catch (Exception e) {
            System.out.println("Error clicking: " + e.getMessage());
        }
    }

    private boolean isSeaweedPixel(int r, int g, int b) {
        return (g > b + 5 && g > r + 5 && g > 60);
    }

    // --- DIAGNOSTIC GUI ENGINE ---

    private void initDebugGui(int captureW, int captureH) {
        debugFrame = new JFrame("AutoBarnaby Live Vision Diagnostic");
        debugFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        debugImageLabel = new JLabel();
        JScrollPane scrollPane = new JScrollPane(debugImageLabel);

        JPanel zoomControls = new JPanel();
        JButton zoomOutBtn = new JButton("-");
        JButton zoomResetBtn = new JButton("100%");
        JButton zoomInBtn = new JButton("+");

        zoomOutBtn.addActionListener(e -> diagnosticZoom = Math.max(0.25, diagnosticZoom - 0.10));
        zoomResetBtn.addActionListener(e -> diagnosticZoom = 1.00);
        zoomInBtn.addActionListener(e -> diagnosticZoom = Math.min(2.00, diagnosticZoom + 0.10));

        zoomControls.add(zoomOutBtn);
        zoomControls.add(zoomResetBtn);
        zoomControls.add(zoomInBtn);

        scrollPane.addMouseWheelListener(e -> {
            if (e.isControlDown()) {
                diagnosticZoom = Math.max(0.25, Math.min(2.00, diagnosticZoom - e.getWheelRotation() * 0.10));
                e.consume();
            }
        });

        debugFrame.add(scrollPane, BorderLayout.CENTER);
        debugFrame.add(zoomControls, BorderLayout.SOUTH);
        debugFrame.setSize(Math.min(1280, captureW + 50), Math.min(720, captureH + 50));
        debugFrame.setVisible(true);
    }

    private void renderDebugView(BufferedImage capture, int[] pixels, List<TrackedObstacle> obstacles,
                                 Point barnaby, int targetY, int captureW, int captureH) {
        Graphics2D g2d = capture.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int scanStartX = (barnaby != null) ? barnaby.x : GAME_LEFT;
        int scanEndX = Math.min(GAME_RIGHT, scanStartX + DANGER_ZONE_WIDTH);

        // Danger Zone boundaries
        g2d.setColor(new Color(0, 255, 255, 120));
        g2d.setStroke(new BasicStroke(2));
        g2d.drawLine(scanStartX - GAME_LEFT, 0, scanStartX - GAME_LEFT, captureH);
        g2d.drawLine(scanEndX - GAME_LEFT, 0, scanEndX - GAME_LEFT, captureH);

        // Score exclusion line
        g2d.setColor(new Color(255, 255, 0, 150));
        g2d.drawLine(0, MAX_BARNABY_Y - GAME_TOP, captureW, MAX_BARNABY_Y - GAME_TOP);

        // Rigid Persistent Hitboxes
        for (TrackedObstacle obs : obstacles) {
            Rectangle r = obs.getRelativeBounds(GAME_LEFT, GAME_TOP);

            g2d.setColor(obs.locked ? new Color(255, 0, 0, 85) : new Color(255, 140, 0, 60));
            g2d.fillRect(r.x, r.y, r.width, r.height);

            g2d.setColor(obs.locked ? new Color(255, 30, 30) : new Color(255, 165, 0));
            g2d.setStroke(obs.locked ? new BasicStroke(2)
                    : new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{6}, 0));
            g2d.drawRect(r.x, r.y, r.width, r.height);

            String label = "#" + obs.id + (obs.isTop ? " [TOP]" : " [BOT]") + (obs.locked ? " [LOCKED]" : "");
            g2d.setFont(new Font("Arial", Font.BOLD, 12));
            int tagY = obs.isTop ? r.y + r.height - 8 : r.y + 16;
            g2d.setColor(Color.BLACK);
            g2d.drawString(label, r.x + 4, tagY + 1);
            g2d.setColor(obs.locked ? Color.YELLOW : Color.WHITE);
            g2d.drawString(label, r.x + 3, tagY);
        }

        // Barnaby Box
        if (barnaby != null) {
            g2d.setColor(Color.MAGENTA);
            g2d.setStroke(new BasicStroke(2));
            g2d.drawRect(barnaby.x - GAME_LEFT - 30, barnaby.y - GAME_TOP - 30, 60, 60);

            // Flight Target Line
            g2d.setColor(Color.GREEN);
            g2d.setStroke(new BasicStroke(3));
            g2d.drawLine(barnaby.x - GAME_LEFT, targetY - GAME_TOP, scanEndX - GAME_LEFT, targetY - GAME_TOP);
        }

        // Click Indicator Banner
        if (System.currentTimeMillis() - lastClickAt < 150) {
            g2d.setColor(Color.RED);
            g2d.fillRect(20, 20, 80, 40);
            g2d.setColor(Color.WHITE);
            g2d.setFont(new Font("Arial", Font.BOLD, 20));
            g2d.drawString("CLICK", 28, 48);
        }

        g2d.dispose();

        int displayW = Math.max(1, (int) (captureW * diagnosticZoom));
        int displayH = Math.max(1, (int) (captureH * diagnosticZoom));
        Image scaledCapture = capture.getScaledInstance(displayW, displayH, Image.SCALE_FAST);
        debugImageLabel.setIcon(new ImageIcon(scaledCapture));
        debugFrame.repaint();
    }
}