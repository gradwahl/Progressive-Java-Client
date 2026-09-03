package com.gradwahl.rs254.gl;

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
