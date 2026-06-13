package com.gradwahl.rs254.gl;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Single source of truth for the client title bar: constants, hit-testing, and
 * painting.  Both the AWT ViewBox (pre-login) and the GLFW GLRenderer (in-game)
 * delegate here so any button added or restyled is automatically reflected in
 * both windows.
 *
 * Callers supply a Graphics2D whose origin is (0, 0) of the bar and whose clip
 * covers at least (0, 0, w, BAR_H).  No GL or AWT component knowledge is needed
 * here — just Java2D.
 */
public final class ClientTitleBar {

    // ── Geometry ──────────────────────────────────────────────────────────────

    /** Logical height of the title bar in pixels. */
    public static final int BAR_H = 26;

    /** Width of every button in pixels. */
    public static final int BTN_W = 46;

    // ── Button-type constants ──────────────────────────────────────────────────

    public static final int BTN_NONE     = -1;
    public static final int BTN_DISCORD  =  0;
    public static final int BTN_MAXIMIZE =  1;
    public static final int BTN_SIDEBAR  =  2;
    public static final int BTN_MINIMIZE =  3;
    public static final int BTN_CLOSE    =  4;

    // ── Colors ────────────────────────────────────────────────────────────────

    /** Opaque ARGB integer values (0xFF______) kept for callers that need them raw. */
    public static final int COL_BG          = 0xFF2B2B2B;
    public static final int COL_BORDER      = 0xFF1F1F1F;
    public static final int COL_TEXT        = 0xFFD8D8D8;
    public static final int COL_BTN_HOVER   = 0xFF3A3A3A;
    public static final int COL_CLOSE_HOVER = 0xFFC42B1C;

    private static final Color C_BG          = new Color(0x2B2B2B);
    private static final Color C_BORDER      = new Color(0x1F1F1F);
    private static final Color C_TEXT        = new Color(0xD8D8D8);
    private static final Color C_BTN_HOVER   = new Color(0x3A3A3A);
    private static final Color C_CLOSE_HOVER = new Color(0xC42B1C);

    private static final BufferedImage DISCORD_ICON = loadDiscordIcon();

    private static BufferedImage loadDiscordIcon() {
        try {
            return ImageIO.read(ClientTitleBar.class.getResourceAsStream("/discord.png"));
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    private ClientTitleBar() {}

    // ── Hit-testing ───────────────────────────────────────────────────────────

    /**
     * Returns which button type sits at logical X coordinate {@code x} in a bar
     * of total logical width {@code w}.  Returns {@link #BTN_NONE} for the title
     * / drag area.
     */
    public static int hitTest(double x, int w) {
        if (x >= w - BTN_W)         return BTN_CLOSE;
        if (x >= w - BTN_W * 2)     return BTN_MAXIMIZE;
        if (x >= w - BTN_W * 3)     return BTN_MINIMIZE;
        if (x >= w - BTN_W * 4)     return BTN_DISCORD;
        return BTN_NONE;
    }

    // ── Painting ──────────────────────────────────────────────────────────────

    /**
     * Paint the complete title bar into {@code g}.
     *
     * @param g            target Graphics2D — origin must be the bar's top-left
     * @param w            logical width of the bar
     * @param title        window title text drawn on the left
     * @param closeHover   true while the cursor is over the close button
     * @param minimizeHover true while over minimize
     * @param maximizeHover true while over maximize/restore
     * @param sidebarHover  true while over the sidebar toggle
     * @param discordHover  true while over the Discord button
     * @param maximized    true when the window is in the maximised state
     * @param sidebarOpen  true when the sidebar panel is visible
     */
    public static void paint(Graphics2D g, int w, String title,
            boolean closeHover, boolean minimizeHover,
            boolean maximizeHover, boolean sidebarHover, boolean discordHover,
            boolean maximized, boolean sidebarOpen) {

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Background + bottom border
        g.setColor(C_BG);
        g.fillRect(0, 0, w, BAR_H);
        g.setColor(C_BORDER);
        g.fillRect(0, BAR_H - 1, w, 1);

        // Buttons — right-to-left: Close, Maximize, Minimize, Discord
        drawBtn(g, w - BTN_W,         0, BTN_CLOSE,    closeHover,    maximized, sidebarOpen);
        drawBtn(g, w - BTN_W * 2,     0, BTN_MAXIMIZE, maximizeHover, maximized, sidebarOpen);
        drawBtn(g, w - BTN_W * 3,     0, BTN_MINIMIZE, minimizeHover, maximized, sidebarOpen);
        drawBtn(g, w - BTN_W * 4,     0, BTN_DISCORD,  discordHover,  maximized, sidebarOpen);

        // Title text
        g.setColor(C_TEXT);
        g.setFont(new Font("Dialog", Font.PLAIN, 12));
        FontMetrics fm = g.getFontMetrics();
        int textY = (BAR_H - fm.getHeight()) / 2 + fm.getAscent();
        g.drawString(title, 8, textY);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static void drawBtn(Graphics2D g, int x, int y, int type, boolean hover,
            boolean maximized, boolean sidebarOpen) {
        Color bgColor = C_BG;
        if (hover) {
            bgColor = (type == BTN_CLOSE) ? C_CLOSE_HOVER : C_BTN_HOVER;
            g.setColor(bgColor);
            g.fillRect(x, y, BTN_W, BAR_H - 1);
        }

        int cx = x + BTN_W / 2;
        int cy = y + BAR_H / 2;
        g.setColor(C_TEXT);

        switch (type) {
            case BTN_CLOSE:
                g.drawLine(cx - 4, cy - 4, cx + 4, cy + 4);
                g.drawLine(cx + 4, cy - 4, cx - 4, cy + 4);
                break;

            case BTN_MINIMIZE:
                g.drawLine(cx - 5, cy + 4, cx + 5, cy + 4);
                break;

            case BTN_MAXIMIZE:
                if (maximized) {
                    // Restore: two offset squares
                    g.drawRect(cx - 2, cy - 5, 7, 7);
                    g.drawRect(cx - 5, cy - 2, 7, 7);
                } else {
                    g.drawRect(cx - 5, cy - 5, 10, 10);
                }
                break;

            case BTN_SIDEBAR:
                // Right arrow = open sidebar, left arrow = close it
                int[] px, py;
                if (sidebarOpen) {
                    px = new int[]{cx + 3, cx - 3, cx + 3};
                    py = new int[]{cy - 5, cy,     cy + 5};
                } else {
                    px = new int[]{cx - 3, cx + 3, cx - 3};
                    py = new int[]{cy - 5, cy,     cy + 5};
                }
                g.fillPolygon(px, py, 3);
                break;

            case BTN_DISCORD:
                drawDiscordIcon(g, cx, cy, bgColor);
                break;
        }
    }

    private static void drawDiscordIcon(Graphics2D g, int cx, int cy, Color bgColor) {
        if (DISCORD_ICON != null) {
            int size = 16;
            g.drawImage(DISCORD_ICON, cx - size / 2, cy - size / 2, size, size, null);
            return;
        }
        // Fallback: hand-drawn shape
        g.setColor(Color.WHITE);
        g.fillRoundRect(cx - 8, cy - 2, 16, 10, 6, 6);
        g.fillOval(cx - 8, cy - 7, 7, 7);
        g.fillOval(cx + 1, cy - 7, 7, 7);
        g.setColor(bgColor);
        g.fillRect(cx - 1, cy - 8, 2, 5);
        g.fillOval(cx - 6, cy,     4, 4);
        g.fillOval(cx + 2, cy,     4, 4);
    }
}
