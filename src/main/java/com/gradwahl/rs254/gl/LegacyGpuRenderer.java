package com.gradwahl.rs254.gl;

import jagex2.graphics.Pix3D;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Legacy screen-space GPU path used by Pix3D's TriangleRenderer forwarding.
 *
 * Vertex layout (10 floats): x, y, r, g, b, u, v, type, z, alpha.
 * This is a mechanical extraction from GLRenderer; rendering behaviour is unchanged.
 */
final class LegacyGpuRenderer {
    private static final int FLOATS_PER_VERT = 10;
    private static final int MAX_TRIS = 32_768;
    private static final int MAX_VERTS = MAX_TRIS * 3;

    private static final String VERT_SRC = """
            #version 330 core
            layout(location=0) in vec2  aPos;
            layout(location=1) in vec3  aColor;
            layout(location=2) in vec2  aUV;
            layout(location=3) in float aType;
            layout(location=4) in float aZ;
            layout(location=5) in float aAlpha;

            uniform vec2 uScreen;

            out vec3  vColor;
            out vec2  vUV;
            flat out int vType;
            out float vAlpha;

            void main() {
                // RS screen-space → NDC XY: (0,0) top-left → (-1,1)
                vec2 ndc = vec2(
                    aPos.x / uScreen.x *  2.0 - 1.0,
                    aPos.y / uScreen.y * -2.0 + 1.0
                );
                // Depth buffer disabled — rely on RS2's painter's algorithm sort.
                gl_Position = vec4(ndc, 0.0, 1.0);
                vColor = aColor;
                vUV    = aUV;
                vType  = int(aType);
                vAlpha = aAlpha;
            }
            """;

    private static final String FRAG_SRC = """
            #version 330 core
            in  vec3 vColor;
            in  vec2 vUV;
            flat in int vType;
            in float vAlpha;

            uniform sampler2D uTex;

            out vec4 fragColor;

            void main() {
                if (vType == 1) {
                    vec4 t = texture(uTex, vUV);
                    if (t.a == 0.0) discard;          // RS2 transparent (palette 0)
                    fragColor = t * vec4(vColor, 1.0); // texture × lighting
                } else {
                    fragColor = vec4(vColor, vAlpha);
                }
            }
            """;

    private final int screenW;
    private final int screenH;
    private final GlTextureManager textures;
    private final FloatBuffer buf = MemoryUtil.memAllocFloat(MAX_VERTS * FLOATS_PER_VERT);

    private int vao;
    private int vbo;
    private int program;
    private int uScreen;
    private int uTex;
    private int vertCount;
    private int currentTexId = -1;
    private int[] frameSceneScissor;

    LegacyGpuRenderer(int screenW, int screenH, GlTextureManager textures) {
        this.screenW = screenW;
        this.screenH = screenH;
        this.textures = textures;
    }

    void init() {
        setupVAO();
        program = GlShader.buildProgram(VERT_SRC, FRAG_SRC);
        uScreen = glGetUniformLocation(program, "uScreen");
        uTex = glGetUniformLocation(program, "uTex");

        glUseProgram(program);
        glUniform2f(uScreen, screenW, screenH);
        glUniform1i(uTex, 0);
    }

    void beginFrame(int[] frameSceneScissor) {
        this.frameSceneScissor = frameSceneScissor;
        buf.clear();
        vertCount = 0;
        currentTexId = -1;
    }

    void restoreState() {
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glDisable(GL_SCISSOR_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glActiveTexture(GL_TEXTURE0);
        glUseProgram(program);
        glUniform2f(uScreen, screenW, screenH);
        glUniform1i(uTex, 0);
    }

    void bindProgram() {
        glUseProgram(program);
    }

    void addFlat(int x0, int y0, int x1, int y1, int x2, int y2,
                 int rgb, int trans, int z0, int z1, int z2) {
        if (currentTexId != -1) {
            flush();
            currentTexId = -1;
        }
        ensureCapacity(3);
        float r = ch(rgb >> 16), g = ch(rgb >> 8), b = ch(rgb);
        float alpha = legacyAlpha(trans);
        putVertex(x0, y0, r, g, b, 0, 0, 0, z0, alpha);
        putVertex(x1, y1, r, g, b, 0, 0, 0, z1, alpha);
        putVertex(x2, y2, r, g, b, 0, 0, 0, z2, alpha);
        vertCount += 3;
    }

    void addGouraud(int x0, int y0, int x1, int y1, int x2, int y2,
                    int hsl0, int hsl1, int hsl2, int trans, int z0, int z1, int z2) {
        if (currentTexId != -1) {
            flush();
            currentTexId = -1;
        }
        ensureCapacity(3);
        int c0 = Pix3D.colourTable[hsl0];
        int c1 = Pix3D.colourTable[hsl1];
        int c2 = Pix3D.colourTable[hsl2];
        float alpha = legacyAlpha(trans);
        putVertex(x0, y0, ch(c0 >> 16), ch(c0 >> 8), ch(c0), 0, 0, 0, z0, alpha);
        putVertex(x1, y1, ch(c1 >> 16), ch(c1 >> 8), ch(c1), 0, 0, 0, z1, alpha);
        putVertex(x2, y2, ch(c2 >> 16), ch(c2 >> 8), ch(c2), 0, 0, 0, z2, alpha);
        vertCount += 3;
    }

    void addTextured(int x0, int y0, int x1, int y1, int x2, int y2,
                     float u0, float v0, float u1, float v1, float u2, float v2,
                     int hsl0, int hsl1, int hsl2, int texId, int z0, int z1, int z2) {
        if (texId != currentTexId) {
            flush();
            textures.bindTexture(texId);
            currentTexId = texId;
        }
        ensureCapacity(3);

        // RS2 texture lighting: texel >>> (hsl >> 6).
        // hsl >> 6 gives 0,1,2,3 for [full, half, quarter, eighth] brightness.
        float b0 = texBrightness(hsl0);
        float b1 = texBrightness(hsl1);
        float b2 = texBrightness(hsl2);
        putVertex(x0, y0, b0, b0, b0, u0, v0, 1, z0, 1);
        putVertex(x1, y1, b1, b1, b1, u1, v1, 1, z1, 1);
        putVertex(x2, y2, b2, b2, b2, u2, v2, 1, z2, 1);
        vertCount += 3;
    }

    void addOverlayRect(int x, int y, int width, int height, int rgb) {
        ensureCapacity(6);
        float r = ch(rgb >> 16), g = ch(rgb >> 8), b = ch(rgb);
        putVertex(x,         y,          r, g, b, 0, 0, 0, 0, 1);
        putVertex(x + width, y,          r, g, b, 0, 0, 0, 0, 1);
        putVertex(x + width, y + height, r, g, b, 0, 0, 0, 0, 1);
        putVertex(x,         y,          r, g, b, 0, 0, 0, 0, 1);
        putVertex(x + width, y + height, r, g, b, 0, 0, 0, 0, 1);
        putVertex(x,         y + height, r, g, b, 0, 0, 0, 0, 1);
        vertCount += 6;
    }

    void flush() {
        if (vertCount == 0) return;
        buf.flip();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, buf);
        glBindVertexArray(vao);
        // The SD rasterizer forwards raw projected vertices to this batch before the
        // software clipper runs, so triangles can have coordinates outside the viewport.
        // Apply a scissor to prevent them from bleeding into the chatbox/sidebar.
        boolean applyScissor = frameSceneScissor != null;
        if (applyScissor) {
            glEnable(GL_SCISSOR_TEST);
            glScissor(frameSceneScissor[0], frameSceneScissor[1], frameSceneScissor[2], frameSceneScissor[3]);
        }
        glDrawArrays(GL_TRIANGLES, 0, vertCount);
        if (applyScissor) glDisable(GL_SCISSOR_TEST);
        buf.clear();
        vertCount = 0;
    }

    void dispose() {
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        glDeleteProgram(program);
        MemoryUtil.memFree(buf);
    }

    private void ensureCapacity(int additionalVerts) {
        if (vertCount + additionalVerts > MAX_VERTS) flush();
    }

    private void putVertex(int x, int y, float r, float g, float b,
                           float u, float v, float type, float z, float alpha) {
        buf.put(x).put(y).put(r).put(g).put(b).put(u).put(v).put(type).put(z).put(alpha);
    }

    private static float ch(int packed) {
        return (packed & 0xFF) / 255f;
    }

    private static float legacyAlpha(int trans) {
        return (256 - trans) / 256f;
    }

    private static float texBrightness(int hsl) {
        int shift = hsl >> 6;
        if (shift <= 0) return 1f;
        if (shift >= 7) return 1f / 128f;
        return 1f / (1 << shift);
    }

    private void setupVAO() {
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER,
                (long) MAX_VERTS * FLOATS_PER_VERT * Float.BYTES,
                GL_DYNAMIC_DRAW);

        int stride = FLOATS_PER_VERT * Float.BYTES;
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 2, GL_FLOAT, false, stride, 5L * Float.BYTES);
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(3, 1, GL_FLOAT, false, stride, 7L * Float.BYTES);
        glEnableVertexAttribArray(3);
        glVertexAttribPointer(4, 1, GL_FLOAT, false, stride, 8L * Float.BYTES);
        glEnableVertexAttribArray(4);
        glVertexAttribPointer(5, 1, GL_FLOAT, false, stride, 9L * Float.BYTES);
        glEnableVertexAttribArray(5);
    }
}
