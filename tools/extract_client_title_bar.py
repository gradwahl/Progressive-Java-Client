from pathlib import Path

renderer_path = Path('src/main/java/com/gradwahl/rs254/gl/GLRenderer.java')
src = renderer_path.read_text(encoding='utf-8')


def replace_once(old, new, label):
    global src
    count = src.count(old)
    if count != 1:
        raise RuntimeError(f'{label}: expected exactly 1 match, found {count}')
    src = src.replace(old, new, 1)


replace_once(
    '    // Native-resolution custom title bar for the in-game GLFW window.\n'
    '    private BufferedImage titleBarBuf;\n'
    '    private IntBuffer     titleBarDirect;\n'
    '    private int           titleBarTex;\n'
    '    private int           titleBarBufW, titleBarBufH;\n'
    '    private boolean       titleBarDirty = true;\n'
    '    private boolean       titleMinimizeHover;\n'
    '    private boolean       titleCloseHover;\n'
    '    private boolean       titleMaximizeHover;\n'
    '    private boolean       titleSidebarHover;\n'
    '    private boolean       titleDiscordHover;\n',
    '    // Shared title-bar painter plus native GL backing surface.\n'
    '    private final ClientTitleBar.Surface titleBarSurface;\n',
    'titlebar surface fields')

replace_once(
    '        this.uiRenderer = new GlUiRenderer(maxUiW, screenH);\n'
    '        this.sidebarRenderer = new SidebarRenderer(uiRenderer);\n',
    '        this.uiRenderer = new GlUiRenderer(maxUiW, screenH);\n'
    '        this.sidebarRenderer = new SidebarRenderer(uiRenderer);\n'
    '        this.titleBarSurface = new ClientTitleBar.Surface(uiRenderer);\n',
    'titlebar surface construction')

replace_once(
    '        sidebarRenderer.init();\n\n'
    '        titleBarTex = glGenTextures();\n'
    '        glBindTexture(GL_TEXTURE_2D, titleBarTex);\n'
    '        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);\n'
    '        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);\n\n',
    '        sidebarRenderer.init();\n        titleBarSurface.init();\n\n',
    'titlebar texture initialization')

replace_once(
    '        if (titleBarTex != 0) glDeleteTextures(titleBarTex);\n',
    '        titleBarSurface.dispose();\n',
    'titlebar texture disposal')
replace_once(
    '        if (titleBarDirect != null) MemoryUtil.memFree(titleBarDirect);\n',
    '',
    'titlebar direct buffer disposal')

# Keep GLFW interaction/window policy in GLRenderer, but move hover state + dirty tracking.
old_hover = '''        boolean inTitle = y >= 0 && y < CLIENT_TITLE_BAR_H;
        int btn = inTitle ? titleHitTest(x) : ClientTitleBar.BTN_NONE;
        boolean newClose = btn == ClientTitleBar.BTN_CLOSE,   newMin  = btn == ClientTitleBar.BTN_MINIMIZE,
                newMax   = btn == ClientTitleBar.BTN_MAXIMIZE, newSide = btn == ClientTitleBar.BTN_SIDEBAR,
                newDisc  = btn == ClientTitleBar.BTN_DISCORD;
        if (newClose != titleCloseHover || newMin != titleMinimizeHover
                || newMax != titleMaximizeHover || newSide != titleSidebarHover || newDisc != titleDiscordHover) {
            titleCloseHover = newClose; titleMinimizeHover = newMin;
            titleMaximizeHover = newMax; titleSidebarHover = newSide; titleDiscordHover = newDisc;
            titleBarDirty = true;
        }
        return inTitle;
'''
new_hover = '''        boolean inTitle = y >= 0 && y < CLIENT_TITLE_BAR_H;
        titleBarSurface.updateHover(inTitle ? titleHitTest(x) : ClientTitleBar.BTN_NONE);
        return inTitle;
'''
replace_once(old_hover, new_hover, 'titlebar hover state')

method_start = src.index('    private void drawClientTitleBar() {')
method_end = src.index('    private long findWindowMonitor(', method_start)
new_method = '''    private void drawClientTitleBar() {
        if (activeTitleBarHeight() <= 0 || window == NULL) return;

        int[] fw = new int[1], fh = new int[1];
        glfwGetFramebufferSize(window, fw, fh);
        int titlePhysH = titleBarPhysicalH(fh[0]);
        if (titlePhysH <= 0) return;

        titleBarSurface.render(Math.max(1, windowW), titleBarText(),
                glTitleMaximized, glSidebarOpen, fw[0], fh[0], titlePhysH);

        legacyRenderer.bindProgram();
        updateOutputViewport();
    }

'''
src = src[:method_start] + new_method + src[method_end:]

# All former dirty writes now invalidate the surface directly.
src = src.replace('titleBarDirty = true;', 'titleBarSurface.markDirty();')

forbidden = [
    'titleBarBuf', 'titleBarDirect', 'titleBarTex', 'titleBarBufW', 'titleBarBufH',
    'titleBarDirty', 'titleMinimizeHover', 'titleCloseHover', 'titleMaximizeHover',
    'titleSidebarHover', 'titleDiscordHover'
]
leftovers = [token for token in forbidden if token in src]
if leftovers:
    raise RuntimeError(f'GLRenderer still owns titlebar surface/hover tokens: {leftovers}')

renderer_path.write_text(src, encoding='utf-8')

# Extend ClientTitleBar without changing its existing static paint/hit-test API.
title_path = Path('src/main/java/com/gradwahl/rs254/gl/ClientTitleBar.java')
title = title_path.read_text(encoding='utf-8')

imports_old = '''import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.imageio.ImageIO;
'''
imports_new = '''import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import javax.imageio.ImageIO;

import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL33.*;
'''
if title.count(imports_old) != 1:
    raise RuntimeError('ClientTitleBar import block changed')
title = title.replace(imports_old, imports_new, 1)

insert_at = title.rfind('\n}')
if insert_at < 0:
    raise RuntimeError('ClientTitleBar closing brace not found')

surface = r'''

    /**
     * Native GL backing surface for the in-game title bar. The static Java2D
     * paint/hit-test API above remains shared with the AWT pre-login window.
     */
    static final class Surface {
        private final GlUiRenderer uiRenderer;
        private BufferedImage buffer;
        private IntBuffer direct;
        private int texture;
        private int width;
        private int height;
        private boolean dirty = true;
        private boolean minimizeHover;
        private boolean closeHover;
        private boolean maximizeHover;
        private boolean sidebarHover;
        private boolean discordHover;

        Surface(GlUiRenderer uiRenderer) {
            this.uiRenderer = uiRenderer;
        }

        void init() {
            texture = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        }

        void markDirty() {
            dirty = true;
        }

        void updateHover(int button) {
            boolean newClose = button == BTN_CLOSE;
            boolean newMin = button == BTN_MINIMIZE;
            boolean newMax = button == BTN_MAXIMIZE;
            boolean newSide = button == BTN_SIDEBAR;
            boolean newDisc = button == BTN_DISCORD;
            if (newClose != closeHover || newMin != minimizeHover || newMax != maximizeHover
                    || newSide != sidebarHover || newDisc != discordHover) {
                closeHover = newClose;
                minimizeHover = newMin;
                maximizeHover = newMax;
                sidebarHover = newSide;
                discordHover = newDisc;
                dirty = true;
            }
        }

        void render(int logicalWidth, String title, boolean maximized, boolean sidebarOpen,
                    int framebufferWidth, int framebufferHeight, int physicalHeight) {
            if (texture == 0 || physicalHeight <= 0 || framebufferWidth <= 0 || framebufferHeight <= 0) {
                return;
            }
            ensureSize(Math.max(1, logicalWidth), BAR_H);

            if (dirty) {
                Graphics2D g = buffer.createGraphics();
                try {
                    paint(g, width, title,
                            closeHover, minimizeHover, maximizeHover, sidebarHover, discordHover,
                            maximized, sidebarOpen);
                } finally {
                    g.dispose();
                }

                int[] pixels = ((DataBufferInt) buffer.getRaster().getDataBuffer()).getData();
                direct.clear();
                direct.put(pixels);
                direct.flip();
                glBindTexture(GL_TEXTURE_2D, texture);
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height,
                        GL_BGRA, GL_UNSIGNED_BYTE, direct);
                dirty = false;
            }

            uiRenderer.drawTexture(texture, 0, framebufferHeight - physicalHeight,
                    framebufferWidth, physicalHeight, 0f, 1f);
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
            dirty = true;
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
                    GL_BGRA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            dirty = true;
        }
    }
'''

title = title[:insert_at] + surface + title[insert_at:]
title_path.write_text(title, encoding='utf-8')
