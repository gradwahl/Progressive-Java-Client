package com.gradwahl.rs254.gl.ui;

import com.gradwahl.rs254.gl.GlShader;

import jagex2.graphics.PixMap;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Shared OpenGL transport for the software-composited client UI.
 *
 * This class owns only the common PixMap upload texture, shader and fullscreen quad.
 * Sidebar, world-map and titlebar composition remain in GLRenderer and reuse this pass.
 */
public final class GlUiRenderer {
    private static final String UI_VERT_SRC = """
            #version 330 core
            layout(location=0) in vec2 aPos;
            layout(location=1) in vec2 aUV;
            uniform float uUMin;
            uniform float uUMax;
            out vec2 vUV;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vUV = vec2(uUMin + aUV.x * (uUMax - uUMin), aUV.y);
            }
            """;

    private static final String UI_FRAG_SRC = """
            #version 330 core
            in vec2 vUV;
            uniform sampler2D uUI;
            out vec4 fragColor;
            void main() {
                vec4 c = texture(uUI, vUV);
                // Alpha == 0 means this pixel was never written (or cleared) — show 3D scene.
                if (c.a == 0.0) discard;
                fragColor = vec4(c.rgb, 1.0);
            }
            """;

    private final int maxUiW;
    private final int screenH;

    private int program;
    private int quadVao;
    private int quadVbo;
    private int gameTexture;
    private int textureLoc;
    private int uMinLoc;
    private int uMaxLoc;
    private IntBuffer directBuffer;

    public GlUiRenderer(int maxUiW, int screenH) {
        this.maxUiW = maxUiW;
        this.screenH = screenH;
    }

    public void init() {
        // Allocate the CPU-side UI buffer that PixMap.draw() writes into.
        PixMap.uiBuffer = new int[maxUiW * screenH];
        PixMap.uiWidth = maxUiW;
        PixMap.uiHeight = screenH;
        // Direct (off-heap) copy buffer for glTexSubImage2D — LWJGL requires direct buffers.
        directBuffer = MemoryUtil.memAllocInt(maxUiW * screenH);

        // Fullscreen quad: two triangles covering NDC [-1,1].
        // UV Y is flipped because RS has Y=0 at top, OpenGL NDC has Y=1 at top.
        float[] quad = {
            -1f, -1f,  0f, 1f,
            -1f,  1f,  0f, 0f,
             1f,  1f,  1f, 0f,
            -1f, -1f,  0f, 1f,
             1f,  1f,  1f, 0f,
             1f, -1f,  1f, 1f,
        };
        quadVao = glGenVertexArrays();
        quadVbo = glGenBuffers();
        glBindVertexArray(quadVao);
        glBindBuffer(GL_ARRAY_BUFFER, quadVbo);
        glBufferData(GL_ARRAY_BUFFER, quad, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 4 * Float.BYTES, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 4 * Float.BYTES, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);

        program = GlShader.buildProgram(UI_VERT_SRC, UI_FRAG_SRC);
        textureLoc = glGetUniformLocation(program, "uUI");
        uMinLoc = glGetUniformLocation(program, "uUMin");
        uMaxLoc = glGetUniformLocation(program, "uUMax");

        // Create the 2D overlay texture (BGRA so IntBuffer maps straight).
        gameTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, gameTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, maxUiW, screenH, 0,
                GL_BGRA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    }

    public void uploadGameUi() {
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, gameTexture);
        directBuffer.clear();
        directBuffer.put(PixMap.uiBuffer, 0, maxUiW * screenH);
        directBuffer.flip();
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, maxUiW, screenH,
                GL_BGRA, GL_UNSIGNED_BYTE, directBuffer);
    }

    public void beginPass() {
        glUseProgram(program);
        glUniform1i(textureLoc, 0);
        glBindVertexArray(quadVao);
    }

    public void drawBound(int x, int y, int width, int height, float uMin, float uMax) {
        glUniform1f(uMinLoc, uMin);
        glUniform1f(uMaxLoc, uMax);
        glViewport(x, y, width, height);
        glDrawArrays(GL_TRIANGLES, 0, 6);
    }

    public void drawTexture(int texture, int x, int y, int width, int height, float uMin, float uMax) {
        glUseProgram(program);
        glUniform1i(textureLoc, 0);
        glUniform1f(uMinLoc, uMin);
        glUniform1f(uMaxLoc, uMax);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        glBindVertexArray(quadVao);
        glViewport(x, y, width, height);
        glDrawArrays(GL_TRIANGLES, 0, 6);
    }

    public void bindGameTexture() {
        glBindTexture(GL_TEXTURE_2D, gameTexture);
    }

    public void dispose() {
        glDeleteBuffers(quadVbo);
        glDeleteVertexArrays(quadVao);
        glDeleteProgram(program);
        glDeleteTextures(gameTexture);
        if (directBuffer != null) MemoryUtil.memFree(directBuffer);
    }
}
