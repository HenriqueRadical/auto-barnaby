import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Scanner;
import javax.swing.*;

public class AutoBarnabyVisualizer {
    
    private static final String VERSION = "1.0.3-FROZEN-HITBOXES";

    // 1. Boundaries of the square game board
    private static final int GAME_LEFT = 463;
    private static final int GAME_TOP = 53;
    private static final int GAME_RIGHT = 1456;
    private static final int GAME_BOTTOM = 1024;

    // 2. Barnaby's Colour
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

    private long lastClickAt = 0L;
    private boolean isRunning = false;
    private volatile double diagnosticZoom = 0.60;
    private static final Scanner sc = new Scanner(System.in);

    private int nextObstacleId = 1;

    // --- OBJECT PERMANENCE DATA MODEL ---
    private static class RawBlock {
        boolean isTop;
        int minX;
        int maxX;
        int extremeY;

        RawBlock(boolean isTop, int minX, int maxX, int extremeY) {
            this.isTop = isTop;
            this.minX = minX;
            this.maxX = maxX;
            this.extremeY = extremeY;
        }
    }

    private static class TrackedObstacle {
        int id;
        boolean isTop;
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

        Rectangle getRelativeBounds(int captureOriginX, int captureOriginY) {
            int relX = x - captureOriginX;
            if (isTop) {
                int relY = GAME_TOP - captureOriginY;
                int h = extremeY - GAME_TOP;
                return new Rectangle(relX, relY, width, Math.max(10, h));
            } else {
                int relY = extremeY - captureOriginY;
                int h = GAME_BOTTOM - extremeY;
                return new Rectangle(relX, relY, width, Math.max(10, h));
            }
        }
    }

    public static void main(String[] args) {
        new AutoBarnabyVisualizer().run();
    }

    public void run() {
        System.out.println("==================================================");
        System.out.println("      AUTO BARNABY - RIGID HITBOX MONITOR         ");
        System.out.println("==================================================");
        System.out.println("\nVersion " + VERSION);
        
        try {
            commandPrompt();
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private void commandPrompt() {
        System.out.println("\nInstructions:");
        System.out.println("- Type 's' to start the Live Vision Monitor.");
        System.out.println("- Type 'q' to quit.");
        
        while (true) {
            System.out.print("Option:");
            String input = sc.nextLine().trim();
            
            if (input.equalsIgnoreCase("s")) {
                startLiveMonitor();
            } else if (input.equalsIgnoreCase("q")) {
                System.out.println("Quitting.");
                System.exit(0);
            }
        }
    }

    private boolean isSeaweedPixel(int r, int g, int b) {
        return (g > b + 5 && g > r + 5 && g > 60);
    }

    private void startLiveMonitor() {
        if (isRunning) return;
        isRunning = true;
        
        System.out.println("Booting up Live Vision Monitor... (Close the window to stop)");

        JFrame frame = new JFrame("AutoBarnaby 2D - Rigid Frozen Hitboxes");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        
        int captureW = GAME_RIGHT - GAME_LEFT;
        int captureH = GAME_BOTTOM - GAME_TOP;
        
        JLabel imageLabel = new JLabel();
        JScrollPane scrollPane = new JScrollPane(imageLabel);
        JPanel zoomControls = new JPanel();
        JButton zoomOutButton = new JButton("-");
        JButton zoomResetButton = new JButton("100%");
        JButton zoomInButton = new JButton("+");

        zoomOutButton.addActionListener(e -> diagnosticZoom = Math.max(0.25, diagnosticZoom - 0.10));
        zoomResetButton.addActionListener(e -> diagnosticZoom = 1.00);
        zoomInButton.addActionListener(e -> diagnosticZoom = Math.min(2.00, diagnosticZoom + 0.10));
        zoomControls.add(zoomOutButton);
        zoomControls.add(zoomResetButton);
        zoomControls.add(zoomInButton);

        scrollPane.addMouseWheelListener(e -> {
            if (e.isControlDown()) {
                diagnosticZoom = Math.max(0.25, Math.min(2.00,
                        diagnosticZoom - e.getWheelRotation() * 0.10));
                e.consume();
            }
        });

        frame.add(scrollPane, BorderLayout.CENTER);
        frame.add(zoomControls, BorderLayout.SOUTH);
        frame.setSize(Math.min(1280, captureW + 50), Math.min(720, captureH + 50)); 
        frame.setVisible(true);

        new Thread(() -> {
            try {
                Robot rb = new Robot();
                Rectangle gameBox = new Rectangle(GAME_LEFT, GAME_TOP, captureW, captureH);
                double tol = 255 * (COLOR_TOLERANCE_PERCENT / 100.0);

                List<TrackedObstacle> trackedObstacles = new ArrayList<>();

                while (frame.isVisible()) {
                    long loopStart = System.currentTimeMillis();
                    
                    BufferedImage capture = rb.createScreenCapture(gameBox);
                    Graphics2D g2d = capture.createGraphics();
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    int[] pixels = capture.getRGB(0, 0, captureW, captureH, null, 0, captureW);

                    // 1. Locate Barnaby
                    int barnabyScreenX = -1;
                    int barnabyScreenY = -1;

                    for (int screenY = Math.max(GAME_TOP, MAX_BARNABY_Y); screenY < GAME_BOTTOM; screenY += 5) {
                        for (int screenX = GAME_LEFT; screenX < GAME_LEFT + (captureW * 30 / 100); screenX += 5) {
                            int sx = screenX - GAME_LEFT;
                            int sy = screenY - GAME_TOP;
                            int rgb = pixels[sy * captureW + sx];
                            
                            int r = (rgb >> 16) & 0xFF;
                            int g = (rgb >> 8) & 0xFF;
                            int b = rgb & 0xFF;
                            
                            if (Math.abs(r - BARNABY_C_R) <= tol && Math.abs(g - BARNABY_C_G) <= tol && Math.abs(b - BARNABY_C_B) <= tol) {
                                barnabyScreenX = screenX;
                                barnabyScreenY = screenY;
                            }
                        }
                    }

                    // 2. Scan Screen Columns for Seaweed
                    List<RawBlock> rawBlocks = new ArrayList<>();
                    int scanStep = 10;

                    int currentTopMinX = -1, currentTopMaxX = -1, currentTopMaxY = -1;
                    int currentBotMinX = -1, currentBotMaxX = -1, currentBotMinY = Integer.MAX_VALUE;

                    for (int screenX = GAME_LEFT; screenX <= GAME_RIGHT; screenX += scanStep) {
                        
                        // Check Top Seaweed (must hug roof)
                        boolean connectsToRoof = false;
                        for (int screenY = GAME_TOP + 15; screenY <= GAME_TOP + 45; screenY += 5) { 
                            int sx = screenX - GAME_LEFT;
                            int sy = screenY - GAME_TOP;
                            int rgb = pixels[sy * captureW + sx];
                            if (isSeaweedPixel((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF)) {
                                connectsToRoof = true;
                                break;
                            }
                        }

                        int colTopY = -1;
                        if (connectsToRoof) {
                            colTopY = GAME_TOP + 15;
                            for (int screenY = GAME_TOP + 15; screenY < GAME_BOTTOM - 50; screenY += 5) {
                                int sx = screenX - GAME_LEFT;
                                int sy = screenY - GAME_TOP;
                                int rgb = pixels[sy * captureW + sx];
                                if (isSeaweedPixel((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF)) {
                                    colTopY = screenY;
                                } else if (screenY > colTopY + 150) { 
                                    break; 
                                }
                            }
                        }

                        // Check Bottom Seaweed (must hug floor)
                        boolean connectsToFloor = false;
                        for (int screenY = GAME_BOTTOM - 15; screenY >= GAME_BOTTOM - 45; screenY -= 5) { 
                            int sx = screenX - GAME_LEFT;
                            int sy = screenY - GAME_TOP;
                            int rgb = pixels[sy * captureW + sx];
                            if (isSeaweedPixel((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF)) {
                                connectsToFloor = true;
                                break;
                            }
                        }

                        int colBotY = -1;
                        if (connectsToFloor) {
                            colBotY = GAME_BOTTOM - 15;
                            for (int screenY = GAME_BOTTOM - 15; screenY > GAME_TOP + 50; screenY -= 5) {
                                int sx = screenX - GAME_LEFT;
                                int sy = screenY - GAME_TOP;
                                int rgb = pixels[sy * captureW + sx];
                                if (isSeaweedPixel((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF)) {
                                    colBotY = screenY;
                                } else if (screenY < colBotY - 150) { 
                                    break; 
                                }
                            }
                        }

                        // Group Top Blocks into contiguous clusters
                        if (colTopY != -1) {
                            if (currentTopMinX == -1) {
                                currentTopMinX = screenX;
                                currentTopMaxY = colTopY;
                            }
                            currentTopMaxX = screenX + scanStep;
                            currentTopMaxY = Math.max(currentTopMaxY, colTopY);
                        } else if (currentTopMinX != -1) {
                            if (currentTopMaxX - currentTopMinX >= 15) {
                                rawBlocks.add(new RawBlock(true, currentTopMinX, currentTopMaxX, currentTopMaxY));
                            }
                            currentTopMinX = -1;
                        }

                        // Group Bottom Blocks into contiguous clusters
                        if (colBotY != -1) {
                            if (currentBotMinX == -1) {
                                currentBotMinX = screenX;
                                currentBotMinY = colBotY;
                            }
                            currentBotMaxX = screenX + scanStep;
                            currentBotMinY = Math.min(currentBotMinY, colBotY);
                        } else if (currentBotMinX != -1) {
                            if (currentBotMaxX - currentBotMinX >= 15) {
                                rawBlocks.add(new RawBlock(false, currentBotMinX, currentBotMaxX, currentBotMinY));
                            }
                            currentBotMinX = -1;
                        }
                    }

                    if (currentTopMinX != -1 && (currentTopMaxX - currentTopMinX >= 15)) {
                        rawBlocks.add(new RawBlock(true, currentTopMinX, currentTopMaxX, currentTopMaxY));
                    }
                    if (currentBotMinX != -1 && (currentBotMaxX - currentBotMinX >= 15)) {
                        rawBlocks.add(new RawBlock(false, currentBotMinX, currentBotMaxX, currentBotMinY));
                    }

                    // 3. Object Permanence & Dimension Locking
                    boolean[] rawMatched = new boolean[rawBlocks.size()];

                    for (TrackedObstacle tracked : trackedObstacles) {
                        int bestMatchIdx = -1;
                        int bestOverlapDist = Integer.MAX_VALUE;

                        for (int i = 0; i < rawBlocks.size(); i++) {
                            if (rawMatched[i]) continue;
                            RawBlock raw = rawBlocks.get(i);

                            if (raw.isTop == tracked.isTop) {
                                int dist = Math.abs(raw.minX - tracked.x);
                                boolean overlaps = (raw.minX <= tracked.x + tracked.width + 40) &&
                                                   (raw.maxX >= tracked.x - 40);

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
                                // Still entering or establishing: expand to capture true outer bounds
                                tracked.x = matchedRaw.minX;
                                tracked.width = Math.max(tracked.width, matchedRaw.maxX - matchedRaw.minX);

                                if (tracked.isTop) {
                                    tracked.extremeY = Math.max(tracked.extremeY, matchedRaw.extremeY);
                                } else {
                                    tracked.extremeY = Math.min(tracked.extremeY, matchedRaw.extremeY);
                                }

                                // Lock once the rear edge has fully cleared the spawn zone
                                boolean fullyInside = matchedRaw.maxX < (GAME_RIGHT - 35);
                                if (fullyInside && tracked.framesTracked >= 4 && tracked.width >= 30) {
                                    tracked.locked = true;
                                }
                            } else {
                                // PERMANENTLY LOCKED: Width and height never change again.
                                // Only X advances left with the obstacle base.
                                tracked.x = matchedRaw.minX;
                            }
                        } else {
                            // Coast leftward if temporarily lost
                            tracked.framesLost++;
                            tracked.x -= 4;
                        }
                    }

                    // Register new incoming obstacles
                    for (int i = 0; i < rawBlocks.size(); i++) {
                        if (!rawMatched[i]) {
                            RawBlock raw = rawBlocks.get(i);
                            if (raw.maxX - raw.minX >= 20) {
                                trackedObstacles.add(new TrackedObstacle(nextObstacleId++, raw.isTop, raw.minX, raw.maxX - raw.minX, raw.extremeY));
                            }
                        }
                    }

                    // Remove off-screen obstacles
                    Iterator<TrackedObstacle> it = trackedObstacles.iterator();
                    while (it.hasNext()) {
                        TrackedObstacle to = it.next();
                        if (to.framesLost > 8 || to.x + to.width < GAME_LEFT - 20) {
                            it.remove();
                        }
                    }

                    // 4. Stable Target Calculation
                    int currentTargetScreenY = 450;
                    int scanStartScreenX = (barnabyScreenX != -1) ? barnabyScreenX : GAME_LEFT;
                    int scanEndScreenX = Math.min(GAME_RIGHT, scanStartScreenX + 400);

                    int worstTopScreenY = GAME_TOP;
                    int worstBottomScreenY = GAME_BOTTOM;
                    boolean foundTop = false;
                    boolean foundBottom = false;

                    for (TrackedObstacle obs : trackedObstacles) {
                        if (obs.x + obs.width >= scanStartScreenX && obs.x <= scanEndScreenX) {
                            if (obs.isTop) {
                                worstTopScreenY = Math.max(worstTopScreenY, obs.extremeY);
                                foundTop = true;
                            } else {
                                worstBottomScreenY = Math.min(worstBottomScreenY, obs.extremeY);
                                foundBottom = true;
                            }
                        }
                    }

                    if (foundTop && foundBottom) {
                        currentTargetScreenY = worstTopScreenY + (int)((worstBottomScreenY - worstTopScreenY) * TARGET_PERCENT);
                    } else if (foundTop) {
                        currentTargetScreenY = worstTopScreenY + (int)(ASSUMED_GAP_SCREEN_SIZE * TARGET_PERCENT);
                    } else if (foundBottom) {
                        currentTargetScreenY = worstBottomScreenY - (int)(ASSUMED_GAP_SCREEN_SIZE * (1.0 - TARGET_PERCENT));
                    }

                    currentTargetScreenY = Math.max(GAME_TOP + 10, Math.min(GAME_BOTTOM - 10, currentTargetScreenY));

                    // 5. Render Diagnostics
                    g2d.setColor(new Color(0, 255, 255, 120));
                    g2d.setStroke(new BasicStroke(2));
                    g2d.drawLine(scanStartScreenX - GAME_LEFT, 0, scanStartScreenX - GAME_LEFT, captureH);
                    g2d.drawLine(scanEndScreenX - GAME_LEFT, 0, scanEndScreenX - GAME_LEFT, captureH);

                    g2d.setColor(new Color(255, 255, 0, 150));
                    g2d.drawLine(0, MAX_BARNABY_Y - GAME_TOP, captureW, MAX_BARNABY_Y - GAME_TOP);

                    // Draw Frozen Hitboxes
                    for (TrackedObstacle obs : trackedObstacles) {
                        Rectangle r = obs.getRelativeBounds(GAME_LEFT, GAME_TOP);

                        // Shading: Locked boxes have a solid outline; unlocking boxes have a dashed outline
                        g2d.setColor(obs.locked ? new Color(255, 0, 0, 85) : new Color(255, 140, 0, 60));
                        g2d.fillRect(r.x, r.y, r.width, r.height);

                        g2d.setColor(obs.locked ? new Color(255, 30, 30) : new Color(255, 165, 0));
                        g2d.setStroke(obs.locked ? new BasicStroke(2) : new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{6}, 0));
                        g2d.drawRect(r.x, r.y, r.width, r.height);

                        // Label
                        String label = "#" + obs.id + (obs.isTop ? " [TOP]" : " [BOT]") + (obs.locked ? " [LOCKED]" : "");
                        g2d.setFont(new Font("Arial", Font.BOLD, 12));
                        int tagY = obs.isTop ? r.y + r.height - 8 : r.y + 16;
                        g2d.setColor(Color.BLACK);
                        g2d.drawString(label, r.x + 4, tagY + 1);
                        g2d.setColor(obs.locked ? Color.YELLOW : Color.WHITE);
                        g2d.drawString(label, r.x + 3, tagY);
                    }

                    // Draw Barnaby Tracking
                    if (barnabyScreenX != -1) {
                        g2d.setColor(Color.MAGENTA);
                        g2d.setStroke(new BasicStroke(2));
                        g2d.drawRect(barnabyScreenX - GAME_LEFT - 30, barnabyScreenY - GAME_TOP - 30, 60, 60);
                    }

                    // Draw Target Line
                    if (barnabyScreenX != -1) {
                        g2d.setColor(Color.GREEN);
                        g2d.setStroke(new BasicStroke(3));
                        g2d.drawLine(barnabyScreenX - GAME_LEFT, currentTargetScreenY - GAME_TOP,
                                     scanEndScreenX - GAME_LEFT, currentTargetScreenY - GAME_TOP);
                    }

                    // Simulator Click Engine
                    long now = System.currentTimeMillis();
                    if (barnabyScreenY > 0) {
                        boolean shouldClick = (currentTargetScreenY > 0 && barnabyScreenY > currentTargetScreenY + DROP_TOLERANCE) || 
                                              (barnabyScreenY > GAME_BOTTOM - 100);
                        
                        if (shouldClick && now - lastClickAt >= CLICK_COOLDOWN_MS) {
                            lastClickAt = now;
                        }
                    }

                    if (now - lastClickAt < 150) {
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
                    imageLabel.setIcon(new ImageIcon(scaledCapture));
                    frame.repaint();

                    long loopTime = System.currentTimeMillis() - loopStart;
                    if (loopTime < 40) Thread.sleep(40 - loopTime);
                }
            } catch (Exception e) {
                System.out.println("Live Monitor crashed: " + e.getMessage());
                e.printStackTrace();
            }
            isRunning = false;
            System.out.println("Live Vision Monitor closed.");
        }).start();
    }
}