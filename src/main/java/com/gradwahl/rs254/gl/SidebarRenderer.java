package com.gradwahl.rs254.gl;

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
