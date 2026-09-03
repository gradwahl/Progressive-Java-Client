from pathlib import Path

ROOT = Path('src/main/java')
GL = ROOT / 'com/gradwahl/rs254/gl'
UI = GL / 'ui'
UI.mkdir(parents=True, exist_ok=True)


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f'{label}: expected exactly 1 match, found {count}')
    return text.replace(old, new, 1)


def make_public_methods(text, signatures):
    for sig in signatures:
        text = replace_once(text, f'    {sig}', f'    public {sig}', f'public {sig}')
    return text

# Move GlUiRenderer into gl.ui and expose only the API already consumed by GLRenderer/UI peers.
old = GL / 'GlUiRenderer.java'
new = UI / 'GlUiRenderer.java'
text = old.read_text(encoding='utf-8')
text = replace_once(text, 'package com.gradwahl.rs254.gl;', 'package com.gradwahl.rs254.gl.ui;', 'GlUiRenderer package')
text = replace_once(text, 'import jagex2.graphics.PixMap;', 'import com.gradwahl.rs254.gl.GlShader;\n\nimport jagex2.graphics.PixMap;', 'GlUiRenderer GlShader import')
text = replace_once(text, 'final class GlUiRenderer {', 'public final class GlUiRenderer {', 'GlUiRenderer visibility')
text = replace_once(text, '    GlUiRenderer(int maxUiW, int screenH) {', '    public GlUiRenderer(int maxUiW, int screenH) {', 'GlUiRenderer constructor')
text = make_public_methods(text, [
    'void init() {',
    'void uploadGameUi() {',
    'void beginPass() {',
    'void drawBound(int x, int y, int width, int height, float uMin, float uMax) {',
    'void drawTexture(int texture, int x, int y, int width, int height, float uMin, float uMax) {',
    'void bindGameTexture() {',
    'void dispose() {',
])
new.write_text(text, encoding='utf-8')
old.unlink()

# Move SidebarRenderer into gl.ui. Its lambda target must be public across the package boundary.
old = GL / 'SidebarRenderer.java'
new = UI / 'SidebarRenderer.java'
text = old.read_text(encoding='utf-8')
text = replace_once(text, 'package com.gradwahl.rs254.gl;', 'package com.gradwahl.rs254.gl.ui;', 'SidebarRenderer package')
text = replace_once(text, 'final class SidebarRenderer {', 'public final class SidebarRenderer {', 'SidebarRenderer visibility')
text = replace_once(text, '    interface Painter {', '    public interface Painter {', 'SidebarRenderer Painter visibility')
text = replace_once(text, '    SidebarRenderer(GlUiRenderer uiRenderer) {', '    public SidebarRenderer(GlUiRenderer uiRenderer) {', 'SidebarRenderer constructor')
text = make_public_methods(text, [
    'void init() {',
    'void render(int physX, int physY, int physW, int physH,',
    'void dispose() {',
])
new.write_text(text, encoding='utf-8')
old.unlink()

# Move ClientTitleBar into gl.ui while preserving its existing public static paint/hit-test API.
old = GL / 'ClientTitleBar.java'
new = UI / 'ClientTitleBar.java'
text = old.read_text(encoding='utf-8')
text = replace_once(text, 'package com.gradwahl.rs254.gl;', 'package com.gradwahl.rs254.gl.ui;', 'ClientTitleBar package')
text = replace_once(text, '    static final class Surface {', '    public static final class Surface {', 'ClientTitleBar.Surface visibility')
text = replace_once(text, '        Surface(GlUiRenderer uiRenderer) {', '        public Surface(GlUiRenderer uiRenderer) {', 'ClientTitleBar.Surface constructor')
for sig in [
    'void init() {',
    'void markDirty() {',
    'void updateHover(int button) {',
    'void render(int logicalWidth, String title, boolean maximized, boolean sidebarOpen,',
    'void dispose() {',
]:
    text = replace_once(text, f'        {sig}', f'        public {sig}', f'ClientTitleBar.Surface public {sig}')
new.write_text(text, encoding='utf-8')
old.unlink()

# GlUiRenderer stays on the shared shader helper; crossing into gl.ui requires explicit visibility.
shader = GL / 'GlShader.java'
text = shader.read_text(encoding='utf-8')
text = replace_once(text, 'final class GlShader {', 'public final class GlShader {', 'GlShader visibility')
text = replace_once(text, '    static int buildProgram(String vertSrc, String fragSrc) {', '    public static int buildProgram(String vertSrc, String fragSrc) {', 'GlShader.buildProgram visibility')
shader.write_text(text, encoding='utf-8')

# Rewrite any existing fully-qualified imports/references.
replacements = {
    'com.gradwahl.rs254.gl.ClientTitleBar': 'com.gradwahl.rs254.gl.ui.ClientTitleBar',
    'com.gradwahl.rs254.gl.GlUiRenderer': 'com.gradwahl.rs254.gl.ui.GlUiRenderer',
    'com.gradwahl.rs254.gl.SidebarRenderer': 'com.gradwahl.rs254.gl.ui.SidebarRenderer',
}
for path in ROOT.rglob('*.java'):
    text = path.read_text(encoding='utf-8')
    original = text
    for old_name, new_name in replacements.items():
        text = text.replace(old_name, new_name)
    if text != original:
        path.write_text(text, encoding='utf-8')

# Add imports for unqualified references that used to work only because classes shared package gl.
def ensure_import(path, fqcn, token):
    text = path.read_text(encoding='utf-8')
    if token not in text or f'import {fqcn};' in text or f'package {fqcn.rsplit(".", 1)[0]};' in text:
        return
    package_end = text.index('\n', text.index('package ')) + 1
    text = text[:package_end] + f'\nimport {fqcn};' + text[package_end:]
    path.write_text(text, encoding='utf-8')

for path in ROOT.rglob('*.java'):
    ensure_import(path, 'com.gradwahl.rs254.gl.ui.ClientTitleBar', 'ClientTitleBar')
    ensure_import(path, 'com.gradwahl.rs254.gl.ui.GlUiRenderer', 'GlUiRenderer')
    ensure_import(path, 'com.gradwahl.rs254.gl.ui.SidebarRenderer', 'SidebarRenderer')

# Structural guards: old files are gone, new packages exist, and root GLRenderer imports the UI package.
for name in ('ClientTitleBar.java', 'GlUiRenderer.java', 'SidebarRenderer.java'):
    if (GL / name).exists():
        raise RuntimeError(f'old gl path still exists: {name}')
    moved = UI / name
    if not moved.exists():
        raise RuntimeError(f'moved gl/ui path missing: {name}')
    if not moved.read_text(encoding='utf-8').startswith('package com.gradwahl.rs254.gl.ui;'):
        raise RuntimeError(f'wrong package declaration: {name}')

glr = (GL / 'GLRenderer.java').read_text(encoding='utf-8')
for fqcn in (
    'com.gradwahl.rs254.gl.ui.ClientTitleBar',
    'com.gradwahl.rs254.gl.ui.GlUiRenderer',
    'com.gradwahl.rs254.gl.ui.SidebarRenderer',
):
    if f'import {fqcn};' not in glr:
        raise RuntimeError(f'GLRenderer missing import: {fqcn}')

print('gl/ui package move complete')
