import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.util.Scanner;
import javax.swing.JOptionPane;

public class AutoBarnaby {
    // Constants
    private static final String VERSION = "1.0.0";

    // 1. Boundaries of the tilted game board
    private static final int TOP_Y = 108;
    private static final int BOTTOM_Y = 1020;
    private static final int TOP_LEFT_X = 548;
    private static final int TOP_RIGHT_X = 1371;
    private static final int BOTTOM_LEFT_X = 215;
    private static final int BOTTOM_RIGHT_X = 1704; 

    // 2. Barnaby's Colour (RGB)
    private static final int BARNABY_C_R = 216;
    private static final int BARNABY_C_G = 111;
    private static final int BARNABY_C_B = 66;

    // 3. Engine Tuning

    /** How closely a pixel must match Barnaby's color to be considered him */
    private static final double COLOR_TOLERANCE_PERCENT = 8;

    /** Target line (where we aim) sits 35% of the way down from the ceiling seaweed to the floor seaweed */
    private static final double TARGET_PERCENT = 0.35;

    /** Minimum wait time between clicks (to prevent going too high) */
    private static final long CLICK_COOLDOWN_MS = 350;

    /** The strict vertical gap assumed between the top and bottom seaweed */
    private static final int ASSUMED_GAP_SCREEN_SIZE = 300;

    /** The danger zone - If Barnaby falls this many pixels below the target line, we make him jump, so he does not hit the bottom seaweed */
    private static final int DROP_TOLERANCE = 90;

    // 4. System Variables

    /** The internal "mathematical" grid for the game is a perfect 1000x1000 square, which gets warped to fit the trapezoidal game screen using the LUTs */
    private final int SCREEN_SIZE = 1000;
    private int[][] lutX;
    private int[] lutY;

    /** The last time a click was registered - used to enforce the click cooldown */
    private long lastClickAt = 0L;

    private Robot rb;
    private static final Scanner sc = new Scanner(System.in);

    public static void main(String[] args) {
        try {
            // If the jar is opened without a console, it runs in "javaw".
            // This snippet detects the missing console and forces Windows/Mac to open the program in a terminal window.
            if (System.console() == null && !GraphicsEnvironment.isHeadless()) {
                String os = System.getProperty("os.name").toLowerCase();
                File jarFile = new File(AutoBarnaby.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                
                if (os.contains("win")) {
                    new ProcessBuilder("cmd", "/c", "start", "java", "-jar", jarFile.getAbsolutePath()).start();
                    System.exit(0);
                } else if (os.contains("mac")) { // Not tested on MacOS, but should work
                    new ProcessBuilder("open", "-a", "Terminal", jarFile.getAbsolutePath()).start();
                    System.exit(0);
                }
            }
            // Starts the actual bot logic
            new AutoBarnaby().run();
        } catch (Throwable t) {
            // If anything goes wrong, it dumps the error to a text file so we can read it.
            try {
                File logFile = new File("crash_log.txt");
                PrintWriter pw = new PrintWriter(logFile);
                t.printStackTrace(pw);
                pw.close();
                JOptionPane.showMessageDialog(null, "Program crashed! Check crash_log.txt for details.", "AutoBarnaby Error", JOptionPane.ERROR_MESSAGE);
            } catch (Exception ex) {
                // Ignore if it fails to write the file
            }
            System.exit(1);
        }
    }

    public void run() {
        int terminalWidth = calculateTerminalSize();
        System.out.println("=".repeat(terminalWidth) + "\n" + " ".repeat((terminalWidth - 24) / 2) + "AUTO BARNABY\n" 
                         + " ".repeat((terminalWidth - 34) / 2) + "Swimmy Barnaby Beater\n" 
                         + "=".repeat(terminalWidth));
        System.out.println("\nVersion " + VERSION);
        System.out.println("\nBuilding 3D Look-Up Tables...");
        buildLUTs();
        System.out.println("LUTs Built successfully!");

        try {
            commandPrompt();
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /** Calculates the width of the terminal window, used only for the title */
    private int calculateTerminalSize() {
        try {
            Scanner sc = new Scanner(new ProcessBuilder("cmd", "/c", "mode con").start().getInputStream());
            int terminalWidth = sc.useDelimiter("\\A").next().lines().filter(line -> line.contains("Columns:")).map(line -> line.replaceAll("\\D+", ""))
                    .mapToInt(Integer::parseInt)
                    .findFirst()
                    .orElse(30);
            sc.close();
            return terminalWidth;
        } catch (Exception e) {
            return 30; // Default width if detection fails
        }
    }

    /**
     * Builds the Look-Up Tables (LUTs).
     * This method pre-calculates the 3D perspective warp. It flattens the slanted game board
     * into a perfect 1000x1000 mathematical grid, allowing the bot to scan straight lines
     * internally while actually tracing diagonal lines on your screen.
     */
    private void buildLUTs() {
        lutX = new int[SCREEN_SIZE + 1][SCREEN_SIZE + 1];
        lutY = new int[SCREEN_SIZE + 1];
        
        for (int wy = 0; wy <= SCREEN_SIZE; wy++) {
            double ratioY = wy / (double) SCREEN_SIZE;
            lutY[wy] = TOP_Y + (int)(ratioY * (BOTTOM_Y - TOP_Y));
            
            int leftX = TOP_LEFT_X + (int)(ratioY * (BOTTOM_LEFT_X - TOP_LEFT_X));
            int rightX = TOP_RIGHT_X + (int)(ratioY * (BOTTOM_RIGHT_X - TOP_RIGHT_X));
            
            for (int wx = 0; wx <= SCREEN_SIZE; wx++) {
                lutX[wx][wy] = leftX + (int)((wx / (double) SCREEN_SIZE) * (rightX - leftX));
            }
        }
    }

    private void commandPrompt() throws AWTException {
        System.out.println("\nInstructions:");
        System.out.println("- Type 's' to start AutoBarnaby (Do this before pressing play on Swimmy Barnaby).");
        System.out.println("- Type 'q' to quit.");
        
        while (true) {
            System.out.print("Option:");
            String input = sc.nextLine().trim();
            
            if (input.equalsIgnoreCase("s")) {
                startAutoBarnaby();
            } else if (input.equalsIgnoreCase("q")) {
                System.out.println("Quitting.");
                System.exit(0);
            }
        }
    }

    private boolean isSeaweedPixel(int r, int g, int b) {
        // Seaweed is identified by being significantly greener than it is blue or red
        return (g > b + 5 && g > r + 5 && g > 60);
    }

    private void startAutoBarnaby() throws AWTException {
        rb = new Robot();
        System.out.println("Engine started. Press CTRL+C in the terminal to stop.");

        // Calculate a bounding box that perfectly encloses the game screen trapezoid
        int captureX = Math.min(TOP_LEFT_X, BOTTOM_LEFT_X);
        int captureY = TOP_Y;
        int captureW = Math.max(TOP_RIGHT_X, BOTTOM_RIGHT_X) - captureX;
        int captureH = BOTTOM_Y - TOP_Y;
        Rectangle gameBox = new Rectangle(captureX, captureY, captureW, captureH);

        double tol = 255 * (COLOR_TOLERANCE_PERCENT / 100.0);

        // MAIN GAME LOOP
        while (true) {
            BufferedImage capture = rb.createScreenCapture(gameBox);
            int[] pixels = capture.getRGB(0, 0, captureW, captureH, null, 0, captureW);

            // 1. Locate Barnaby
            int barnabyScreenX = -1;
            int barnabyScreenY = -1;

            // Only scan the left 30% of the screen for Barnaby
            for (int wy = 0; wy < SCREEN_SIZE; wy += 5) {
                for (int wx = 0; wx < 300; wx += 5) {
                    int sx = lutX[wx][wy] - captureX;
                    int sy = lutY[wy] - captureY;
                    int rgb = pixels[sy * captureW + sx];
                    
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    
                    if (Math.abs(r - BARNABY_C_R) <= tol && Math.abs(g - BARNABY_C_G) <= tol && Math.abs(b - BARNABY_C_B) <= tol) {
                        barnabyScreenX = wx;
                        barnabyScreenY = wy;
                    }
                }
            }

            int currentTargetScreenY = 450; // Default safe altitude if no obstacles exist

            // 2. Danger Zone Scanning
            if (barnabyScreenX != -1) {
                // The Danger Zone starts slightly ahead of Barnaby to ignore foreground obstacles
                int scanStartScreenX = barnabyScreenX + 20; 
                int scanEndScreenX = Math.min(SCREEN_SIZE, scanStartScreenX + 320); 

                int worstTopScreenY = 0;
                int worstBottomScreenY = SCREEN_SIZE;
                boolean foundTop = false;
                boolean foundBottom = false;

                // Scan vertical columns within the Danger Zone
                for (int wx = scanStartScreenX; wx <= scanEndScreenX; wx += 10) {
                    
                    // Hugging Rule for Top Seaweed
                    // We only consider top seaweed as a threat if it connects to the top of the game screen, to avoid miscalculating.
                    boolean connectsToRoof = false;
                    for (int wy = 15; wy <= 45; wy += 5) { 
                        int sx = lutX[wx][wy] - captureX;
                        int sy = lutY[wy] - captureY;
                        int rgb = pixels[sy * captureW + sx];
                        if (isSeaweedPixel((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF)) { connectsToRoof = true; break; }
                    }

                    if (connectsToRoof) {
                        int localTop = 15;
                        for (int wy = 15; wy < SCREEN_SIZE - 50; wy += 5) {
                            int sx = lutX[wx][wy] - captureX;
                            int sy = lutY[wy] - captureY;
                            int rgb = pixels[sy * captureW + sx];
                            if (isSeaweedPixel((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF)) {
                                localTop = wy; 
                                foundTop = true;
                            } else if (foundTop && wy > localTop + 150) { 
                                break; 
                            }
                        }
                        worstTopScreenY = Math.max(worstTopScreenY, localTop);
                    }
                    
                    // Hugging Rule for Bottom Seaweed (check if connects to floor)
                    boolean connectsToFloor = false;
                    for (int wy = SCREEN_SIZE - 15; wy >= SCREEN_SIZE - 45; wy -= 5) { 
                        int sx = lutX[wx][wy] - captureX;
                        int sy = lutY[wy] - captureY;
                        int rgb = pixels[sy * captureW + sx];
                        if (isSeaweedPixel((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF)) { connectsToFloor = true; break; }
                    }

                    if (connectsToFloor) {
                        int localBottom = SCREEN_SIZE - 15;
                        for (int wy = SCREEN_SIZE - 15; wy > 50; wy -= 5) {
                            int sx = lutX[wx][wy] - captureX;
                            int sy = lutY[wy] - captureY;
                            int rgb = pixels[sy * captureW + sx];
                            if (isSeaweedPixel((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF)) {
                                localBottom = wy; 
                                foundBottom = true;
                            } else if (foundBottom && wy < localBottom - 150) { 
                                break; 
                            }
                        }
                        worstBottomScreenY = Math.min(worstBottomScreenY, localBottom);
                    }
                }

                // 3. Single-Anchor Target Calculation
                if (foundTop && foundBottom) {
                    currentTargetScreenY = worstTopScreenY + (int)((worstBottomScreenY - worstTopScreenY) * TARGET_PERCENT);
                } else if (foundTop) {
                    currentTargetScreenY = worstTopScreenY + (int)(ASSUMED_GAP_SCREEN_SIZE * TARGET_PERCENT);
                } else if (foundBottom) {
                    currentTargetScreenY = worstBottomScreenY - (int)(ASSUMED_GAP_SCREEN_SIZE * (1.0 - TARGET_PERCENT));
                }
            }

            currentTargetScreenY = Math.max(10, Math.min(SCREEN_SIZE - 10, currentTargetScreenY));

            // 4. Autopilot Execution
            long now = System.currentTimeMillis();
            if (barnabyScreenY > 0) {
                boolean shouldClick = (currentTargetScreenY > 0 && barnabyScreenY > currentTargetScreenY + DROP_TOLERANCE) || (barnabyScreenY > 920);
                
                if (shouldClick && now - lastClickAt >= CLICK_COOLDOWN_MS) {
                    clickBarnaby();
                    lastClickAt = now;
                }
            }
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
}