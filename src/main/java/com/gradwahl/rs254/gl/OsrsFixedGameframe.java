package com.gradwahl.rs254.gl;

import jagex2.graphics.Pix32;
import jagex2.graphics.PixMap;

import java.awt.Color;
import java.awt.Graphics;

/**
 * OSRS fixed-mode gameframe anchors for the 765x503 client canvas.
 * Keep fixed UI placement here so the rev-254 renderer is not half 2004,
 * half OSRS coordinates spread across Client.java.
 */
public final class OsrsFixedGameframe {

    public static final int CANVAS_W = 765;
    public static final int CANVAS_H = 503;

    public static final int VIEWPORT_X = 1;
    public static final int VIEWPORT_Y = 1;
    public static final int VIEWPORT_W = 512;
    public static final int VIEWPORT_H = 337;

    public static final int CHAT_X = 0;
    public static final int CHAT_Y = 338;
    public static final int CHAT_W = 519;
    public static final int CHAT_H = 142;
    public static final int CHAT_TAB_BACKING_Y = 477;

    public static final int MINIMAP_X = 516;
    public static final int MINIMAP_Y = 0;

    // Stone brick side pieces around the round minimap.
    // 1037_left_minimap_side_edge_29x156.png and 1038_minimap_side_edge_48x160.png.
    public static final int MINIMAP_SIDE_LEFT_X = 516;
    // Side pieces align with the minimap_frame_fixed (172x156) true inner-circle position.
    // The frame's inner circle centre is at sprite (97, 63), NOT the bounding-box centre (86, 78).
    // Frame is drawn at mapback (RM_CX-97, RM_CY-63) = (18, 25).
    // frame left edge in mapback = 18; frame right edge = 18+172 = 190; frame top = 25.
    // side_right starts where frame right edge meets screen: MINIMAP_X + 190 = 706.
    public static final int MINIMAP_SIDE_LEFT_Y = 10;
    public static final int MINIMAP_SIDE_RIGHT_X = 706; // 516 + (RM_CX - 97 + frame.wi) = 516 + 190
    public static final int MINIMAP_SIDE_RIGHT_Y = 10;

    public static final int SIDEBAR_TOP_TABS_X = 516;
    public static final int SIDEBAR_TOP_TABS_Y = 168;
    public static final int SIDEBAR_PANEL_X = 548;
    public static final int SIDEBAR_PANEL_Y = 205;
    public static final int SIDEBAR_BOTTOM_TABS_X = 516;
    public static final int SIDEBAR_BOTTOM_TABS_Y = 466;

    // OSRS side-frame pieces that sit OUTSIDE the inventory panel.
    // They look like extra pillars when drawn alongside the real left/right
    // inventory pillars, so the fixed frame keeps them hidden by default.
    public static final int SIDEBAR_LEFT_OUTER_X = 516;
    public static final int SIDEBAR_LEFT_OUTER_Y = 205;
    public static final int SIDEBAR_RIGHT_OUTER_X = 737;
    public static final int SIDEBAR_RIGHT_OUTER_Y = 205;
    private static final boolean DRAW_EXTRA_SIDEBAR_OUTERS = false;

    // Clear the old 2004 backvmid3/left-side strip area so a stale classic pillar
    // cannot remain to the left of the OSRS inventory frame after login/redraws.
    private static final int LEGACY_SIDEBAR_STRIP_X = 496;
    private static final int LEGACY_SIDEBAR_STRIP_Y = 205;
    private static final int LEGACY_SIDEBAR_STRIP_W = SIDEBAR_PANEL_X - LEGACY_SIDEBAR_STRIP_X;
    private static final int LEGACY_SIDEBAR_STRIP_H = 261;

    public static final int SIDEBAR_LEFT_PILLAR_X = 528;
    public static final int SIDEBAR_LEFT_PILLAR_Y = 205;
    public static final int SIDEBAR_RIGHT_PILLAR_X = 727;
    public static final int SIDEBAR_RIGHT_PILLAR_Y = 205;

    private static final int RIGHT_FRAME_X = 516;
    private static final int RIGHT_FRAME_W = CANVAS_W - RIGHT_FRAME_X;

    // RuneLite/117HD-style application chrome colour used for the non-game
    // backing around the fixed frame. Important: this must be used by the
    // logged-in redraw path too, otherwise the client looks grey on startup
    // but snaps back to black after login when redrawFrame rebuilds the UI.
    public static final int CHROME_RGB = 0x2B2B2B;
    public static final int CHROME_ARGB = 0xFF000000 | CHROME_RGB;
    public static final Color CHROME_COLOR = new Color(CHROME_RGB);
    private OsrsFixedGameframe() {
    }

    public static void fillBlack(Graphics graphics, int x, int y, int w, int h) {
        fillRect(graphics, x, y, w, h, Color.BLACK, 0xFF000000);
    }

    public static boolean active() {
        return OsrsUi.isLoaded();
    }

    public static Pix32 sprite(String name) {
        return OsrsUi.get(name);
    }

    public static void clear(Graphics graphics) {
        // The side frame/chat backing must be chrome grey every time the gameframe
        // is rebuilt. drawBase() is triggered again after login, so leaving this
        // as black makes the top/right minimap edge revert to black in-game.
        fillRect(graphics, RIGHT_FRAME_X, 0, RIGHT_FRAME_W, CANVAS_H, CHROME_COLOR, CHROME_ARGB);
        fillRect(graphics, CHAT_X, CHAT_Y, CHAT_W, CANVAS_H - CHAT_Y, CHROME_COLOR, CHROME_ARGB);
    }

    public static void clearRect(Graphics graphics, int x, int y, int w, int h) {
        fillRect(graphics, x, y, w, h, CHROME_COLOR, CHROME_ARGB);
    }

    private static void fillRect(Graphics graphics, int x, int y, int w, int h, Color color, int argb) {
        if (graphics != null) {
            graphics.setColor(color);
            graphics.fillRect(x, y, w, h);
        }
        if (PixMap.uiBuffer == null) {
            return;
        }
        int left = Math.max(x, 0);
        int top = Math.max(y, 0);
        int right = Math.min(x + w, PixMap.uiWidth);
        int bottom = Math.min(y + h, PixMap.uiHeight);
        for (int row = top; row < bottom; row++) {
            int off = row * PixMap.uiWidth + left;
            for (int col = left; col < right; col++) {
                PixMap.uiBuffer[off++] = argb;
            }
        }
    }

    public static void drawBase(Graphics graphics, PixMap top, PixMap minimapSideLeft, PixMap minimapSideRight, PixMap leftOuter, PixMap rightOuter, PixMap leftPillarA, PixMap leftPillarB, PixMap rightPillar) {
        clear(graphics);
        // 1px chrome gutter along top and left edges of the viewport
        clearRect(graphics, 0, 0, VIEWPORT_X + VIEWPORT_W, VIEWPORT_Y);
        clearRect(graphics, 0, 0, VIEWPORT_X, VIEWPORT_Y + VIEWPORT_H);
        if (top != null) {
            top.draw(0, 0, graphics);
        }

        // Minimap side pieces are now drawn by Client.drawMinimapFrameOverlay()
        // after areaMapback.draw(), so they can use LiveTuner offsets and are not
        // trapped inside the 249x172 mapback clip.

        // Remove the duplicate/outer pillar area before drawing the real inventory
        // frame pillars. This prevents four vertical pillars appearing around the
        // sidebar when the OSRS frame is redrawn after login.
        fillRect(graphics, LEGACY_SIDEBAR_STRIP_X, LEGACY_SIDEBAR_STRIP_Y,
                LEGACY_SIDEBAR_STRIP_W, LEGACY_SIDEBAR_STRIP_H, CHROME_COLOR, CHROME_ARGB);

        // The outer side strips are preserved as assets/tuner targets, but hidden
        // by default because they are the extra duplicate pillars in the screenshot.
        if (DRAW_EXTRA_SIDEBAR_OUTERS && leftOuter != null) {
            leftOuter.draw(SIDEBAR_LEFT_OUTER_Y + LiveTuner.leftOuterOffsetY,
                    SIDEBAR_LEFT_OUTER_X + LiveTuner.leftOuterOffsetX, graphics);
        }
        if (DRAW_EXTRA_SIDEBAR_OUTERS && rightOuter != null) {
            rightOuter.draw(SIDEBAR_RIGHT_OUTER_Y + LiveTuner.rightOuterOffsetY,
                    SIDEBAR_RIGHT_OUTER_X + LiveTuner.rightOuterOffsetX, graphics);
        }

        if (leftPillarA != null) {
            leftPillarA.draw(SIDEBAR_LEFT_PILLAR_Y + LiveTuner.leftPillarAOffsetY,
                    SIDEBAR_LEFT_PILLAR_X + LiveTuner.leftPillarAOffsetX, graphics);
        }
        if (leftPillarB != null) {
            leftPillarB.draw(SIDEBAR_LEFT_PILLAR_Y + LiveTuner.leftPillarBOffsetY,
                    SIDEBAR_LEFT_PILLAR_X + LiveTuner.leftPillarBOffsetX, graphics);
        }
        if (rightPillar != null) {
            rightPillar.draw(SIDEBAR_RIGHT_PILLAR_Y + LiveTuner.rightPillarOffsetY,
                    SIDEBAR_RIGHT_PILLAR_X + LiveTuner.rightPillarOffsetX, graphics);
        }
    }
}
