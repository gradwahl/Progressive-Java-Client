package com.gradwahl.rs254.gl;

import jagex2.dash3d.Ground;
import jagex2.dash3d.Model;
import jagex2.dash3d.QuickGround;
import jagex2.dash3d.Square;
import jagex2.dash3d.World;
import jagex2.graphics.Pix3D;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Phase 2 foundation: a true world-space GPU pass, kept separate from the legacy
 * screen-space triangle forwarder in {@link GLRenderer}.
 *
 * <p>The legacy path receives triangles that RS2 has already projected to 2D and
 * blits them with the depth buffer disabled. This class starts the HD path with
 * real 3D terrain geometry, the RS2 camera transform, and a real depth value.
 *
 * <p>The vertex shader replicates the projection from {@code Model.worldRender}
 * and {@code World.renderQuickGround}: yaw, pitch, perspective divide by depth,
 * focal length 512, centered on {@code Pix3D.projectionX/Y}. Camera/projection
 * values are snapshotted by {@link GLRenderer#setHdFrame} immediately after the
 * SD scene pass, before the minimap changes the projection center.
 *
 * <p>Current scope: draw visible terrain from {@link QuickGround}/{@link Ground}
 * tile data using the same HSL color indices the software renderer gives to
 * {@link Pix3D}. Texture/material sampling comes later; textured terrain uses the
 * legacy low-memory HSL fallback color for now.
 */
final class HdSceneRenderer implements Model.HdModelSink {
    enum DebugMode {
        NORMAL("normal"),
        TERRAIN_ONLY("terrain-only"),
        MODEL_ONLY("model-only"),
        WIREFRAME("wireframe"),
        TILE_ID("tile-id colour"),
        TEXTURE_ID("texture-id colour"),
        TEXTURE_ISOLATE("texture isolate (cycles one id white)"),
        WATER_SHORE_MASK("water shoreline mask"),
        WATER_FOAM_NOISE("water foam noise"),
        WATER_FOAM_FINAL("water final foam"),
        HEIGHT("height colour");

        final String label;

        DebugMode(String label) {
            this.label = label;
        }
    }

    /** Vertex layout: vec3 world position + vec4 color/brightness + vec2 uv + float texture layer. */
    private static final int FLOATS_PER_VERT = 12;
    /** Enough headroom for the 50x50 visible terrain window, including shaped tiles. */
    private static final int MAX_VERTS = 512 * 1024;
    private static final int MODEL_FLOATS_PER_VERT = 10;
    private static final int MAX_MODEL_VERTS = 384 * 1024;
    private static final int TRANSPARENT_HSL = 12345678;
    private static final int MAX_TEXTURES = Pix3D.TEXTURE_COUNT;
    /** Flat colour for untextured tiles in texture-id debug mode, so textured overlays stand out. */
    private static final int DEBUG_UNTEXTURED = 0x303030;
    /**
     * HD ground textures (RLHD jpgs) loaded into texture-array layers ABOVE the
     * vanilla slots,
     * vanilla slots, so untextured rev-254 underlays (grass/dirt/sand have no vanilla
     * texture id) can be textured the RLHD way: ground texture modulated by the tile's
     * existing blended underlay colour.
     */
    private static final int GRASS_LAYER = MAX_TEXTURES;
    private static final int DIRT_LAYER = MAX_TEXTURES + 1;
    private static final int SAND_LAYER = MAX_TEXTURES + 2;
    private static final int GROUND_TEX_COUNT = 3;
    /** World units per ground-texture repeat (128 = one repeat per tile). */
    private static final float GROUND_UV_SCALE = 128f;
    private static final String[] GROUND_TEX_RES = {
            "/hd/ground/grass_1.jpg", "/hd/ground/dirt_1.jpg", "/hd/ground/sand_1.jpg"
    };
    private static final String RLHD_TEX_MANIFEST = "/hd/textures/manifest.txt";
    private boolean groundTexturesOk;
    private static final float AMBIENT = 0.66f;
    private static final float SUN_POWER = 0.38f;
    private static final float SUN_X = -0.42f;
    private static final float SUN_Y = -0.78f;
    private static final float SUN_Z = -0.46f;
    // Sky/fog colour — used as both the GL clear (above-horizon sky) and the fog that
    // terrain fades into at far distance. Near-black matches classic RS2's dark sky.
    private static final float SKY_R = 0.05f;
    private static final float SKY_G = 0.05f;
    private static final float SKY_B = 0.07f;
    private static final float FOG_NEAR = 1850f;
    private static final float FOG_FAR = 3400f;
    /**
     * Water rendering (rev-254 water = vanilla texture id 1). Drawn as a separate
     * transparent pass over the opaque terrain, grounded in RLHD `WaterType`: a
     * semi-transparent surface that reflects the sky (fresnel), fades to a teal depth
     * colour, with a subtle animated ripple. Vanilla texture layer 1 supplies ripple detail.
     */
    private static final int WATER_TEXTURE_ID = 1;
    private static final int MAX_WATER_VERTS = 192 * 1024;
    private static final float WATER_DEPTH_R = 0.34f, WATER_DEPTH_G = 0.47f, WATER_DEPTH_B = 0.56f; // calm light blue-grey
    private static final float WATER_SKY_R = 0.41f, WATER_SKY_G = 0.50f, WATER_SKY_B = 0.61f;       // RLHD WATER surfaceColor #69809C
    // RLHD water foam map is used as shoreline-foam noise, not full-surface ripples.
    private static final String WATER_NORMAL_RES = "/hd/water/water_normal_map_1.png";
    private static final String WATER_FOAM_RES = "/hd/water/water_foam.jpg";
    private static final float WATER_NORMAL_STRENGTH = 0.65f;
    private static final float WATER_SPEC_STRENGTH = 0.75f;
    private static final float WATER_SPEC_GLOSS = 120f;
    private static final float WATER_OPACITY = 0.40f;  // lower so the underwater riverbed shows through
    /** Riverbed drawn this far BELOW the water surface (heights are negative-up, so +down). */
    private static final int WATER_DEPTH_OFFSET = 30;
    /** Visible water sits on the source water surface; foam is shader-side, not raised geometry. */
    private static final int WATER_SURFACE_LIFT = 0;
    /** Water shader foam controls. Width is world units; scale/speed use world-space UVs. */
    private static final float WATER_FOAM_WIDTH = 24f;
    private static final float WATER_FOAM_SCALE = 0.08f;
    private static final float WATER_FOAM_SPEED_X = 0.020f;
    private static final float WATER_FOAM_SPEED_Y = 0.010f;
    private static final float WATER_FOAM_THRESHOLD_LOW = 0.18f;
    private static final float WATER_FOAM_THRESHOLD_HIGH = 0.68f;
    private static final float WATER_FOAM_OPACITY = 0.62f;
    private static final float WATER_FOAM_R = 0.85f, WATER_FOAM_G = 0.95f, WATER_FOAM_B = 1.00f;
    private static final float RIVERBED_R = 0.34f, RIVERBED_G = 0.33f, RIVERBED_B = 0.27f; // darker sandy bottom for depth contrast

    private static final String VERT_SRC = """
            #version 330 core
            layout(location=0) in vec3 aWorld;
            layout(location=1) in vec4 aColor;
            layout(location=2) in vec2 aUV;
            layout(location=3) in float aTex;
            layout(location=4) in float aTex2;
            layout(location=5) in float aBlend;

            uniform vec3 uCam;
            uniform vec4 uRot;
            uniform vec2 uCenter;
            uniform vec2 uScreen;

            out vec4 vColor;
            out vec2 vUV;
            out float vDepth;
            flat out float vTex;
            flat out float vTex2;
            out float vBlend;
            out vec3 vWorld;

            void main() {
                vec3  r    = aWorld - uCam;
                float sinX = uRot.x, cosX = uRot.y;
                float sinY = uRot.z, cosY = uRot.w;

                float a     = r.z * sinY + r.x * cosY;
                float b     = r.z * cosY - r.x * sinY;
                float depth = r.y * sinX + b * cosX;
                float sY    = r.y * cosX - b * sinX;

                depth = max(depth, 1.0);
                float screenX = uCenter.x + (a  * 512.0) / depth;
                float screenY = uCenter.y + (sY * 512.0) / depth;

                float ndcX = screenX / uScreen.x *  2.0 - 1.0;
                float ndcY = screenY / uScreen.y * -2.0 + 1.0;
                float ndcZ = clamp((depth - 50.0) / (3500.0 - 50.0), 0.0, 1.0) * 2.0 - 1.0;

                gl_Position = vec4(ndcX, ndcY, ndcZ, 1.0);
                vColor = aColor;
                vUV = aUV;
                vDepth = depth;
                vTex = aTex;
                vTex2 = aTex2;
                vBlend = aBlend;
                vWorld = aWorld;
            }
            """;

    private static final String FRAG_SRC = """
            #version 330 core
            in vec4 vColor;
            in vec2 vUV;
            in float vDepth;
            flat in float vTex;
            flat in float vTex2;
            in float vBlend;
            in vec3 vWorld;
            uniform sampler2DArray uTextures;
            uniform vec3 uFogColor;
            uniform vec2 uFogRange;
            uniform float uFogEnabled;
            uniform vec3 uCam;
            uniform float uWaterPass;
            uniform float uTime;
            uniform vec3 uWaterDepth;
            uniform vec3 uWaterSky;
            uniform float uWaterOpacity;
            out vec4 fragColor;
            void main() {
                float fog = clamp((vDepth - uFogRange.x) / (uFogRange.y - uFogRange.x), 0.0, 1.0) * uFogEnabled;
                if (uWaterPass > 0.5) {
                    // Calm RLHD-style water: a light blue-grey surface with a gentle
                    // large-scale undulation and a soft sky reflection. No speckle.
                    vec3 viewDir = normalize(uCam - vWorld);
                    // Looking down -> more transparent depth colour; grazing -> more sky reflection.
                    float fres = pow(1.0 - clamp(viewDir.y, 0.0, 1.0), 4.0);
                    // Less sky reflection when looking down so the underwater bottom shows through.
                    float reflectMix = clamp(0.12 + 0.55 * fres, 0.0, 0.80);

                    // Very subtle, slow, low-frequency undulation (brightness only).
                    float wave = sin(vWorld.x * 0.018 + uTime * 1.05)
                               + sin(vWorld.z * 0.021 - uTime * 0.85);
                    float shade = 0.97 + 0.03 * wave;

                    vec3 water = mix(uWaterDepth, uWaterSky, reflectMix) * shade;
                    float alpha = mix(uWaterOpacity, 0.92, fres);
                    fragColor = vec4(mix(water, uFogColor, fog), alpha);
                    return;
                }
                vec4 base;
                if (vTex >= 0.0) {
                    vec4 texel = texture(uTextures, vec3(vUV, vTex));
                    if (vBlend > 0.0) {
                        // Feather toward a neighbouring ground material at tile boundaries.
                        vec4 texel2 = texture(uTextures, vec3(vUV, vTex2));
                        texel = mix(texel, texel2, vBlend);
                    }
                    if (texel.a <= 0.0) {
                        discard;
                    }
                    base = vec4(texel.rgb * vColor.rgb, texel.a * vColor.a);
                } else {
                    base = vColor;
                }
                fragColor = vec4(mix(base.rgb, uFogColor, fog), base.a);
            }
            """;

    // Phase B water: a dedicated shader that samples the opaque scene's colour (for
    // refraction of the riverbed) and depth (for the water-column depth gradient + foam).
    private static final String WATER_VERT_SRC = """
            #version 330 core
            layout(location=0) in vec3 aWorld;
            layout(location=1) in vec4 aShoreMask;
            uniform vec3 uCam;
            uniform vec4 uRot;
            uniform vec2 uCenter;
            uniform vec2 uScreen;
            out vec3 vWorld;
            out float vCamDepth;
            out vec4 vShoreMask;
            void main() {
                vec3 r = aWorld - uCam;
                float sinX = uRot.x, cosX = uRot.y, sinY = uRot.z, cosY = uRot.w;
                float a = r.z * sinY + r.x * cosY;
                float b = r.z * cosY - r.x * sinY;
                float depth = r.y * sinX + b * cosX;
                float sY = r.y * cosX - b * sinX;
                depth = max(depth, 1.0);
                float screenX = uCenter.x + (a * 512.0) / depth;
                float screenY = uCenter.y + (sY * 512.0) / depth;
                float ndcX = screenX / uScreen.x *  2.0 - 1.0;
                float ndcY = screenY / uScreen.y * -2.0 + 1.0;
                float ndcZ = clamp((depth - 50.0) / (3500.0 - 50.0), 0.0, 1.0) * 2.0 - 1.0;
                gl_Position = vec4(ndcX, ndcY, ndcZ, 1.0);
                vWorld = aWorld;
                vCamDepth = depth;
                vShoreMask = aShoreMask;
            }
            """;

    private static final String WATER_FRAG_SRC = """
            #version 330 core
            in vec3 vWorld;
            in float vCamDepth;
            in vec4 vShoreMask;
            uniform sampler2D uSceneColor;
            uniform sampler2D uSceneDepth;
            uniform sampler2D uWaterNormal;
            uniform sampler2D uWaterFoam;
            uniform vec2 uViewOrigin;
            uniform vec2 uViewSize;
            uniform vec3 uCam;
            uniform float uTime;
            uniform vec3 uWaterDepthCol;
            uniform vec3 uWaterSky;
            uniform vec3 uFogColor;
            uniform vec2 uFogRange;
            uniform float uFogEnabled;
            uniform vec3 uSunDir;
            uniform float uNormalStrength;
            uniform float uSpecStrength;
            uniform float uSpecGloss;
            uniform float uFoamWidth;
            uniform float uFoamScale;
            uniform vec2 uFoamSpeed;
            uniform vec2 uFoamThreshold;
            uniform float uFoamOpacity;
            uniform vec3 uFoamColor;
            uniform int uWaterDebug;
            out vec4 fragColor;
            float camDepthFromWin(float win) { return 50.0 + win * (3500.0 - 50.0); }
            void main() {
                vec2 uv = (gl_FragCoord.xy - uViewOrigin) / uViewSize;
                float sceneWin = texture(uSceneDepth, uv).r;
                float waterWin = gl_FragCoord.z;
                if (waterWin > sceneWin + 0.00005) {
                    discard; // opaque geometry is in front of the water here
                }
                float column = max(camDepthFromWin(sceneWin) - camDepthFromWin(waterWin), 0.0);

                // Calm refraction: sample the riverbed directly, without full-surface ripples.
                vec3 N = vec3(0.0, 1.0, 0.0);
                vec3 bottom = texture(uSceneColor, uv).rgb;

                // Deeper water -> hides bottom and tends to the deep colour.
                float tint = clamp(0.28 + column / 170.0, 0.0, 0.85);
                vec3 water = mix(bottom, uWaterDepthCol, tint);

                // Capped sky reflection via the perturbed normal (more at grazing angles).
                vec3 viewDir = normalize(uCam - vWorld);
                float fres = pow(1.0 - clamp(dot(viewDir, N), 0.0, 1.0), 4.0);
                water = mix(water, uWaterSky, clamp(fres * 0.55, 0.0, 0.6));

                // Soft sun glint on the calm water plane.
                vec3 halfDir = normalize(uSunDir + viewDir);
                float spec = pow(max(dot(N, halfDir), 0.0), uSpecGloss) * (uSpecStrength * 0.35);
                water += spec;

                // Shore/water-edge foam: CPU provides approximate distance-to-shore
                // as four directional masks on the normal water surface vertices.
                // Convert the strongest interpolated edge mask back to world-unit distance.
                float nearestShore = clamp(max(max(vShoreMask.x, vShoreMask.y), max(vShoreMask.z, vShoreMask.w)), 0.0, 1.0);
                float distanceToShore = (1.0 - nearestShore) * 128.0;
                float shoreMask = 1.0 - smoothstep(0.0, uFoamWidth, distanceToShore);
                shoreMask *= shoreMask;
                vec2 foamUV = vWorld.xz * uFoamScale + vec2(uTime * uFoamSpeed.x, uTime * uFoamSpeed.y);
                float noise = texture(uWaterFoam, foamUV).r;
                float foamPattern = smoothstep(uFoamThreshold.x, uFoamThreshold.y, noise) * mix(0.45, 1.0, noise);
                float foam = shoreMask * foamPattern;

                if (uWaterDebug == 1) {
                    fragColor = vec4(vec3(shoreMask), 1.0);
                    return;
                } else if (uWaterDebug == 2) {
                    fragColor = vec4(vec3(noise), 1.0);
                    return;
                } else if (uWaterDebug == 3) {
                    fragColor = vec4(vec3(foam), 1.0);
                    return;
                }

                water = mix(water, uFoamColor, foam * uFoamOpacity);

                float fog = clamp((vCamDepth - uFogRange.x) / (uFogRange.y - uFogRange.x), 0.0, 1.0) * uFogEnabled;
                fragColor = vec4(mix(water, uFogColor, fog), 1.0);
            }
            """;

    static final class HdCamera {
        float x, y, z;
        float sinX, cosX, sinY, cosY;
        float projX, projY;
    }

    private static final class ViewPoint {
        final int centerX, centerY;
        final int screenX, screenY;
        final int viewX, viewY, viewZ;

        ViewPoint(int centerX, int centerY, int screenX, int screenY, int viewX, int viewY, int viewZ) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.screenX = screenX;
            this.screenY = screenY;
            this.viewX = viewX;
            this.viewY = viewY;
            this.viewZ = viewZ;
        }
    }

    private final int screenW;
    private final int screenH;
    private final FloatBuffer buf = MemoryUtil.memAllocFloat(MAX_VERTS * FLOATS_PER_VERT);
    private final FloatBuffer modelBuf = MemoryUtil.memAllocFloat(MAX_MODEL_VERTS * MODEL_FLOATS_PER_VERT);
    private final FloatBuffer waterBuf = MemoryUtil.memAllocFloat(MAX_WATER_VERTS * FLOATS_PER_VERT);
    private int waterVertCount;
    private final long startNanos = System.nanoTime();

    private int vao, vbo, prog;
    private int texArray;
    private int uCam, uRot, uCenter, uScreen, uTextures, uFogColor, uFogRange, uFogEnabled;
    private int uWaterPass, uTime, uWaterDepth, uWaterSky, uWaterOpacity;
    // Offscreen scene framebuffer (Phase A): opaque scene rendered here so later water
    // passes can sample scene colour (refraction) and depth (depth-based colour/foam).
    private int sceneFbo, sceneColorTex, sceneDepthTex, fboW, fboH;
    private boolean fboOk;
    // MSAA: opaque pass draws into multisampled renderbuffers, then resolves into sceneFbo.
    private static final int MSAA_SAMPLES = 4;
    private int msaaFbo, msaaColorRb, msaaDepthRb;
    private boolean msaaOk;
    // Phase B water shader (refraction + depth-based colour). Used only when the FBO is available.
    private int waterProg;
    private int waterNormalTex, waterFoamTex;
    private int wuCam, wuRot, wuCenter, wuScreen, wuTime, wuSceneColor, wuSceneDepth,
            wuViewOrigin, wuViewSize, wuWaterDepthCol, wuWaterSky, wuFogColor, wuFogRange, wuFogEnabled,
            wuWaterNormal, wuWaterFoam, wuSunDir, wuNormalStrength, wuSpecStrength, wuSpecGloss,
            wuFoamWidth, wuFoamScale, wuFoamSpeed, wuFoamThreshold, wuFoamOpacity, wuFoamColor, wuWaterDebug;
    private boolean initialised;
    private boolean texturesUploaded;
    private int textureSize;
    private int totalTextureLayers;
    private java.util.List<String> rlhdTextureResources;
    private boolean failed;
    private int modelVertCount;
    private boolean modelCaptureOverflow;
    private DebugMode debugMode = DebugMode.NORMAL;
    /** Distinct terrain texture ids seen this frame, printed (throttled) in texture-id debug mode. */
    private final java.util.TreeSet<Integer> debugTexIds = new java.util.TreeSet<>();
    private long debugTexIdsLastPrint;
    /** Player tile (>>7) this frame, and the texture id under it: -1 untextured, -2 not in view. */
    private int playerTileX, playerTileZ;
    private int debugPlayerTexId;
    /** HD ground texture layer the player's tile would get (-3 = not computed). */
    private int debugPlayerGroundLayer = -3;
    /** Texture-isolate mode: which id is currently painted white, and the cycle cursor/clock. */
    private int debugIsolateId = -999;
    private int debugIsolateIdx;
    private long debugIsolateLastSwitch;
    private int currentModelBitset;
    private int currentModelObjectId = -1;
    private java.util.List<HdMaterialResolver.SourceLight> sourceLights = java.util.List.of();

    HdSceneRenderer(int screenW, int screenH) {
        this.screenW = screenW;
        this.screenH = screenH;
    }

    private void init() {
        prog = buildProgram(VERT_SRC, FRAG_SRC);
        uCam = glGetUniformLocation(prog, "uCam");
        uRot = glGetUniformLocation(prog, "uRot");
        uCenter = glGetUniformLocation(prog, "uCenter");
        uScreen = glGetUniformLocation(prog, "uScreen");
        uTextures = glGetUniformLocation(prog, "uTextures");
        uFogColor = glGetUniformLocation(prog, "uFogColor");
        uFogRange = glGetUniformLocation(prog, "uFogRange");
        uFogEnabled = glGetUniformLocation(prog, "uFogEnabled");
        uWaterPass = glGetUniformLocation(prog, "uWaterPass");
        uTime = glGetUniformLocation(prog, "uTime");
        uWaterDepth = glGetUniformLocation(prog, "uWaterDepth");
        uWaterSky = glGetUniformLocation(prog, "uWaterSky");
        uWaterOpacity = glGetUniformLocation(prog, "uWaterOpacity");

        waterProg = buildProgram(WATER_VERT_SRC, WATER_FRAG_SRC);
        wuCam = glGetUniformLocation(waterProg, "uCam");
        wuRot = glGetUniformLocation(waterProg, "uRot");
        wuCenter = glGetUniformLocation(waterProg, "uCenter");
        wuScreen = glGetUniformLocation(waterProg, "uScreen");
        wuTime = glGetUniformLocation(waterProg, "uTime");
        wuSceneColor = glGetUniformLocation(waterProg, "uSceneColor");
        wuSceneDepth = glGetUniformLocation(waterProg, "uSceneDepth");
        wuViewOrigin = glGetUniformLocation(waterProg, "uViewOrigin");
        wuViewSize = glGetUniformLocation(waterProg, "uViewSize");
        wuWaterDepthCol = glGetUniformLocation(waterProg, "uWaterDepthCol");
        wuWaterSky = glGetUniformLocation(waterProg, "uWaterSky");
        wuFogColor = glGetUniformLocation(waterProg, "uFogColor");
        wuFogRange = glGetUniformLocation(waterProg, "uFogRange");
        wuFogEnabled = glGetUniformLocation(waterProg, "uFogEnabled");
        wuWaterNormal = glGetUniformLocation(waterProg, "uWaterNormal");
        wuWaterFoam = glGetUniformLocation(waterProg, "uWaterFoam");
        wuSunDir = glGetUniformLocation(waterProg, "uSunDir");
        wuNormalStrength = glGetUniformLocation(waterProg, "uNormalStrength");
        wuSpecStrength = glGetUniformLocation(waterProg, "uSpecStrength");
        wuSpecGloss = glGetUniformLocation(waterProg, "uSpecGloss");
        wuFoamWidth = glGetUniformLocation(waterProg, "uFoamWidth");
        wuFoamScale = glGetUniformLocation(waterProg, "uFoamScale");
        wuFoamSpeed = glGetUniformLocation(waterProg, "uFoamSpeed");
        wuFoamThreshold = glGetUniformLocation(waterProg, "uFoamThreshold");
        wuFoamOpacity = glGetUniformLocation(waterProg, "uFoamOpacity");
        wuFoamColor = glGetUniformLocation(waterProg, "uFoamColor");
        wuWaterDebug = glGetUniformLocation(waterProg, "uWaterDebug");

        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) MAX_VERTS * FLOATS_PER_VERT * Float.BYTES, GL_DYNAMIC_DRAW);
        int stride = FLOATS_PER_VERT * Float.BYTES;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 4, GL_FLOAT, false, stride, 3L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 2, GL_FLOAT, false, stride, 7L * Float.BYTES);
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(3, 1, GL_FLOAT, false, stride, 9L * Float.BYTES);
        glEnableVertexAttribArray(3);
        glVertexAttribPointer(4, 1, GL_FLOAT, false, stride, 10L * Float.BYTES);
        glEnableVertexAttribArray(4);
        glVertexAttribPointer(5, 1, GL_FLOAT, false, stride, 11L * Float.BYTES);
        glEnableVertexAttribArray(5);
        glBindVertexArray(0);
        initialised = true;
    }

    void beginModelCapture() {
        modelBuf.clear();
        modelVertCount = 0;
        modelCaptureOverflow = false;
    }

    String cycleDebugMode() {
        DebugMode[] modes = DebugMode.values();
        debugMode = modes[(debugMode.ordinal() + 1) % modes.length];
        return debugMode.label;
    }

    boolean shouldClearLegacyScene() {
        return debugMode != DebugMode.NORMAL;
    }

    float skyRed() {
        return SKY_R;
    }

    float skyGreen() {
        return SKY_G;
    }

    float skyBlue() {
        return SKY_B;
    }

    @Override
    public void beginModel(int yaw, int viewX, int viewY, int viewZ, int bitset) {
        currentModelBitset = bitset;
        currentModelObjectId = bitset > 0 ? bitset >>> 14 & 0x7FFF : -1;
    }

    @Override
    public void endModel() {
        currentModelBitset = 0;
        currentModelObjectId = -1;
    }

    @Override
    public void addModelTriangle(int x0, int y0, int z0, int hsl0,
            int x1, int y1, int z1, int hsl1,
            int x2, int y2, int z2, int hsl2,
            int trans) {
        if (modelVertCount + 3 > MAX_MODEL_VERTS) {
            modelCaptureOverflow = true;
            return;
        }
        float alpha = legacyAlpha(trans);
        putCapturedModelVertex(x0, y0, z0, hsl0, alpha);
        putCapturedModelVertex(x1, y1, z1, hsl1, alpha);
        putCapturedModelVertex(x2, y2, z2, hsl2, alpha);
        modelVertCount += 3;
    }

    @Override
    public void addTexturedModelTriangle(int x0, int y0, int z0, int light0, float u0, float v0,
            int x1, int y1, int z1, int light1, float u1, float v1,
            int x2, int y2, int z2, int light2, float u2, float v2,
            int textureId, int trans) {
        if (textureId < 0 || textureId >= MAX_TEXTURES) {
            addModelTriangle(x0, y0, z0, light0, x1, y1, z1, light1, x2, y2, z2, light2, trans);
            return;
        }
        if (modelVertCount + 3 > MAX_MODEL_VERTS) {
            modelCaptureOverflow = true;
            return;
        }
        float alpha = legacyAlpha(trans);
        if (debugMode == DebugMode.TEXTURE_ID) {
            int rgb = debugTextureColour(textureId);
            putCapturedModelVertexRgb(x0, y0, z0, rgb, alpha);
            putCapturedModelVertexRgb(x1, y1, z1, rgb, alpha);
            putCapturedModelVertexRgb(x2, y2, z2, rgb, alpha);
            modelVertCount += 3;
            return;
        }
        putCapturedTexturedModelVertex(x0, y0, z0, light0, alpha, u0, v0, textureId);
        putCapturedTexturedModelVertex(x1, y1, z1, light1, alpha, u1, v1, textureId);
        putCapturedTexturedModelVertex(x2, y2, z2, light2, alpha, u2, v2, textureId);
        modelVertCount += 3;
    }

    /** Creates/resizes the offscreen scene FBO (colour + depth textures). Returns true if usable. */
    private boolean ensureFbo(int w, int h) {
        if (w <= 0 || h <= 0) {
            return false;
        }
        if (sceneFbo != 0 && w == fboW && h == fboH) {
            return fboOk;
        }
        fboW = w;
        fboH = h;
        if (sceneFbo == 0) sceneFbo = glGenFramebuffers();
        if (sceneColorTex == 0) sceneColorTex = glGenTextures();
        if (sceneDepthTex == 0) sceneDepthTex = glGenTextures();

        glBindTexture(GL_TEXTURE_2D, sceneColorTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        glBindTexture(GL_TEXTURE_2D, sceneDepthTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, w, h, 0, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);

        glBindFramebuffer(GL_FRAMEBUFFER, sceneFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, sceneColorTex, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, sceneDepthTex, 0);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        fboOk = status == GL_FRAMEBUFFER_COMPLETE;
        if (!fboOk) {
            System.err.println("[HD] scene FBO incomplete (status " + status + "); rendering directly.");
            return false;
        }

        // MSAA FBO: multisampled renderbuffers that resolve into sceneFbo after the opaque pass.
        int maxSamples = glGetInteger(GL_MAX_SAMPLES);
        int samples = Math.min(MSAA_SAMPLES, maxSamples);
        if (msaaFbo == 0) msaaFbo = glGenFramebuffers();
        if (msaaColorRb == 0) msaaColorRb = glGenRenderbuffers();
        if (msaaDepthRb == 0) msaaDepthRb = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, msaaColorRb);
        glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_RGBA8, w, h);
        glBindRenderbuffer(GL_RENDERBUFFER, msaaDepthRb);
        glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_DEPTH_COMPONENT24, w, h);
        glBindRenderbuffer(GL_RENDERBUFFER, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, msaaFbo);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, msaaColorRb);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, msaaDepthRb);
        int msaaStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        msaaOk = msaaStatus == GL_FRAMEBUFFER_COMPLETE;
        if (!msaaOk) {
            System.err.println("[HD] MSAA FBO incomplete (status " + msaaStatus + "); MSAA disabled.");
        } else {
            System.err.println("[HD] MSAA " + samples + "x enabled (" + w + "x" + h + ").");
        }
        return true;
    }

    void render(HdSceneFrame frame) {
        World world = frame.world;
        int plane = frame.plane;
        HdCamera cam = frame.camera;
        if (failed || world == null || world.groundHeight == null || cam == null) {
            return;
        }
        try {
            if (!initialised) {
                init();
            }
            ensureTexturesUploaded();
            sourceLights = HdMaterialResolver.lightsFrom(frame.sources);
            if (debugMode == DebugMode.MODEL_ONLY) {
                buf.clear();
            }
            int verts = debugMode == DebugMode.MODEL_ONLY ? 0 : buildTerrain(frame);
            if (debugMode != DebugMode.TERRAIN_ONLY) {
                verts = appendCapturedModels(cam, verts);
            }
            if (verts == 0 && waterVertCount == 0) {
                return;
            }
            // Append the transparent water verts after the opaque geometry so they can
            // be drawn last in their own pass from the same VBO.
            int opaqueVerts = verts;
            int waterVerts = waterVertCount;
            if (waterVerts > 0 && opaqueVerts + waterVerts <= MAX_VERTS) {
                waterBuf.flip();
                buf.put(waterBuf);
            } else {
                waterVerts = 0;
            }
            buf.flip();

            glUseProgram(prog);
            glUniform3f(uCam, cam.x, cam.y, cam.z);
            glUniform4f(uRot, cam.sinX, cam.cosX, cam.sinY, cam.cosY);
            glUniform2f(uCenter, cam.projX, cam.projY);
            glUniform2f(uScreen, screenW, screenH);
            glUniform1i(uTextures, 0);
            glUniform3f(uFogColor, SKY_R, SKY_G, SKY_B);
            glUniform2f(uFogRange, FOG_NEAR, FOG_FAR);
            glUniform1f(uFogEnabled, shouldApplyAtmosphere() ? 1f : 0f);
            glUniform1f(uTime, (System.nanoTime() - startNanos) / 1_000_000_000f);
            glUniform3f(uWaterDepth, WATER_DEPTH_R, WATER_DEPTH_G, WATER_DEPTH_B);
            glUniform3f(uWaterSky, WATER_SKY_R, WATER_SKY_G, WATER_SKY_B);
            glUniform1f(uWaterOpacity, WATER_OPACITY);
            glUniform1f(uWaterPass, 0f);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D_ARRAY, texArray);

            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferSubData(GL_ARRAY_BUFFER, 0, buf);

            // Render the scene into an offscreen FBO so later passes can sample scene
            // colour/depth. Falls back to direct rendering if the FBO is unavailable.
            int[] vp = new int[4];
            glGetIntegerv(GL_VIEWPORT, vp);
            int vx = vp[0], vy = vp[1], vw = vp[2], vh = vp[3];
            boolean useFbo = ensureFbo(vw, vh);
            if (useFbo) {
                // Draw into the MSAA FBO if available; otherwise directly into the resolve FBO.
                glBindFramebuffer(GL_FRAMEBUFFER, msaaOk ? msaaFbo : sceneFbo);
                glViewport(0, 0, vw, vh);
                glScissor(0, 0, vw, vh);  // FBO coords start at 0,0; screen-space scissor would mis-clip
                glClearColor(SKY_R, SKY_G, SKY_B, 1f);
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                glClearColor(0f, 0f, 0f, 1f);
            } else {
                glClear(GL_DEPTH_BUFFER_BIT);
            }
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LEQUAL);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            if (debugMode == DebugMode.WIREFRAME) {
                glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
                glLineWidth(1.5f);
            }
            glBindVertexArray(vao);
            if (opaqueVerts > 0) {
                glDrawArrays(GL_TRIANGLES, 0, opaqueVerts);
            }
            if (debugMode == DebugMode.WIREFRAME) {
                glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
            }

            if (useFbo) {
                // Composite the opaque scene to the screen, then draw depth-aware /
                // refraction water (Phase B) sampling the scene colour + depth textures.
                glBindVertexArray(0);
                glDisable(GL_DEPTH_TEST);
                glDisable(GL_BLEND);
                if (msaaOk) {
                    // Resolve MSAA renderbuffers into the single-sample sceneFbo textures so
                    // the water shader can sample them, then blit the resolved colour to screen.
                    glBindFramebuffer(GL_READ_FRAMEBUFFER, msaaFbo);
                    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, sceneFbo);
                    glBlitFramebuffer(0, 0, vw, vh, 0, 0, vw, vh,
                            GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT, GL_NEAREST);
                }
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
                glBindFramebuffer(GL_READ_FRAMEBUFFER, sceneFbo);
                // Restore screen-space scissor BEFORE the blit so the destination rect
                // (vx, vy, vx+vw, vy+vh) isn't clipped by the FBO-local (0,0,vw,vh) scissor.
                glScissor(vx, vy, vw, vh);
                glBlitFramebuffer(0, 0, vw, vh, vx, vy, vx + vw, vy + vh,
                        GL_COLOR_BUFFER_BIT, GL_NEAREST);
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                glViewport(vx, vy, vw, vh);

                if (waterVerts > 0) {
                    glUseProgram(waterProg);
                    glUniform3f(wuCam, cam.x, cam.y, cam.z);
                    glUniform4f(wuRot, cam.sinX, cam.cosX, cam.sinY, cam.cosY);
                    glUniform2f(wuCenter, cam.projX, cam.projY);
                    glUniform2f(wuScreen, screenW, screenH);
                    glUniform1f(wuTime, (System.nanoTime() - startNanos) / 1_000_000_000f);
                    glUniform2f(wuViewOrigin, vx, vy);
                    glUniform2f(wuViewSize, vw, vh);
                    glUniform3f(wuWaterDepthCol, WATER_DEPTH_R, WATER_DEPTH_G, WATER_DEPTH_B);
                    glUniform3f(wuWaterSky, WATER_SKY_R, WATER_SKY_G, WATER_SKY_B);
                    glUniform3f(wuFogColor, SKY_R, SKY_G, SKY_B);
                    glUniform2f(wuFogRange, FOG_NEAR, FOG_FAR);
                    glUniform1f(wuFogEnabled, shouldApplyAtmosphere() ? 1f : 0f);
                    // Direction from the surface toward the sun (negated sun travel direction).
                    float sl = (float) Math.sqrt(SUN_X * SUN_X + SUN_Y * SUN_Y + SUN_Z * SUN_Z);
                    glUniform3f(wuSunDir, -SUN_X / sl, -SUN_Y / sl, -SUN_Z / sl);
                    glUniform1f(wuNormalStrength, WATER_NORMAL_STRENGTH);
                    glUniform1f(wuSpecStrength, WATER_SPEC_STRENGTH);
                    glUniform1f(wuSpecGloss, WATER_SPEC_GLOSS);
                    glUniform1f(wuFoamWidth, WATER_FOAM_WIDTH);
                    glUniform1f(wuFoamScale, WATER_FOAM_SCALE);
                    glUniform2f(wuFoamSpeed, WATER_FOAM_SPEED_X, WATER_FOAM_SPEED_Y);
                    glUniform2f(wuFoamThreshold, WATER_FOAM_THRESHOLD_LOW, WATER_FOAM_THRESHOLD_HIGH);
                    glUniform1f(wuFoamOpacity, WATER_FOAM_OPACITY);
                    glUniform3f(wuFoamColor, WATER_FOAM_R, WATER_FOAM_G, WATER_FOAM_B);
                    glUniform1i(wuWaterDebug, waterDebugMode());
                    glActiveTexture(GL_TEXTURE1);
                    glBindTexture(GL_TEXTURE_2D, sceneColorTex);
                    glUniform1i(wuSceneColor, 1);
                    glActiveTexture(GL_TEXTURE2);
                    glBindTexture(GL_TEXTURE_2D, sceneDepthTex);
                    glUniform1i(wuSceneDepth, 2);
                    glActiveTexture(GL_TEXTURE3);
                    glBindTexture(GL_TEXTURE_2D, waterNormalTex);
                    glUniform1i(wuWaterNormal, 3);
                    glActiveTexture(GL_TEXTURE4);
                    glBindTexture(GL_TEXTURE_2D, waterFoamTex);
                    glUniform1i(wuWaterFoam, 4);
                    glActiveTexture(GL_TEXTURE0);
                    glBindVertexArray(vao);
                    glDrawArrays(GL_TRIANGLES, opaqueVerts, waterVerts);
                    glBindVertexArray(0);
                }
            } else {
                // No FBO: simple water with the main shader (old path).
                if (waterVerts > 0) {
                    glUniform1f(uWaterPass, 1f);
                    glDepthMask(false);
                    glDrawArrays(GL_TRIANGLES, opaqueVerts, waterVerts);
                    glDepthMask(true);
                    glUniform1f(uWaterPass, 0f);
                }
                glBindVertexArray(0);
                glDisable(GL_DEPTH_TEST);
                glDisable(GL_BLEND);
            }

            glDisable(GL_SCISSOR_TEST);
            buf.clear();
            modelBuf.clear();
            modelVertCount = 0;
            sourceLights = java.util.List.of();
        } catch (Throwable t) {
            failed = true;
            glDisable(GL_SCISSOR_TEST);
            buf.clear();
            modelBuf.clear();
            modelVertCount = 0;
            sourceLights = java.util.List.of();
            System.err.println("[HD] world-space pass disabled (falling back to SD only): " + t);
        }
    }

    private int buildTerrain(HdSceneFrame frame) {
        World world = frame.world;
        int plane = frame.plane;
        HdCamera cam = frame.camera;
        Square[][][] tiles = world.groundh;
        if (tiles == null || plane < 0 || plane >= tiles.length || tiles[plane] == null) {
            return 0;
        }

        buf.clear();
        waterBuf.clear();
        waterVertCount = 0;
        if (debugMode == DebugMode.TEXTURE_ID) {
            debugTexIds.clear();
            debugPlayerTexId = -2;
            debugPlayerGroundLayer = -3;
            jagex2.dash3d.ClientPlayer p = jagex2.client.Client.localPlayer;
            playerTileX = p != null ? p.x >> 7 : Integer.MIN_VALUE;
            playerTileZ = p != null ? p.z >> 7 : Integer.MIN_VALUE;
        } else if (debugMode == DebugMode.TEXTURE_ISOLATE) {
            // Pick the id to paint white from the ids gathered so far, advancing every 2s.
            long now = System.currentTimeMillis();
            if (!debugTexIds.isEmpty() && now - debugIsolateLastSwitch > 2000) {
                debugIsolateLastSwitch = now;
                debugIsolateIdx = (debugIsolateIdx + 1) % debugTexIds.size();
            }
            debugIsolateId = -999;
            int i = 0;
            for (int id : debugTexIds) {
                if (i++ == debugIsolateIdx % Math.max(1, debugTexIds.size())) {
                    debugIsolateId = id;
                    break;
                }
            }
        }
        int verts = 0;
        int minX = Math.max(0, frame.minTileX);
        int minZ = Math.max(0, frame.minTileZ);
        int maxX = Math.min(frame.maxTileX, tiles[plane].length);
        int maxZ = Math.min(frame.maxTileZ, tiles[plane][0].length);
        for (int tx = minX; tx < maxX; tx++) {
            for (int tz = minZ; tz < maxZ; tz++) {
                Square tile = tiles[plane][tx][tz];
                if (tile == null) {
                    continue;
                }
                if (tile.quickGround != null) {
                    verts += putQuickGround(world, plane, tx, tz, tile.quickGround, cam);
                } else if (tile.ground != null) {
                    verts += putGround(tx, tz, tile.ground, cam);
                }
                if (verts + 24 >= MAX_VERTS) {
                    return verts;
                }
            }
        }
        if (debugMode == DebugMode.TEXTURE_ID) {
            long now = System.currentTimeMillis();
            if (now - debugTexIdsLastPrint > 1500) {
                debugTexIdsLastPrint = now;
                StringBuilder sb = new StringBuilder("[HD] visible terrain texture ids: ");
                if (debugTexIds.isEmpty()) {
                    sb.append("(none — all tiles untextured underlay)");
                } else {
                    for (int id : debugTexIds) {
                        int rgb = debugTextureColour(id);
                        sb.append(id).append("(rgb ")
                                .append(rgb >> 16 & 0xFF).append(',')
                                .append(rgb >> 8 & 0xFF).append(',')
                                .append(rgb & 0xFF).append(") ");
                    }
                }
                String underPlayer;
                if (debugPlayerTexId == -2) {
                    underPlayer = "tile not in view";
                } else if (debugPlayerTexId < 0) {
                    underPlayer = "untextured underlay -> HD ground texture: " + groundLayerName(debugPlayerGroundLayer);
                } else {
                    underPlayer = "texId=" + debugPlayerTexId;
                }
                String line = sb.toString().trim() + "  |  UNDER PLAYER (white tile): " + underPlayer;
                System.out.println(line);
                writeDebugTexIdsFile(line);
            }
        } else if (debugMode == DebugMode.TEXTURE_ISOLATE) {
            long now = System.currentTimeMillis();
            if (now - debugTexIdsLastPrint > 400) {
                debugTexIdsLastPrint = now;
                String line = "[HD] ISOLATING texId=" + (debugIsolateId == -999 ? "(waiting)" : debugIsolateId)
                        + "  -> the WHITE tiles on screen are this texture  (cycles every 2s through "
                        + debugTexIds + ")";
                System.out.println(line);
                writeDebugTexIdsFile(line);
            }
        }
        return verts;
    }

    private int putQuickGround(World world, int plane, int tx, int tz, QuickGround ground, HdCamera cam) {
        int[][] h = world.groundHeight[plane];
        int x0 = tx * 128, x1 = x0 + 128;
        int z0 = tz * 128, z1 = z0 + 128;
        int hSW = h[tx][tz];
        int hSE = h[tx + 1][tz];
        int hNE = h[tx + 1][tz + 1];
        int hNW = h[tx][tz + 1];
        if (depth(cam, x0, hSW, z0) < 50 || depth(cam, x1, hSE, z0) < 50
                || depth(cam, x1, hNE, z1) < 50 || depth(cam, x0, hNW, z1) < 50) {
            return 0;
        }

        // Water tiles go to the separate transparent water pass (normal/terrain/model modes only).
        // An opaque sandy riverbed is drawn below the surface so the water reads as see-through depth.
        if (shouldTextureGround() && ground.textureId == WATER_TEXTURE_ID) {
            putWaterQuad(world.groundh[plane], tx, tz, x0, z0, x1, z1, hSW, hSE, hNE, hNW);
            return putRiverbedQuad(x0, z0, x1, z1, hSW, hSE, hNE, hNW);
        }

        int sw = ground.swColour;
        int se = ground.seColour;
        int ne = ground.neColour;
        int nw = ground.nwColour;
        boolean debugRgb = debugMode == DebugMode.TILE_ID || debugMode == DebugMode.HEIGHT;
        if (debugMode == DebugMode.TILE_ID) {
            sw = se = ne = nw = debugTileColour(tx, tz);
        } else if (debugMode == DebugMode.TEXTURE_ID || debugMode == DebugMode.TEXTURE_ISOLATE) {
            int t = ground.textureId;
            if (t >= 0) {
                debugTexIds.add(t);
            }
            if (debugMode == DebugMode.TEXTURE_ISOLATE) {
                sw = se = ne = nw = (t == debugIsolateId) ? 0xFFFFFF : DEBUG_UNTEXTURED;
            } else {
                sw = se = ne = nw = t >= 0 ? debugTextureColour(t) : DEBUG_UNTEXTURED;
                if (tx == playerTileX && tz == playerTileZ) {
                    debugPlayerTexId = t;
                    debugPlayerGroundLayer = classifyGround(ground.rgb);
                    sw = se = ne = nw = 0xFFFFFF; // highlight the tile under the player
                }
            }
            debugRgb = true;
        } else if (debugMode == DebugMode.HEIGHT) {
            sw = debugHeightColour(hSW);
            se = debugHeightColour(hSE);
            ne = debugHeightColour(hNE);
            nw = debugHeightColour(hNW);
        } else if (ground.textureId != -1 && ground.textureId >= 0 && ground.textureId < World.TEXTURE_HSL.length) {
            ViewPoint vSW = project(cam, x0, hSW, z0);
            ViewPoint vSE = project(cam, x1, hSE, z0);
            ViewPoint vNE = project(cam, x1, hNE, z1);
            ViewPoint vNW = project(cam, x0, hNW, z1);
            int tex = ground.textureId;
            ViewPoint tri0AxisA = ground.flat ? vSW : vNE;
            ViewPoint tri0AxisB = ground.flat ? vSE : vNW;
            ViewPoint tri0AxisC = ground.flat ? vNW : vSE;
            float light0 = triangleLight(x1, hNE, z1, x0, hNW, z1, x1, hSE, z0);
            float light1 = triangleLight(x0, hSW, z0, x1, hSE, z0, x0, hNW, z1);
            putTexturedTerrainVertex(x1, hNE, z1, ne, vNE, tri0AxisA, tri0AxisB, tri0AxisC, tex, light0);
            putTexturedTerrainVertex(x0, hNW, z1, nw, vNW, tri0AxisA, tri0AxisB, tri0AxisC, tex, light0);
            putTexturedTerrainVertex(x1, hSE, z0, se, vSE, tri0AxisA, tri0AxisB, tri0AxisC, tex, light0);
            putTexturedTerrainVertex(x0, hSW, z0, sw, vSW, vSW, vSE, vNW, tex, light1);
            putTexturedTerrainVertex(x1, hSE, z0, se, vSE, vSW, vSE, vNW, tex, light1);
            putTexturedTerrainVertex(x0, hNW, z1, nw, vNW, vSW, vSE, vNW, tex, light1);
            return 6;
        }

        // Untextured underlay (grass/dirt/sand has no vanilla texture id): give it an
        // HD ground texture modulated by the tile's existing blended HSL colour, and
        // feather toward neighbouring tiles' materials at each corner.
        int groundLayer = !debugRgb && shouldTextureGround() ? classifyGround(ground.rgb) : -1;
        if (groundLayer >= 0) {
            computeCornerBlends(world.groundh[plane], tx, tz, groundLayer);
        }

        int verts = 0;
        if (ne != TRANSPARENT_HSL) {
            float light = triangleLight(x1, hNE, z1, x0, hNW, z1, x1, hSE, z0);
            if (groundLayer >= 0) {
                putGroundVertex(x1, hNE, z1, ne, light, groundLayer, cbSecondary[2], cbWeight[2]);
                putGroundVertex(x0, hNW, z1, nw, light, groundLayer, cbSecondary[3], cbWeight[3]);
                putGroundVertex(x1, hSE, z0, se, light, groundLayer, cbSecondary[1], cbWeight[1]);
            } else {
                putTerrainVertex(x1, hNE, z1, ne, debugRgb, light);
                putTerrainVertex(x0, hNW, z1, nw, debugRgb, light);
                putTerrainVertex(x1, hSE, z0, se, debugRgb, light);
            }
            verts += 3;
        }
        if (sw != TRANSPARENT_HSL) {
            float light = triangleLight(x0, hSW, z0, x1, hSE, z0, x0, hNW, z1);
            if (groundLayer >= 0) {
                putGroundVertex(x0, hSW, z0, sw, light, groundLayer, cbSecondary[0], cbWeight[0]);
                putGroundVertex(x1, hSE, z0, se, light, groundLayer, cbSecondary[1], cbWeight[1]);
                putGroundVertex(x0, hNW, z1, nw, light, groundLayer, cbSecondary[3], cbWeight[3]);
            } else {
                putTerrainVertex(x0, hSW, z0, sw, debugRgb, light);
                putTerrainVertex(x1, hSE, z0, se, debugRgb, light);
                putTerrainVertex(x0, hNW, z1, nw, debugRgb, light);
            }
            verts += 3;
        }
        return verts;
    }

    private int putGround(int tx, int tz, Ground ground, HdCamera cam) {
        int vertexCount = ground.vertexX.length;
        ViewPoint[] projected = new ViewPoint[vertexCount];
        for (int i = 0; i < ground.vertexX.length; i++) {
            if (depth(cam, ground.vertexX[i], ground.vertexY[i], ground.vertexZ[i]) < 50) {
                return 0;
            }
            projected[i] = project(cam, ground.vertexX[i], ground.vertexY[i], ground.vertexZ[i]);
        }

        // Pre-pass: which ground texture layers touch each vertex, so the within-tile
        // overlay/underlay boundary (e.g. dirt path cutting through grass) can feather
        // instead of showing a hard diagonal edge.
        int[] vertMask = null;
        if (shouldTextureGround()) {
            vertMask = new int[vertexCount];
            for (int i = 0; i < ground.triangleVertexA.length; i++) {
                int layer = groundLayerForTri(ground, i);
                if (layer >= 0) {
                    int bit = layerBit(layer);
                    vertMask[ground.triangleVertexA[i]] |= bit;
                    vertMask[ground.triangleVertexB[i]] |= bit;
                    vertMask[ground.triangleVertexC[i]] |= bit;
                }
            }
        }

        int verts = 0;
        for (int i = 0; i < ground.triangleVertexA.length; i++) {
            int a = ground.triangleVertexA[i];
            int b = ground.triangleVertexB[i];
            int c = ground.triangleVertexC[i];
            // Water triangles go to the separate transparent water pass, with an opaque
            // sandy riverbed triangle below for see-through depth.
            if (shouldTextureGround() && ground.triangleTextureIds != null
                    && ground.triangleTextureIds[i] == WATER_TEXTURE_ID) {
                putWaterVertex(ground.vertexX[a], ground.vertexY[a], ground.vertexZ[a]);
                putWaterVertex(ground.vertexX[b], ground.vertexY[b], ground.vertexZ[b]);
                putWaterVertex(ground.vertexX[c], ground.vertexY[c], ground.vertexZ[c]);
                putRiverbedVertex(ground.vertexX[a], ground.vertexY[a], ground.vertexZ[a]);
                putRiverbedVertex(ground.vertexX[b], ground.vertexY[b], ground.vertexZ[b]);
                putRiverbedVertex(ground.vertexX[c], ground.vertexY[c], ground.vertexZ[c]);
                verts += 3;
                continue;
            }
            int ca = ground.triangleColourA[i];
            int cb = ground.triangleColourB[i];
            int cc = ground.triangleColourC[i];
            boolean debugRgb = debugMode == DebugMode.TILE_ID || debugMode == DebugMode.HEIGHT;
            float light = triangleLight(
                    ground.vertexX[a], ground.vertexY[a], ground.vertexZ[a],
                    ground.vertexX[b], ground.vertexY[b], ground.vertexZ[b],
                    ground.vertexX[c], ground.vertexY[c], ground.vertexZ[c]);
            if (debugMode == DebugMode.TILE_ID) {
                ca = cb = cc = debugTileColour(tx, tz);
            } else if (debugMode == DebugMode.TEXTURE_ID || debugMode == DebugMode.TEXTURE_ISOLATE) {
                int tex = ground.triangleTextureIds != null && ground.triangleTextureIds[i] >= 0
                        ? ground.triangleTextureIds[i]
                        : -1;
                if (tex >= 0) {
                    debugTexIds.add(tex);
                }
                if (debugMode == DebugMode.TEXTURE_ISOLATE) {
                    ca = cb = cc = (tex == debugIsolateId) ? 0xFFFFFF : DEBUG_UNTEXTURED;
                } else {
                    ca = cb = cc = tex >= 0 ? debugTextureColour(tex) : DEBUG_UNTEXTURED;
                    if (tx == playerTileX && tz == playerTileZ) {
                        if (tex >= 0 || debugPlayerTexId == -2) {
                            debugPlayerTexId = tex;
                        }
                        if (debugPlayerGroundLayer == -3) {
                            debugPlayerGroundLayer = classifyGround(Pix3D.colourTable[ground.triangleColourA[i] & 0xFFFF]);
                        }
                        ca = cb = cc = 0xFFFFFF; // highlight the tile under the player
                    }
                }
                debugRgb = true;
            } else if (debugMode == DebugMode.HEIGHT) {
                ca = debugHeightColour(ground.vertexY[a]);
                cb = debugHeightColour(ground.vertexY[b]);
                cc = debugHeightColour(ground.vertexY[c]);
            } else if (ground.triangleTextureIds != null && ground.triangleTextureIds[i] >= 0
                    && ground.triangleTextureIds[i] < World.TEXTURE_HSL.length) {
                int tex = ground.triangleTextureIds[i];
                ViewPoint axisA = ground.flat && vertexCount > 3 ? projected[0] : projected[a];
                ViewPoint axisB = ground.flat && vertexCount > 3 ? projected[1] : projected[b];
                ViewPoint axisC = ground.flat && vertexCount > 3 ? projected[3] : projected[c];
                putTexturedTerrainVertex(ground.vertexX[a], ground.vertexY[a], ground.vertexZ[a], ca,
                        projected[a], axisA, axisB, axisC, tex, light);
                putTexturedTerrainVertex(ground.vertexX[b], ground.vertexY[b], ground.vertexZ[b], cb,
                        projected[b], axisA, axisB, axisC, tex, light);
                putTexturedTerrainVertex(ground.vertexX[c], ground.vertexY[c], ground.vertexZ[c], cc,
                        projected[c], axisA, axisB, axisC, tex, light);
                verts += 3;
                continue;
            }
            if (ca == TRANSPARENT_HSL) {
                continue;
            }
            // Texture shaped overlay/underlay triangles (paths, dirt patches) the same
            // way as open underlays: classify the triangle's colour -> ground texture.
            int groundLayer = !debugRgb && shouldTextureGround()
                    ? classifyGround(Pix3D.colourTable[ca & 0xFFFF]) : -1;
            if (groundLayer >= 0) {
                int la = vertMask != null ? otherLayerFromMask(vertMask[a], groundLayer) : groundLayer;
                int lb = vertMask != null ? otherLayerFromMask(vertMask[b], groundLayer) : groundLayer;
                int lc = vertMask != null ? otherLayerFromMask(vertMask[c], groundLayer) : groundLayer;
                float wa = la != groundLayer ? 0.5f : 0f;
                float wb = lb != groundLayer ? 0.5f : 0f;
                float wc = lc != groundLayer ? 0.5f : 0f;
                putGroundVertex(ground.vertexX[a], ground.vertexY[a], ground.vertexZ[a], ca, light, groundLayer, la, wa);
                putGroundVertex(ground.vertexX[b], ground.vertexY[b], ground.vertexZ[b], cb, light, groundLayer, lb, wb);
                putGroundVertex(ground.vertexX[c], ground.vertexY[c], ground.vertexZ[c], cc, light, groundLayer, lc, wc);
            } else {
                putTerrainVertex(ground.vertexX[a], ground.vertexY[a], ground.vertexZ[a], ca, debugRgb, light);
                putTerrainVertex(ground.vertexX[b], ground.vertexY[b], ground.vertexZ[b], cb, debugRgb, light);
                putTerrainVertex(ground.vertexX[c], ground.vertexY[c], ground.vertexZ[c], cc, debugRgb, light);
            }
            verts += 3;
        }
        return verts;
    }

    private int appendCapturedModels(HdCamera cam, int verts) {
        if (modelVertCount == 0) {
            return verts;
        }
        modelBuf.flip();
        while (modelBuf.remaining() >= MODEL_FLOATS_PER_VERT * 3 && verts + 3 <= MAX_VERTS) {
            float a0 = modelBuf.get();
            float sy0 = modelBuf.get();
            float depth0 = modelBuf.get();
            float r0 = modelBuf.get();
            float g0 = modelBuf.get();
            float b0 = modelBuf.get();
            float alpha0 = modelBuf.get();
            float u0 = modelBuf.get();
            float v0 = modelBuf.get();
            float tex0 = modelBuf.get();

            float a1 = modelBuf.get();
            float sy1 = modelBuf.get();
            float depth1 = modelBuf.get();
            float r1 = modelBuf.get();
            float g1 = modelBuf.get();
            float b1 = modelBuf.get();
            float alpha1 = modelBuf.get();
            float u1 = modelBuf.get();
            float v1 = modelBuf.get();
            float tex1 = modelBuf.get();

            float a2 = modelBuf.get();
            float sy2 = modelBuf.get();
            float depth2 = modelBuf.get();
            float r2 = modelBuf.get();
            float g2 = modelBuf.get();
            float b2 = modelBuf.get();
            float alpha2 = modelBuf.get();
            float u2 = modelBuf.get();
            float v2 = modelBuf.get();
            float tex2 = modelBuf.get();

            float ry0 = depth0 * cam.sinX + sy0 * cam.cosX;
            float yawB0 = depth0 * cam.cosX - sy0 * cam.sinX;
            float rx0 = a0 * cam.cosY - yawB0 * cam.sinY;
            float rz0 = a0 * cam.sinY + yawB0 * cam.cosY;
            float wx0 = cam.x + rx0;
            float wy0 = cam.y + ry0;
            float wz0 = cam.z + rz0;

            float ry1 = depth1 * cam.sinX + sy1 * cam.cosX;
            float yawB1 = depth1 * cam.cosX - sy1 * cam.sinX;
            float rx1 = a1 * cam.cosY - yawB1 * cam.sinY;
            float rz1 = a1 * cam.sinY + yawB1 * cam.cosY;
            float wx1 = cam.x + rx1;
            float wy1 = cam.y + ry1;
            float wz1 = cam.z + rz1;

            float ry2 = depth2 * cam.sinX + sy2 * cam.cosX;
            float yawB2 = depth2 * cam.cosX - sy2 * cam.sinX;
            float rx2 = a2 * cam.cosY - yawB2 * cam.sinY;
            float rz2 = a2 * cam.sinY + yawB2 * cam.cosY;
            float wx2 = cam.x + rx2;
            float wy2 = cam.y + ry2;
            float wz2 = cam.z + rz2;

            float light = triangleLight(wx0, wy0, wz0, wx1, wy1, wz1, wx2, wy2, wz2)
                    + sourceLight((wx0 + wx1 + wx2) / 3f, (wy0 + wy1 + wy2) / 3f, (wz0 + wz1 + wz2) / 3f);
            putVertex(wx0, wy0, wz0, r0 * light, g0 * light, b0 * light, alpha0, u0, v0, tex0);
            putVertex(wx1, wy1, wz1, r1 * light, g1 * light, b1 * light, alpha1, u1, v1, tex1);
            putVertex(wx2, wy2, wz2, r2 * light, g2 * light, b2 * light, alpha2, u2, v2, tex2);
            verts += 3;
        }
        if (modelCaptureOverflow || modelBuf.remaining() >= MODEL_FLOATS_PER_VERT) {
            System.err.println("[HD] model capture overflow; some model faces were skipped");
        }
        return verts;
    }

    private void putCapturedModelVertex(int x, int y, int z, int hsl, float alpha) {
        int rgb = Pix3D.colourTable[hsl & 0xFFFF];
        putCapturedModelVertexRgb(x, y, z, rgb, alpha);
    }

    private void putCapturedModelVertexRgb(int x, int y, int z, int rgb, float alpha) {
        rgb = HdMaterialResolver.resolveRgb(currentModelObjectId, -1, rgb);
        modelBuf.put(x).put(y).put(z)
                .put(channel(rgb >> 16)).put(channel(rgb >> 8)).put(channel(rgb)).put(alpha)
                .put(0f).put(0f).put(-1f);
    }

    private void putCapturedTexturedModelVertex(int x, int y, int z, int lightness, float alpha,
            float u, float v, int textureId) {
        int fallbackRgb = Pix3D.colourTable[World.TEXTURE_HSL[Math.min(textureId, World.TEXTURE_HSL.length - 1)] & 0xFFFF];
        float brightness = texBrightness(lightness)
                * HdMaterialResolver.materialLightBias(currentModelObjectId, textureId, fallbackRgb);
        modelBuf.put(x).put(y).put(z)
                .put(brightness).put(brightness).put(brightness).put(alpha)
                .put(u).put(v).put(textureId);
    }

    private void putVertex(int x, int y, int z, int hsl) {
        int rgb = Pix3D.colourTable[hsl & 0xFFFF];
        putVertex(x, y, z, channel(rgb >> 16), channel(rgb >> 8), channel(rgb), 1f);
    }

    private void putVertexRgb(int x, int y, int z, int rgb) {
        putVertex(x, y, z, channel(rgb >> 16), channel(rgb >> 8), channel(rgb), 1f);
    }

    private void putTerrainVertex(int x, int y, int z, int colour, boolean rgb) {
        putTerrainVertex(x, y, z, colour, rgb, 1f);
    }

    private void putTerrainVertex(int x, int y, int z, int colour, boolean rgb, float light) {
        if (rgb) {
            putVertex(x, y, z,
                    channel(colour >> 16) * light,
                    channel(colour >> 8) * light,
                    channel(colour) * light,
                    1f);
        } else {
            int packed = Pix3D.colourTable[colour & 0xFFFF];
            putVertex(x, y, z,
                    channel(packed >> 16) * light,
                    channel(packed >> 8) * light,
                    channel(packed) * light,
                    1f);
        }
    }

    private void putVertex(int x, int y, int z, float r, float g, float b) {
        putVertex((float) x, (float) y, (float) z, r, g, b, 1f);
    }

    private void putVertex(float x, float y, float z, float r, float g, float b, float alpha) {
        putVertex(x, y, z, r, g, b, alpha, 0f, 0f, -1f);
    }

    private void putVertex(float x, float y, float z, float r, float g, float b, float alpha,
            float u, float v, float tex) {
        putVertex(x, y, z, r, g, b, alpha, u, v, tex, tex, 0f);
    }

    private void putVertex(float x, float y, float z, float r, float g, float b, float alpha,
            float u, float v, float tex, float tex2, float blend) {
        buf.put(x).put(y).put(z).put(r).put(g).put(b).put(alpha).put(u).put(v).put(tex)
                .put(tex2).put(blend);
    }

    private void putTexturedTerrainVertex(int x, int y, int z, int lightness,
            ViewPoint p, ViewPoint texA, ViewPoint texB, ViewPoint texC, int tex) {
        putTexturedTerrainVertex(x, y, z, lightness, p, texA, texB, texC, tex, 1f);
    }

    private void putTexturedTerrainVertex(int x, int y, int z, int lightness,
            ViewPoint p, ViewPoint texA, ViewPoint texB, ViewPoint texC, int tex, float light) {
        HdMaterials.Material mat = HdMaterials.forTerrainTexture(tex);
        float brightness = texBrightness(lightness) * light * mat.brightness;
        float u, v;
        if (mat.worldTiled) {
            // Continuous world-space UVs so connected ground tiles flow across
            // boundaries instead of the legacy projective basis resetting per tile.
            u = x / mat.uvScale;
            v = z / mat.uvScale;
        } else {
            u = textureU(p.screenX, p.screenY, texA, texB, texC);
            v = textureV(p.screenX, p.screenY, texA, texB, texC);
        }
        putVertex((float) x, (float) y, (float) z,
                brightness * mat.tintR, brightness * mat.tintG, brightness * mat.tintB,
                1f, u, v, tex);
    }

    private boolean shouldApplyLighting() {
        return debugMode == DebugMode.NORMAL
                || debugMode == DebugMode.TERRAIN_ONLY
                || debugMode == DebugMode.MODEL_ONLY;
    }

    private boolean shouldApplyAtmosphere() {
        return debugMode == DebugMode.NORMAL
                || debugMode == DebugMode.TERRAIN_ONLY
                || debugMode == DebugMode.MODEL_ONLY;
    }

    private float triangleLight(float x0, float y0, float z0,
            float x1, float y1, float z1,
            float x2, float y2, float z2) {
        if (!shouldApplyLighting()) {
            return 1f;
        }
        float ax = x1 - x0;
        float ay = y1 - y0;
        float az = z1 - z0;
        float bx = x2 - x0;
        float by = y2 - y0;
        float bz = z2 - z0;
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 0.0001f) {
            return 1f;
        }
        nx /= len;
        ny /= len;
        nz /= len;
        float dot = Math.abs(nx * SUN_X + ny * SUN_Y + nz * SUN_Z);
        return clampLight(AMBIENT + SUN_POWER * dot);
    }

    private float sourceLight(float x, float y, float z) {
        if (!shouldApplyLighting() || sourceLights.isEmpty()) {
            return 0f;
        }
        float add = 0f;
        for (HdMaterialResolver.SourceLight light : sourceLights) {
            float dx = x - light.x();
            float dy = y - light.y();
            float dz = z - light.z();
            float radius = light.radius();
            float dist2 = dx * dx + dy * dy + dz * dz;
            if (dist2 >= radius * radius) {
                continue;
            }
            float falloff = 1f - (float) Math.sqrt(dist2) / radius;
            add += falloff * falloff * light.strength();
        }
        return Math.min(0.35f, add);
    }

    private static float clampLight(float light) {
        return Math.max(0.45f, Math.min(1.18f, light));
    }

    private static float channel(int value) {
        return (value & 0xFF) / 255f;
    }

    /** Mirrors the texture-id debug line to logs/hd-texture-ids.txt so it's readable without a console. */
    private static void writeDebugTexIdsFile(String line) {
        try {
            String dir = System.getProperty("rs254.logDir", "logs");
            java.io.File f = new java.io.File(dir);
            f.mkdirs();
            try (java.io.FileWriter w = new java.io.FileWriter(new java.io.File(f, "hd-texture-ids.txt"))) {
                w.write(line + System.lineSeparator());
            }
        } catch (Throwable ignored) {
        }
    }

    private static int debugTileColour(int tx, int tz) {
        int hash = tx * 73428767 ^ tz * 91227153;
        int r = 64 + (hash & 0x7F);
        int g = 64 + ((hash >> 7) & 0x7F);
        int b = 64 + ((hash >> 14) & 0x7F);
        return (r << 16) | (g << 8) | b;
    }

    private static int debugTextureColour(int textureId) {
        int hash = textureId * 1103515245 + 12345;
        int r = 80 + (hash & 0xAF);
        int g = 80 + ((hash >> 8) & 0xAF);
        int b = 80 + ((hash >> 16) & 0xAF);
        return (r << 16) | (g << 8) | b;
    }

    private static int debugHeightColour(int height) {
        int v = Math.max(0, Math.min(255, 96 + height / 4));
        int r = Math.max(0, Math.min(255, v - 64));
        int g = v;
        int b = 255 - v / 2;
        return (r << 16) | (g << 8) | b;
    }

    /** Matches {@link World#mulLightness(int, int)} for texture fallback coloring. */
    private static int mulLightness(int hsl, int baseHsl) {
        int lightness = 127 - hsl;
        int out = lightness * (baseHsl & 0x7F) / 160;
        if (out < 2) {
            out = 2;
        } else if (out > 126) {
            out = 126;
        }
        return (baseHsl & 0xFF80) + out;
    }

    private static float legacyAlpha(int trans) {
        return (256 - trans) / 256f;
    }

    private static float texBrightness(int hsl) {
        int shift = Math.max(0, Math.min(7, hsl >> 6));
        return 1f / (1 << shift);
    }

    private static float textureU(int screenX, int screenY, ViewPoint a, ViewPoint b, ViewPoint c) {
        int dX01 = a.viewX - b.viewX;
        int dX20 = c.viewX - a.viewX;
        int dY01 = a.viewY - b.viewY;
        int dY20 = c.viewY - a.viewY;
        int dZ01 = a.viewZ - b.viewZ;
        int dZ20 = c.viewZ - a.viewZ;
        long uA = ((long) dX20 * a.viewY - (long) dY20 * a.viewX) << 14;
        long uB = ((long) dY20 * a.viewZ - (long) dZ20 * a.viewY) << 8;
        long uC = ((long) dZ20 * a.viewX - (long) dX20 * a.viewZ) << 5;
        long wA = ((long) dY01 * dX20 - (long) dX01 * dY20) << 14;
        long wB = ((long) dZ01 * dY20 - (long) dY01 * dZ20) << 8;
        long wC = ((long) dX01 * dZ20 - (long) dZ01 * dX20) << 5;
        int dx = screenX - a.centerX;
        int dy = screenY - a.centerY;
        long u = uA + uC * dy + (uB >> 3) * dx;
        long w = wA + wC * dy + (wB >> 3) * dx;
        return w != 0 ? (float) u / w : 0f;
    }

    private static float textureV(int screenX, int screenY, ViewPoint a, ViewPoint b, ViewPoint c) {
        int dX01 = a.viewX - b.viewX;
        int dX20 = c.viewX - a.viewX;
        int dY01 = a.viewY - b.viewY;
        int dY20 = c.viewY - a.viewY;
        int dZ01 = a.viewZ - b.viewZ;
        int dZ20 = c.viewZ - a.viewZ;
        long vA = ((long) dX01 * a.viewY - (long) dY01 * a.viewX) << 14;
        long vB = ((long) dY01 * a.viewZ - (long) dZ01 * a.viewY) << 8;
        long vC = ((long) dZ01 * a.viewX - (long) dX01 * a.viewZ) << 5;
        long wA = ((long) dY01 * dX20 - (long) dX01 * dY20) << 14;
        long wB = ((long) dZ01 * dY20 - (long) dY01 * dZ20) << 8;
        long wC = ((long) dX01 * dZ20 - (long) dZ01 * dX20) << 5;
        int dx = screenX - a.centerX;
        int dy = screenY - a.centerY;
        long v = vA + vC * dy + (vB >> 3) * dx;
        long w = wA + wC * dy + (wB >> 3) * dx;
        return w != 0 ? (float) v / w : 0f;
    }

    private void ensureTexturesUploaded() {
        if (texturesUploaded) {
            return;
        }
        textureSize = Pix3D.lowMem ? 64 : 128;
        rlhdTextureResources = loadRlhdTextureManifest();
        int requestedLayers = MAX_TEXTURES + GROUND_TEX_COUNT + rlhdTextureResources.size();
        int maxArrayLayers = glGetInteger(GL_MAX_ARRAY_TEXTURE_LAYERS);
        totalTextureLayers = Math.min(requestedLayers, maxArrayLayers);
        if (totalTextureLayers < requestedLayers) {
            System.err.println("[HD] GPU texture array layer cap " + maxArrayLayers
                    + " is below requested RLHD texture layers " + requestedLayers
                    + "; only the first " + Math.max(0, totalTextureLayers - MAX_TEXTURES - GROUND_TEX_COUNT)
                    + " RLHD texture resources will be uploaded.");
        }
        texArray = glGenTextures();
        glBindTexture(GL_TEXTURE_2D_ARRAY, texArray);
        glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, textureSize, textureSize, totalTextureLayers,
                0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        ByteBuffer rgba = MemoryUtil.memAlloc(textureSize * textureSize * 4);
        try {
            for (int tex = 0; tex < MAX_TEXTURES; tex++) {
                rgba.clear();
                if (Pix3D.textures[tex] != null && Pix3D.texturePalette[tex] != null) {
                    int[] texels = Pix3D.getTexels(tex);
                    boolean transparent = Pix3D.textureTranslucent[tex];
                    for (int i = 0; i < textureSize * textureSize; i++) {
                        int c = texels[i];
                        rgba.put((byte) (c >> 16));
                        rgba.put((byte) (c >> 8));
                        rgba.put((byte) c);
                        rgba.put(transparent && c == 0 ? (byte) 0 : (byte) -1);
                    }
                } else {
                    int rgb = Pix3D.colourTable[World.TEXTURE_HSL[Math.min(tex, World.TEXTURE_HSL.length - 1)] & 0xFFFF];
                    for (int i = 0; i < textureSize * textureSize; i++) {
                        rgba.put((byte) (rgb >> 16));
                        rgba.put((byte) (rgb >> 8));
                        rgba.put((byte) rgb);
                        rgba.put((byte) -1);
                    }
                }
                rgba.flip();
                glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, tex, textureSize, textureSize, 1,
                        GL_RGBA, GL_UNSIGNED_BYTE, rgba);
            }
            // HD ground textures into the slots above the vanilla 50.
            groundTexturesOk = true;
            for (int i = 0; i < GROUND_TEX_COUNT; i++) {
                if (MAX_TEXTURES + i >= totalTextureLayers || !uploadTextureResource(GROUND_TEX_RES[i], MAX_TEXTURES + i, rgba, true)) {
                    groundTexturesOk = false;
                }
            }
            int rlhdBaseLayer = MAX_TEXTURES + GROUND_TEX_COUNT;
            int uploadedRlhd = 0;
            for (int i = 0; i < rlhdTextureResources.size() && rlhdBaseLayer + i < totalTextureLayers; i++) {
                if (uploadTextureResource(rlhdTextureResources.get(i), rlhdBaseLayer + i, rgba, false)) {
                    uploadedRlhd++;
                }
            }
            System.out.println("[HD] uploaded " + uploadedRlhd + " RLHD texture resources into texture-array layers");
        } finally {
            MemoryUtil.memFree(rgba);
        }
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);
        waterNormalTex = load2DTexture(WATER_NORMAL_RES);
        waterFoamTex = load2DTexture(WATER_FOAM_RES);
        texturesUploaded = true;
    }

    private java.util.List<String> loadRlhdTextureManifest() {
        java.util.ArrayList<String> resources = new java.util.ArrayList<>();
        try (java.io.InputStream in = HdSceneRenderer.class.getResourceAsStream(RLHD_TEX_MANIFEST)) {
            if (in == null) {
                System.err.println("[HD] RLHD texture manifest not found on classpath: " + RLHD_TEX_MANIFEST);
                return resources;
            }
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        resources.add("/hd/textures/" + line);
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("[HD] RLHD texture manifest load error: " + t);
        }
        return resources;
    }

    /** Loads an RLHD bitmap from the classpath, scales to textureSize, uploads to a layer. */
    private boolean uploadTextureResource(String resource, int layer, ByteBuffer rgba, boolean forceOpaque) {
        try (java.io.InputStream in = HdSceneRenderer.class.getResourceAsStream(resource)) {
            if (in == null) {
                System.err.println("[HD] texture not found on classpath: " + resource);
                return false;
            }
            java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(in);
            if (src == null) {
                System.err.println("[HD] texture failed to decode: " + resource);
                return false;
            }
            java.awt.image.BufferedImage scaled = new java.awt.image.BufferedImage(
                    textureSize, textureSize, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(src, 0, 0, textureSize, textureSize, null);
            g.dispose();
            int[] px = scaled.getRGB(0, 0, textureSize, textureSize, null, 0, textureSize);
            rgba.clear();
            for (int p : px) {
                rgba.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p)
                        .put(forceOpaque ? (byte) -1 : (byte) (p >> 24));
            }
            rgba.flip();
            glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, textureSize, textureSize, 1,
                    GL_RGBA, GL_UNSIGNED_BYTE, rgba);
            return true;
        } catch (Throwable t) {
            System.err.println("[HD] texture load error (" + resource + "): " + t);
            return false;
        }
    }

    /** Loads a standalone RGBA 2D texture (e.g. a normal map) from the classpath. Returns 0 on failure. */
    private int load2DTexture(String resource) {
        try (java.io.InputStream in = HdSceneRenderer.class.getResourceAsStream(resource)) {
            if (in == null) {
                System.err.println("[HD] texture not found on classpath: " + resource);
                return 0;
            }
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(in);
            if (img == null) {
                return 0;
            }
            int w = img.getWidth(), h = img.getHeight();
            int[] px = img.getRGB(0, 0, w, h, null, 0, w);
            ByteBuffer bb = MemoryUtil.memAlloc(w * h * 4);
            try {
                for (int p : px) {
                    bb.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) (p >> 24));
                }
                bb.flip();
                int tex = glGenTextures();
                glBindTexture(GL_TEXTURE_2D, tex);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, bb);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
                glBindTexture(GL_TEXTURE_2D, 0);
                return tex;
            } finally {
                MemoryUtil.memFree(bb);
            }
        } catch (Throwable t) {
            System.err.println("[HD] 2D texture load error (" + resource + "): " + t);
            return 0;
        }
    }

    /**
     * Classifies a vanilla underlay base colour (packed RGB) into an HD ground texture
     * layer, or -1 to leave it flat. Heuristic by hue: green -> grass, warm/bright ->
     * sand, brownish -> dirt.
     */
    private int classifyGround(int rgb) {
        if (!groundTexturesOk || rgb == 0) {
            return -1;
        }
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        // Green dominant -> grass.
        if (g > r && g >= b) {
            return GRASS_LAYER;
        }
        // Bright warm (high r,g, lower b) -> sand.
        if (r > 120 && g > 105 && b > 70 && r >= g && g >= b) {
            return SAND_LAYER;
        }
        // Clearly bluish/cold (blue dominant) -> leave flat; likely water-edge/special.
        if (b > r && b > g) {
            return -1;
        }
        // Everything else (grey, brown, tan, dark dirt, shadowed ground) -> dirt.
        return DIRT_LAYER;
    }

    private static String groundLayerName(int layer) {
        if (layer == GRASS_LAYER) return "GRASS";
        if (layer == DIRT_LAYER) return "DIRT";
        if (layer == SAND_LAYER) return "SAND";
        return "none (flat)";
    }

    private boolean shouldTextureGround() {
        return debugMode == DebugMode.NORMAL
                || debugMode == DebugMode.TERRAIN_ONLY
                || debugMode == DebugMode.MODEL_ONLY
                || debugMode == DebugMode.WATER_SHORE_MASK
                || debugMode == DebugMode.WATER_FOAM_NOISE
                || debugMode == DebugMode.WATER_FOAM_FINAL;
    }

    private int waterDebugMode() {
        if (debugMode == DebugMode.WATER_SHORE_MASK) return 1;
        if (debugMode == DebugMode.WATER_FOAM_NOISE) return 2;
        if (debugMode == DebugMode.WATER_FOAM_FINAL) return 3;
        return 0;
    }

    /** Emits a ground-textured terrain vertex: HD ground texture modulated by the tile's HSL colour. */
    private void putGroundVertex(int x, int y, int z, int hsl, float light, int layer) {
        putGroundVertex(x, y, z, hsl, light, layer, layer, 0f);
    }

    /** Emits one water-surface vertex into the separate transparent water buffer. */
    private void putWaterVertex(int x, int y, int z) {
        putWaterVertex(x, y, z, 0f, 0f, 0f, 0f);
    }

    private void putWaterVertex(int x, int y, int z, float west, float east, float south, float north) {
        if (waterVertCount + 1 > MAX_WATER_VERTS) {
            return;
        }
        waterBuf.put(x).put(y + WATER_SURFACE_LIFT).put(z)
                .put(west).put(east).put(south).put(north)
                .put(0f).put(0f)
                .put((float) WATER_TEXTURE_ID).put((float) WATER_TEXTURE_ID).put(0f);
        waterVertCount++;
    }

    private void putWaterQuad(Square[][] tiles, int tx, int tz,
            int x0, int z0, int x1, int z1, int hSW, int hSE, int hNE, int hNW) {
        float west = isLandNeighbour(tiles, tx - 1, tz) ? 1f : 0f;
        float east = isLandNeighbour(tiles, tx + 1, tz) ? 1f : 0f;
        float south = isLandNeighbour(tiles, tx, tz - 1) ? 1f : 0f;
        float north = isLandNeighbour(tiles, tx, tz + 1) ? 1f : 0f;
        putWaterVertex(x1, hNE, z1, 0f, east, 0f, north);
        putWaterVertex(x0, hNW, z1, west, 0f, 0f, north);
        putWaterVertex(x1, hSE, z0, 0f, east, south, 0f);
        putWaterVertex(x0, hSW, z0, west, 0f, south, 0f);
        putWaterVertex(x1, hSE, z0, 0f, east, south, 0f);
        putWaterVertex(x0, hNW, z1, west, 0f, 0f, north);
    }

    private boolean isLandNeighbour(Square[][] tiles, int tx, int tz) {
        return tx >= 0 && tz >= 0 && tx < tiles.length && tiles[tx] != null
                && tz < tiles[tx].length && tiles[tx][tz] != null && !isWaterSquare(tiles[tx][tz]);
    }

    private boolean isWaterSquare(Square tile) {
        if (tile == null) {
            return false;
        }
        if (tile.quickGround != null && tile.quickGround.textureId == WATER_TEXTURE_ID) {
            return true;
        }
        if (tile.ground != null && tile.ground.triangleTextureIds != null) {
            for (int id : tile.ground.triangleTextureIds) {
                if (id == WATER_TEXTURE_ID) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int lerpHeight(int a, int b, float t) {
        return Math.round(a + (b - a) * t);
    }

    /** One opaque riverbed vertex (sandy bottom) lowered below the water surface. */
    private void putRiverbedVertex(int x, int y, int z) {
        float tex = groundTexturesOk ? (float) SAND_LAYER : -1f;
        float u = x / GROUND_UV_SCALE;
        float v = z / GROUND_UV_SCALE;
        putVertex((float) x, (float) (y + WATER_DEPTH_OFFSET), (float) z,
                RIVERBED_R, RIVERBED_G, RIVERBED_B, 1f, u, v, tex);
    }

    /** Emits the opaque riverbed quad under a water tile. Returns the opaque vert count. */
    private int putRiverbedQuad(int x0, int z0, int x1, int z1, int hSW, int hSE, int hNE, int hNW) {
        putRiverbedVertex(x1, hNE, z1);
        putRiverbedVertex(x0, hNW, z1);
        putRiverbedVertex(x1, hSE, z0);
        putRiverbedVertex(x0, hSW, z0);
        putRiverbedVertex(x1, hSE, z0);
        putRiverbedVertex(x0, hNW, z1);
        return 6;
    }

    /** As above, but feathered toward a second ground texture by {@code blend} (0..1). */
    private void putGroundVertex(int x, int y, int z, int hsl, float light, int layer, int layer2, float blend) {
        int rgb = Pix3D.colourTable[hsl & 0xFFFF];
        float u = x / GROUND_UV_SCALE;
        float v = z / GROUND_UV_SCALE;
        putVertex((float) x, (float) y, (float) z,
                channel(rgb >> 16) * light, channel(rgb >> 8) * light, channel(rgb) * light,
                1f, u, v, layer, layer2, blend);
    }

    /** Ground texture layer of any tile (for blend-weight neighbour sampling), or -1. */
    private int layerAt(Square[][] planeTiles, int tx, int tz) {
        if (tx < 0 || tz < 0 || tx >= planeTiles.length || planeTiles[tx] == null
                || tz >= planeTiles[tx].length) {
            return -1;
        }
        Square t = planeTiles[tx][tz];
        if (t == null) {
            return -1;
        }
        if (t.quickGround != null) {
            return classifyGround(t.quickGround.rgb);
        }
        if (t.ground != null) {
            int c = t.ground.overlayColour != 0 ? t.ground.overlayColour : t.ground.underlayColour;
            return classifyGround(c);
        }
        return -1;
    }

    private static int layerBit(int layer) {
        if (layer == GRASS_LAYER) return 1;
        if (layer == DIRT_LAYER) return 2;
        if (layer == SAND_LAYER) return 4;
        return 0;
    }

    /** The ground texture layer a single Ground triangle would use, or -1 (vanilla-textured/transparent/non-ground). */
    private int groundLayerForTri(Ground ground, int i) {
        if (ground.triangleColourA[i] == TRANSPARENT_HSL) {
            return -1;
        }
        if (ground.triangleTextureIds != null && ground.triangleTextureIds[i] >= 0
                && ground.triangleTextureIds[i] < World.TEXTURE_HSL.length) {
            return -1;
        }
        return classifyGround(Pix3D.colourTable[ground.triangleColourA[i] & 0xFFFF]);
    }

    /** Given the set of layers touching a vertex (bitmask), return one that differs from thisLayer, else thisLayer. */
    private static int otherLayerFromMask(int mask, int thisLayer) {
        int rem = mask & ~layerBit(thisLayer);
        if ((rem & 1) != 0) return GRASS_LAYER;
        if ((rem & 2) != 0) return DIRT_LAYER;
        if ((rem & 4) != 0) return SAND_LAYER;
        return thisLayer;
    }

    // The 4 tiles meeting at each tile corner: SW, SE, NE, NW.
    private static final int[][][] CORNER_NEIGHBORS = {
            {{-1, -1}, {0, -1}, {-1, 0}, {0, 0}},
            {{0, -1}, {1, -1}, {0, 0}, {1, 0}},
            {{0, 0}, {1, 0}, {0, 1}, {1, 1}},
            {{-1, 0}, {0, 0}, {-1, 1}, {0, 1}},
    };
    private final int[] cbSecondary = new int[4];
    private final float[] cbWeight = new float[4];

    /**
     * For each tile corner, finds the dominant neighbouring ground material that differs
     * from this tile's, and a blend weight (fraction of the 4 corner tiles that have it).
     * A straight grass|dirt edge yields weight 0.5 on both sides of the shared corner, so
     * the two tiles feather into each other symmetrically.
     */
    private void computeCornerBlends(Square[][] planeTiles, int tx, int tz, int thisLayer) {
        for (int c = 0; c < 4; c++) {
            int cntGrass = 0, cntDirt = 0, cntSand = 0;
            for (int[] off : CORNER_NEIGHBORS[c]) {
                int lay = layerAt(planeTiles, tx + off[0], tz + off[1]);
                if (lay == GRASS_LAYER) cntGrass++;
                else if (lay == DIRT_LAYER) cntDirt++;
                else if (lay == SAND_LAYER) cntSand++;
            }
            int secondary = thisLayer, secCount = 0;
            if (thisLayer != GRASS_LAYER && cntGrass > secCount) { secondary = GRASS_LAYER; secCount = cntGrass; }
            if (thisLayer != DIRT_LAYER && cntDirt > secCount) { secondary = DIRT_LAYER; secCount = cntDirt; }
            if (thisLayer != SAND_LAYER && cntSand > secCount) { secondary = SAND_LAYER; secCount = cntSand; }
            cbSecondary[c] = secondary;
            cbWeight[c] = secCount / 4f;
        }
    }

    /** Camera-space depth of a world point, matching renderGround's var25. */
    private static float depth(HdCamera cam, int wx, int wy, int wz) {
        float rx = wx - cam.x, ry = wy - cam.y, rz = wz - cam.z;
        float b = rz * cam.cosY - rx * cam.sinY;
        return ry * cam.sinX + b * cam.cosX;
    }

    private static ViewPoint project(HdCamera cam, int wx, int wy, int wz) {
        float rx = wx - cam.x;
        float ry = wy - cam.y;
        float rz = wz - cam.z;
        float viewX = rz * cam.sinY + rx * cam.cosY;
        float yawB = rz * cam.cosY - rx * cam.sinY;
        float viewZ = ry * cam.sinX + yawB * cam.cosX;
        float viewY = ry * cam.cosX - yawB * cam.sinX;
        int centerX = Math.round(cam.projX);
        int centerY = Math.round(cam.projY);
        int sx = Math.round(cam.projX + (viewX * 512f) / viewZ);
        int sy = Math.round(cam.projY + (viewY * 512f) / viewZ);
        return new ViewPoint(centerX, centerY, sx, sy, Math.round(viewX), Math.round(viewY), Math.round(viewZ));
    }

    void dispose() {
        if (initialised) {
            glDeleteBuffers(vbo);
            glDeleteVertexArrays(vao);
            glDeleteProgram(prog);
            if (waterProg != 0) glDeleteProgram(waterProg);
            if (texArray != 0) {
                glDeleteTextures(texArray);
            }
            if (msaaFbo != 0) glDeleteFramebuffers(msaaFbo);
            if (msaaColorRb != 0) glDeleteRenderbuffers(msaaColorRb);
            if (msaaDepthRb != 0) glDeleteRenderbuffers(msaaDepthRb);
            if (sceneFbo != 0) glDeleteFramebuffers(sceneFbo);
            if (sceneColorTex != 0) glDeleteTextures(sceneColorTex);
            if (sceneDepthTex != 0) glDeleteTextures(sceneDepthTex);
            if (waterNormalTex != 0) glDeleteTextures(waterNormalTex);
            if (waterFoamTex != 0) glDeleteTextures(waterFoamTex);
        }
        MemoryUtil.memFree(buf);
        MemoryUtil.memFree(modelBuf);
        MemoryUtil.memFree(waterBuf);
    }

    private static int buildProgram(String vertSrc, String fragSrc) {
        int vs = compileShader(GL_VERTEX_SHADER, vertSrc);
        int fs = compileShader(GL_FRAGMENT_SHADER, fragSrc);
        int p = glCreateProgram();
        glAttachShader(p, vs);
        glAttachShader(p, fs);
        glLinkProgram(p);
        if (glGetProgrami(p, GL_LINK_STATUS) == GL_FALSE) {
            throw new RuntimeException("HD shader link:\n" + glGetProgramInfoLog(p));
        }
        glDeleteShader(vs);
        glDeleteShader(fs);
        return p;
    }

    private static int compileShader(int type, String src) {
        int id = glCreateShader(type);
        glShaderSource(id, src);
        glCompileShader(id);
        if (glGetShaderi(id, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new RuntimeException("HD shader compile:\n" + glGetShaderInfoLog(id));
        }
        return id;
    }
}
