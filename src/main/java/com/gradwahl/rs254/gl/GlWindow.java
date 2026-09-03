package com.gradwahl.rs254.gl;

import org.lwjgl.glfw.GLFWCharCallbackI;
import org.lwjgl.glfw.GLFWCursorPosCallbackI;
import org.lwjgl.glfw.GLFWFramebufferSizeCallbackI;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.glfw.GLFWKeyCallbackI;
import org.lwjgl.glfw.GLFWMouseButtonCallbackI;
import org.lwjgl.glfw.GLFWScrollCallbackI;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.glfw.GLFWWindowIconifyCallbackI;
import org.lwjgl.glfw.GLFWWindowMaximizeCallbackI;
import org.lwjgl.glfw.GLFWWindowSizeCallbackI;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Owns the GLFW window and OpenGL context lifecycle.
 *
 * Input meaning and client layout remain in GLRenderer. This class only owns the
 * native window plus raw GLFW callback registration so those policies can be
 * extracted separately without changing behaviour.
 */
final class GlWindow {
    private long handle;

    void init(int outputW, int outputH, String title) {
        glfwSetErrorCallback((error, description) ->
                System.err.println("[GLFW ERROR] " + error + ": "
                        + org.lwjgl.glfw.GLFWErrorCallback.getDescription(description)));
        if (!glfwInit()) throw new IllegalStateException("GLFW init failed");

        // Query the primary monitor's content (DPI) scale so the initial window
        // size produces a framebuffer close to native RS2 physical pixel dimensions.
        // Without this, a 175% DPI display would create a much larger framebuffer
        // for the logical client window and upscale the UI.
        glfwDefaultWindowHints();
        float[] xscale = {1f}, yscale = {1f};
        long primaryMonitor = glfwGetPrimaryMonitor();
        if (primaryMonitor != NULL) {
            glfwGetMonitorContentScale(primaryMonitor, xscale, yscale);
        }
        int initW = Math.max(1, Math.round(outputW / xscale[0]));
        int initH = Math.max(1, Math.round(outputH / yscale[0]));

        handle = tryCreateWindow(initW, initH, title);
        if (handle == NULL) {
            throw new RuntimeException(
                    "GLFW window creation failed — OpenGL 3.3 is required.\n" +
                    "Update your GPU drivers, or on a VM enable 3D acceleration.\n" +
                    "On Windows without a GPU, install Mesa (opengl32.dll) and re-run.");
        }
        setWindowIcon();

        glfwMakeContextCurrent(handle);
        glfwSwapInterval(1);
        GL.createCapabilities();
    }

    long handle() {
        return handle;
    }

    int[] windowSize() {
        int[] width = new int[1];
        int[] height = new int[1];
        glfwGetWindowSize(handle, width, height);
        return new int[]{width[0], height[0]};
    }

    int[] framebufferSize() {
        int[] width = new int[1];
        int[] height = new int[1];
        glfwGetFramebufferSize(handle, width, height);
        return new int[]{width[0], height[0]};
    }

    void showCentered(Runnable prepareFrame) {
        long monitor = glfwGetPrimaryMonitor();
        int x = Integer.MIN_VALUE;
        int y = Integer.MIN_VALUE;
        if (monitor != NULL) {
            GLFWVidMode vidMode = glfwGetVideoMode(monitor);
            if (vidMode != null) {
                int[] mx = new int[1], my = new int[1];
                glfwGetMonitorPos(monitor, mx, my);
                int[] size = windowSize();
                x = mx[0] + (vidMode.width() - size[0]) / 2;
                y = my[0] + (vidMode.height() - size[1]) / 2;
            }
        }
        showAt(x, y, prepareFrame);
    }

    void showAt(int x, int y, Runnable prepareFrame) {
        if (handle == NULL || glfwGetWindowAttrib(handle, GLFW_VISIBLE) == GLFW_TRUE) {
            return;
        }
        if (x != Integer.MIN_VALUE) {
            glfwSetWindowPos(handle, x, y);
        }
        // Pre-fill both front and back buffers with a clean frame so neither the
        // stale back-buffer nor garbage appears the instant the window becomes visible.
        for (int i = 0; i < 2; i++) {
            prepareFrame.run();
            glfwSwapBuffers(handle);
        }
        glfwShowWindow(handle);
        glfwPollEvents();
    }

    boolean shouldClose() {
        return glfwWindowShouldClose(handle);
    }

    boolean isIconified() {
        return handle == NULL || glfwGetWindowAttrib(handle, GLFW_ICONIFIED) == GLFW_TRUE;
    }

    void pollEvents() {
        glfwPollEvents();
    }

    void swapBuffers() {
        glfwSwapBuffers(handle);
    }

    void setCursorPosCallback(GLFWCursorPosCallbackI callback) {
        glfwSetCursorPosCallback(handle, callback);
    }

    void setScrollCallback(GLFWScrollCallbackI callback) {
        glfwSetScrollCallback(handle, callback);
    }

    void setMouseButtonCallback(GLFWMouseButtonCallbackI callback) {
        glfwSetMouseButtonCallback(handle, callback);
    }

    void setFramebufferSizeCallback(GLFWFramebufferSizeCallbackI callback) {
        glfwSetFramebufferSizeCallback(handle, callback);
    }

    void setWindowIconifyCallback(GLFWWindowIconifyCallbackI callback) {
        glfwSetWindowIconifyCallback(handle, callback);
    }

    void setWindowSizeCallback(GLFWWindowSizeCallbackI callback) {
        glfwSetWindowSizeCallback(handle, callback);
    }

    void setWindowMaximizeCallback(GLFWWindowMaximizeCallbackI callback) {
        glfwSetWindowMaximizeCallback(handle, callback);
    }

    void setKeyCallback(GLFWKeyCallbackI callback) {
        glfwSetKeyCallback(handle, callback);
    }

    void setCharCallback(GLFWCharCallbackI callback) {
        glfwSetCharCallback(handle, callback);
    }

    void dispose() {
        if (handle != NULL) {
            glfwFreeCallbacks(handle);
            glfwDestroyWindow(handle);
            handle = NULL;
        }
        glfwTerminate();
    }

    private long tryCreateWindow(int width, int height, String title) {
        // Attempt 1: OpenGL 3.3 core profile (preferred)
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_DECORATED, GLFW_FALSE);
        long window = glfwCreateWindow(width, height, title, NULL, NULL);
        if (window != NULL) return window;

        // Attempt 2: OpenGL 3.3 compatibility profile (some older/VM drivers)
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_COMPAT_PROFILE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_DECORATED, GLFW_FALSE);
        window = glfwCreateWindow(width, height, title, NULL, NULL);
        if (window != NULL) {
            System.err.println("[GL] Using compatibility profile fallback");
        }
        return window;
    }

    private void setWindowIcon() {
        try (InputStream is = GlWindow.class.getResourceAsStream("/icon.ico")) {
            if (is == null) return;
            BufferedImage img = loadIco(is);
            if (img == null) return;
            int width = img.getWidth();
            int height = img.getHeight();
            int[] rgb = img.getRGB(0, 0, width, height, null, 0, width);
            ByteBuffer buffer = MemoryUtil.memAlloc(width * height * 4);
            try {
                for (int pixel : rgb) {
                    buffer.put((byte) ((pixel >> 16) & 0xFF))
                            .put((byte) ((pixel >> 8) & 0xFF))
                            .put((byte) (pixel & 0xFF))
                            .put((byte) ((pixel >> 24) & 0xFF));
                }
                buffer.flip();
                try (GLFWImage.Buffer icons = GLFWImage.malloc(1)) {
                    icons.position(0).width(width).height(height).pixels(buffer);
                    glfwSetWindowIcon(handle, icons);
                }
            } finally {
                MemoryUtil.memFree(buffer);
            }
        } catch (Exception e) {
            System.err.println("[Icon] " + e.getMessage());
        }
    }

    private static BufferedImage loadIco(InputStream is) throws Exception {
        byte[] data = is.readAllBytes();
        if (data.length < 6) return null;
        int count = (data[4] & 0xFF) | ((data[5] & 0xFF) << 8);
        int bestW = -1;
        int bestOff = 0;
        int bestLen = 0;
        for (int i = 0; i < count; i++) {
            int base = 6 + i * 16;
            if (base + 16 > data.length) break;
            int width = data[base] & 0xFF;
            if (width == 0) width = 256;
            int size = icoInt(data, base + 8);
            int offset = icoInt(data, base + 12);
            if (width > bestW) {
                bestW = width;
                bestOff = offset;
                bestLen = size;
            }
        }
        if (bestW < 0) return null;
        return ImageIO.read(new java.io.ByteArrayInputStream(data, bestOff, bestLen));
    }

    private static int icoInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }
}
