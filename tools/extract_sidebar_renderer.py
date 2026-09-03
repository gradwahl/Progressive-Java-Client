from pathlib import Path

path = Path('src/main/java/com/gradwahl/rs254/gl/GLRenderer.java')
src = path.read_text(encoding='utf-8')


def replace_once(old, new, label):
    global src
    count = src.count(old)
    if count != 1:
        raise RuntimeError(f'{label}: expected exactly 1 match, found {count}')
    src = src.replace(old, new, 1)


replace_once(
    '    // Native-resolution sidebar — rendered via Java2D at physical screen pixels\n'
    '    private java.awt.image.BufferedImage sidebarNativeBuf;\n'
    '    private java.nio.IntBuffer           sidebarNativeDirect;\n'
    '    private int                          sidebarNativeTex;\n'
    '    private int                          sidebarNativeW, sidebarNativeH;\n'
    '    private java.awt.Graphics2D          sg;  // set during drawSidebar(), null otherwise\n',
    '    // Native-resolution sidebar surface; feature painters still live here for now.\n'
    '    private final SidebarRenderer sidebarRenderer;\n'
    '    private java.awt.Graphics2D sg;  // set while sidebar/world-map Java2D painters run\n',
    'sidebar surface fields')

replace_once(
    '        this.maxUiW = screenW + SIDEBAR_PANEL_W + SIDEBAR_RAIL_W;\n'
    '        this.uiRenderer = new GlUiRenderer(maxUiW, screenH);\n',
    '        this.maxUiW = screenW + SIDEBAR_PANEL_W + SIDEBAR_RAIL_W;\n'
    '        this.uiRenderer = new GlUiRenderer(maxUiW, screenH);\n'
    '        this.sidebarRenderer = new SidebarRenderer(uiRenderer);\n',
    'sidebar renderer construction')

replace_once(
    '        uiRenderer.init();\n\n'
    '        sidebarNativeTex = glGenTextures();\n'
    '        glBindTexture(GL_TEXTURE_2D, sidebarNativeTex);\n'
    '        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);\n'
    '        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);\n\n',
    '        uiRenderer.init();\n        sidebarRenderer.init();\n\n',
    'sidebar texture initialization')

replace_once(
    '        if (sidebarNativeTex != 0) glDeleteTextures(sidebarNativeTex);\n',
    '        sidebarRenderer.dispose();\n',
    'sidebar texture disposal')
replace_once(
    '        if (sidebarNativeDirect != null) MemoryUtil.memFree(sidebarNativeDirect);\n',
    '',
    'sidebar direct buffer disposal')

sidebar_start = src.index('    private void drawSidebarNative(int physX, int physY, int physW, int physH, double scale,')
fullscreen_start = src.index('    private void drawWorldMapFullscreenNative(int physW, int physH) {', sidebar_start)
sidebar_method = '''    private void drawSidebarNative(int physX, int physY, int physW, int physH, double scale,
                                   double logicalStartX) {
        sidebarRenderer.render(physX, physY, physW, physH, scale, scale, logicalStartX, 0, g -> {
            g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                    java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(java.awt.RenderingHints.KEY_FRACTIONALMETRICS,
                    java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            java.awt.Graphics2D old = sg;
            sg = g;
            try {
                drawSidebar();
            } finally {
                sg = old;
            }
        });
    }

'''
src = src[:sidebar_start] + sidebar_method + src[fullscreen_start:]

fullscreen_start = src.index('    private void drawWorldMapFullscreenNative(int physW, int physH) {')
floating_start = src.index('    private void drawWorldMapFloatingNative() {', fullscreen_start)
fullscreen_method = '''    private void drawWorldMapFullscreenNative(int physW, int physH) {
        sidebarRenderer.render(0, 0, physW, physH,
                (double) physW / screenW, (double) physH / screenH, 0, 0, g -> {
            g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            java.awt.Graphics2D old = sg;
            sg = g;
            try {
                fillUiRect(0, 0, screenW, screenH, 0xFF262626);
                drawUiTextVerticallyCentered("WORLD MAP", 12, 4, 20, 2, 0xFFDCDCDC);
                drawWorldMapHeaderControls(0, 0, screenW);
                drawSidebarHeaderCloseButton(0, screenW);
                fillUiRect(0, WORLD_MAP_HEADER_H - 1, screenW, 1, 0xFF363636);
                drawWorldMapView(0, WORLD_MAP_HEADER_H, screenW, screenH - WORLD_MAP_HEADER_H);
            } finally {
                sg = old;
            }
        });
    }

'''
src = src[:fullscreen_start] + fullscreen_method + src[floating_start:]

floating_start = src.index('    private void drawWorldMapFloatingNative() {')
load_icons_start = src.index('    private void loadTabIcons() {', floating_start)
floating_method = '''    private void drawWorldMapFloatingNative() {
        int[] rect = computeLogicalRectInFramebuffer(vpDrawX, vpDrawY, vpW, vpH);
        if (rect == null || rect[2] <= 0 || rect[3] <= 0) {
            return;
        }
        int physW = rect[2];
        int physH = rect[3];
        sidebarRenderer.render(rect[0], rect[1], physW, physH,
                (double) physW / vpW, (double) physH / vpH, 0, 0, g -> {
            g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            java.awt.Graphics2D old = sg;
            sg = g;
            try {
                fillUiRect(0, 0, vpW, vpH, 0xF0262626);
                drawUiTextVerticallyCentered("WORLD MAP", 12, 4, 20, 2, 0xFFDCDCDC);
                drawWorldMapHeaderControls(0, 0, vpW);
                drawSidebarHeaderCloseButton(0, vpW);
                fillUiRect(0, WORLD_MAP_HEADER_H - 1, vpW, 1, 0xFF363636);
                drawWorldMapView(0, WORLD_MAP_HEADER_H, vpW, Math.max(0, vpH - WORLD_MAP_HEADER_H));
            } finally {
                sg = old;
            }
        });
    }

'''
src = src[:floating_start] + floating_method + src[load_icons_start:]

forbidden = ['sidebarNativeBuf', 'sidebarNativeDirect', 'sidebarNativeTex', 'sidebarNativeW', 'sidebarNativeH']
leftovers = [token for token in forbidden if token in src]
if leftovers:
    raise RuntimeError(f'GLRenderer still owns sidebar surface tokens: {leftovers}')

path.write_text(src, encoding='utf-8')

sidebar = Path('src/main/java/com/gradwahl/rs254/gl/SidebarRenderer.java')
sidebar.write_text('''package com.gradwahl.rs254.gl;

import org.lwjgl.system.MemoryUtil;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33.*;

/** Owns the native Java2D/GL upload surface used by the sidebar and map overlays. */
final class SidebarRenderer {
    @FunctionalInterface
    interface Painter {
        void paint(Graphics2D g);
    }

    private final GlUiRenderer uiRenderer;
    private BufferedImage buffer;
    private IntBuffer direct;
    private int texture;
    private int width;
    private int height;

    SidebarRenderer(GlUiRenderer uiRenderer) {
        this.uiRenderer = uiRenderer;
    }

    void init() {
        texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    }

    void render(int physX, int physY, int physW, int physH,
                double scaleX, double scaleY, double logicalStartX, double logicalStartY,
                Painter painter) {
        if (physW <= 0 || physH <= 0) return;
        ensureSize(physW, physH);

        Graphics2D g = buffer.createGraphics();
        try {
            g.setBackground(new Color(0, 0, 0, 0));
            g.clearRect(0, 0, physW, physH);
            g.scale(scaleX, scaleY);
            g.translate(-logicalStartX, -logicalStartY);
            painter.paint(g);
        } finally {
            g.dispose();
        }

        int[] pixels = ((DataBufferInt) buffer.getRaster().getDataBuffer()).getData();
        direct.clear();
        direct.put(pixels);
        direct.flip();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, physW, physH,
                GL_BGRA, GL_UNSIGNED_BYTE, direct);

        uiRenderer.drawBound(physX, physY, physW, physH, 0f, 1f);
        uiRenderer.bindGameTexture();
    }

    void dispose() {
        if (buffer != null) {
            buffer.flush();
            buffer = null;
        }
        if (direct != null) {
            MemoryUtil.memFree(direct);
            direct = null;
        }
        if (texture != 0) {
            glDeleteTextures(texture);
            texture = 0;
        }
        width = 0;
        height = 0;
    }

    private void ensureSize(int newWidth, int newHeight) {
        if (newWidth == width && newHeight == height && buffer != null && direct != null) return;
        if (buffer != null) buffer.flush();
        if (direct != null) MemoryUtil.memFree(direct);

        buffer = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
        direct = MemoryUtil.memAllocInt(newWidth * newHeight);
        width = newWidth;
        height = newHeight;

        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, newWidth, newHeight, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
    }
}
''', encoding='utf-8')
