from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GL_DIR = ROOT / "src/main/java/com/gradwahl/rs254/gl"
GL_RENDERER = GL_DIR / "GLRenderer.java"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def replace_method(text: str, signature: str, replacement: str) -> str:
    start = text.find(signature)
    if start < 0:
        raise RuntimeError(f"method not found: {signature}")
    brace = text.find("{", start)
    if brace < 0:
        raise RuntimeError(f"method opening brace not found: {signature}")
    depth = 0
    i = brace
    while i < len(text):
        ch = text[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                end = i + 1
                return text[:start] + replacement + text[end:]
        i += 1
    raise RuntimeError(f"method closing brace not found: {signature}")


GL_SHADER = r'''package com.gradwahl.rs254.gl;

import static org.lwjgl.opengl.GL33.*;

/** Small OpenGL shader/program utility shared by the GL render passes. */
final class GlShader {
    private GlShader() {}

    static int buildProgram(String vertSrc, String fragSrc) {
        int vs = compileShader(GL_VERTEX_SHADER, vertSrc);
        int fs = compileShader(GL_FRAGMENT_SHADER, fragSrc);
        int program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            throw new RuntimeException("Shader link:\n" + glGetProgramInfoLog(program));
        }
        glDeleteShader(vs);
        glDeleteShader(fs);
        return program;
    }

    private static int compileShader(int type, String src) {
        int id = glCreateShader(type);
        glShaderSource(id, src);
        glCompileShader(id);
        if (glGetShaderi(id, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new RuntimeException("Shader compile:\n" + glGetShaderInfoLog(id));
        }
        return id;
    }
}
'''

GL_TEXTURE_MANAGER = r'''package com.gradwahl.rs254.gl;

import jagex2.graphics.Pix3D;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33.*;

/** Owns the OpenGL texture objects that mirror Pix3D's legacy texture slots. */
final class GlTextureManager {
    private final int[] gpuTex = new int[Pix3D.TEXTURE_COUNT];

    /** Upload (or re-upload) one game texture slot to the GPU. */
    void uploadTexture(int texId) {
        if (texId < 0 || texId >= Pix3D.TEXTURE_COUNT) return;
        if (Pix3D.textures[texId] == null || Pix3D.texturePalette[texId] == null) return;
        int[] texels = Pix3D.getTexels(texId);
        int size = Pix3D.lowMem ? 64 : 128;

        if (gpuTex[texId] == 0) gpuTex[texId] = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, gpuTex[texId]);

        ByteBuffer rgba = MemoryUtil.memAlloc(size * size * 4);
        try {
            boolean transparent = Pix3D.textureTranslucent[texId];
            for (int i = 0; i < size * size; i++) {
                int c = texels[i];
                rgba.put((byte) (c >> 16));
                rgba.put((byte) (c >> 8));
                rgba.put((byte) c);
                rgba.put(transparent && c == 0 ? (byte) 0 : (byte) -1);
            }
            rgba.flip();
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, size, size, 0,
                    GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        } finally {
            MemoryUtil.memFree(rgba);
        }

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
    }

    void bindTexture(int texId) {
        if (texId < 0 || texId >= Pix3D.TEXTURE_COUNT) return;
        if (gpuTex[texId] == 0) uploadTexture(texId);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, gpuTex[texId]);
    }

    void dispose() {
        for (int texture : gpuTex) {
            if (texture != 0) glDeleteTextures(texture);
        }
    }
}
'''

LEGACY_GPU_RENDERER = r'''package com.gradwahl.rs254.gl;

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
'''


def patch_gl_renderer() -> None:
    src = GL_RENDERER.read_text(encoding="utf-8")

    src = replace_once(src, "import java.nio.FloatBuffer;\n", "", "FloatBuffer import")
    src = replace_once(src,
        "    private static final int FLOATS_PER_VERT = 10;\n"
        "    private static final int MAX_TRIS        = 32_768;\n"
        "    private static final int MAX_VERTS       = MAX_TRIS * 3;\n",
        "", "legacy constants")

    legacy_shader_start = src.find('    private static final String VERT_SRC = """')
    state_marker = "    // -------------------------------------------------------------------------\n    // state\n"
    legacy_shader_end = src.find(state_marker, legacy_shader_start)
    if legacy_shader_start < 0 or legacy_shader_end < 0:
        raise RuntimeError("legacy shader block markers not found")
    src = src[:legacy_shader_start] + src[legacy_shader_end:]

    src = replace_once(src,
        "    private long     window;\n"
        "    private int      vao, vbo, prog;\n"
        "    private int      uScreen, uTex;\n"
        "    private boolean  frameDrawable = true;\n"
        "    private boolean  windowIconified;\n"
        "    private int      framebufferW = 1;\n"
        "    private int      framebufferH = 1;\n"
        "    private int      restoreCooldownFrames;\n\n"
        "    private final FloatBuffer buf =\n"
        "            MemoryUtil.memAllocFloat(MAX_VERTS * FLOATS_PER_VERT);\n"
        "    private int vertCount;\n\n"
        "    private final int[] gpuTex   = new int[Pix3D.TEXTURE_COUNT]; // OpenGL texture IDs per slot\n"
        "    private int         currentTexId = -1;         // texture bound for current batch\n",
        "    private long     window;\n"
        "    private boolean  frameDrawable = true;\n"
        "    private boolean  windowIconified;\n"
        "    private int      framebufferW = 1;\n"
        "    private int      framebufferH = 1;\n"
        "    private int      restoreCooldownFrames;\n\n"
        "    private final GlTextureManager textureManager;\n"
        "    private final LegacyGpuRenderer legacyRenderer;\n",
        "legacy state")

    src = replace_once(src,
        "    // Cached per-frame viewport scissor bounds in GL framebuffer coords {x,y,w,h}.\n"
        "    // Computed once in beginFrame and reused by flushBatch to clip the SD triangle batch.\n"
        "    private int[] frameSceneScissor;\n",
        "", "frame scissor field")

    src = replace_once(src,
        "        this.screenW = screenW;\n"
        "        this.screenH = screenH;\n"
        "        this.maxUiW = screenW + SIDEBAR_PANEL_W + SIDEBAR_RAIL_W;\n",
        "        this.screenW = screenW;\n"
        "        this.screenH = screenH;\n"
        "        this.textureManager = new GlTextureManager();\n"
        "        this.legacyRenderer = new LegacyGpuRenderer(screenW, screenH, textureManager);\n"
        "        this.maxUiW = screenW + SIDEBAR_PANEL_W + SIDEBAR_RAIL_W;\n",
        "constructor legacy components")

    src = replace_once(src,
        "        setupVAO();\n"
        "        prog    = buildProgram();\n"
        "        uScreen = glGetUniformLocation(prog, \"uScreen\");\n"
        "        uTex    = glGetUniformLocation(prog, \"uTex\");\n\n"
        "        glUseProgram(prog);\n"
        "        glUniform2f(uScreen, screenW, screenH);\n"
        "        glUniform1i(uTex, 0);\n",
        "        legacyRenderer.init();\n",
        "legacy init")

    src = replace_once(src,
        "        // Cache scissor bounds for this frame so flushBatch can clip the SD batch cheaply.\n"
        "        frameSceneScissor = computeSceneScissor();\n",
        "        // Cache scissor bounds for this frame so the legacy batch can clip the SD triangles cheaply.\n"
        "        int[] frameSceneScissor = computeSceneScissor();\n",
        "frame scissor local")
    src = replace_once(src,
        "        buf.clear();\n"
        "        vertCount    = 0;\n"
        "        currentTexId = -1;\n",
        "        legacyRenderer.beginFrame(frameSceneScissor);\n",
        "legacy begin frame")

    src = replace_method(src, "    private void restoreSceneGlState()",
        "    private void restoreSceneGlState() {\n"
        "        legacyRenderer.restoreState();\n"
        "    }")

    start = src.find("    // -------------------------------------------------------------------------\n    // TriangleRenderer implementation")
    end = src.find("    // -------------------------------------------------------------------------\n    // UI overlay pass", start)
    if start < 0 or end < 0:
        raise RuntimeError("legacy renderer section markers not found")
    facade = r'''    // -------------------------------------------------------------------------
    // TriangleRenderer compatibility facade
    // -------------------------------------------------------------------------

    @Override
    public void addFlat(int x0, int y0, int x1, int y1, int x2, int y2, int rgb, int trans, int z0, int z1, int z2) {
        legacyRenderer.addFlat(x0, y0, x1, y1, x2, y2, rgb, trans, z0, z1, z2);
    }

    @Override
    public void addGouraud(int x0, int y0, int x1, int y1, int x2, int y2,
                           int hsl0, int hsl1, int hsl2, int trans, int z0, int z1, int z2) {
        legacyRenderer.addGouraud(x0, y0, x1, y1, x2, y2, hsl0, hsl1, hsl2, trans, z0, z1, z2);
    }

    @Override
    public void addTextured(int x0, int y0, int x1, int y1, int x2, int y2,
                            float u0, float v0, float u1, float v1, float u2, float v2,
                            int hsl0, int hsl1, int hsl2, int texId, int z0, int z1, int z2) {
        legacyRenderer.addTextured(x0, y0, x1, y1, x2, y2,
                u0, v0, u1, v1, u2, v2, hsl0, hsl1, hsl2, texId, z0, z1, z2);
    }

    /** Upload (or re-upload) one game texture slot to the GPU. */
    public void uploadTexture(int texId) {
        textureManager.uploadTexture(texId);
    }

'''
    src = src[:start] + facade + src[end:]

    src = src.replace("        flushBatch();", "        legacyRenderer.flush();")
    src = src.replace("            flushBatch();", "            legacyRenderer.flush();")
    src = replace_once(src, "        uiProg = buildProgram(UI_VERT_SRC, UI_FRAG_SRC);\n",
                       "        uiProg = GlShader.buildProgram(UI_VERT_SRC, UI_FRAG_SRC);\n",
                       "UI shader build")
    src = src.replace("glUseProgram(prog);", "legacyRenderer.bindProgram();")

    src = replace_once(src,
        "        legacyRenderer.flush();\n"
        "        if (hdScene != null) hdScene.dispose();\n"
        "        glDeleteBuffers(vbo);\n"
        "        glDeleteVertexArrays(vao);\n"
        "        glDeleteProgram(prog);\n",
        "        legacyRenderer.flush();\n"
        "        if (hdScene != null) hdScene.dispose();\n"
        "        legacyRenderer.dispose();\n",
        "legacy destroy")
    src = replace_once(src,
        "        for (int t : gpuTex) if (t != 0) glDeleteTextures(t);\n"
        "        MemoryUtil.memFree(buf);\n",
        "        textureManager.dispose();\n",
        "texture destroy")

    src = replace_method(src, "    private void addOverlayRect(int x, int y, int width, int height, int rgb)",
        "    private void addOverlayRect(int x, int y, int width, int height, int rgb) {\n"
        "        legacyRenderer.addOverlayRect(x, y, width, height, rgb);\n"
        "    }")

    src = replace_method(src, "    private void setupVAO()", "")
    src = replace_method(src, "    private int buildProgram()", "")
    src = replace_method(src, "    private int buildProgram(String vertSrc, String fragSrc)", "")
    src = replace_method(src, "    private static int compileShader(int type, String src)", "")

    banned = [
        "flushBatch(", "putVertex(", "currentTexId", "gpuTex", "MAX_VERTS",
        "FLOATS_PER_VERT", "private int      vao", "private int      uScreen",
        "buildProgram(UI_VERT_SRC", "glUseProgram(prog)",
    ]
    for token in banned:
        if token in src:
            raise RuntimeError(f"legacy token still present in GLRenderer: {token}")

    GL_RENDERER.write_text(src, encoding="utf-8")


def main() -> None:
    (GL_DIR / "GlShader.java").write_text(GL_SHADER, encoding="utf-8")
    (GL_DIR / "GlTextureManager.java").write_text(GL_TEXTURE_MANAGER, encoding="utf-8")
    (GL_DIR / "LegacyGpuRenderer.java").write_text(LEGACY_GPU_RENDERER, encoding="utf-8")
    patch_gl_renderer()


if __name__ == "__main__":
    main()
