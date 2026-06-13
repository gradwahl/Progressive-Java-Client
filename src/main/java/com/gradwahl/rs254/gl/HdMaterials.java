package com.gradwahl.rs254.gl;

/**
 * Minimal terrain material registry for the HD renderer (Phase 2, increment 13).
 *
 * <p>Maps a rev-254 vanilla terrain texture id to a small set of rendering rules.
 * This is the seed of the larger 117HD-style material system: for now it only
 * controls UV behaviour and a brightness multiplier, but it gives the renderer a
 * single, data-driven place to differentiate grass / stone / path / wood / water
 * instead of treating every textured terrain face identically.
 *
 * <p>The important rule here is {@link Material#worldTiled}. Legacy terrain UVs are
 * derived from the software-renderer's per-triangle projective basis, which makes a
 * texture restart at every tile boundary (the "cobblestone phase reset"). A
 * world-tiled material instead derives UVs straight from world X/Z, so connected
 * ground tiles flow continuously the way 117HD terrain does.
 *
 * <p>Texture ids are rev-254 cache ids, NOT OSRS ids — fill in the override table
 * below using the {@code Ctrl+H} "texture-id colour" debug mode to identify which
 * id is grass / dirt / path / etc., then give each its own scale/brightness.
 */
final class HdMaterials {

    /** World units per texture repeat. A rev-254 tile is 128 units wide. */
    static final float TILE_SIZE = 128f;

    /** Per-texture rules. */
    static final class Material {
        /** If true, UVs come from world X/Z (continuous across tiles). */
        final boolean worldTiled;
        /** World units per full texture repeat when {@link #worldTiled}. */
        final float uvScale;
        /** Multiplier applied to the face's base brightness. */
        final float brightness;
        /** Per-channel colour multiplier (1,1,1 = untinted). Used to recolour e.g. water. */
        final float tintR, tintG, tintB;

        Material(boolean worldTiled, float uvScale, float brightness) {
            this(worldTiled, uvScale, brightness, 1f, 1f, 1f);
        }

        Material(boolean worldTiled, float uvScale, float brightness,
                 float tintR, float tintG, float tintB) {
            this.worldTiled = worldTiled;
            this.uvScale = uvScale;
            this.brightness = brightness;
            this.tintR = tintR;
            this.tintG = tintG;
            this.tintB = tintB;
        }
    }

    /**
     * Default terrain material: world-tiled at one repeat per tile, full brightness.
     * One repeat per 128-unit tile roughly matches the vanilla texture density while
     * staying continuous across tile boundaries.
     */
    private static final Material DEFAULT = new Material(true, TILE_SIZE, 1f);

    /**
     * Per-texture-id overrides. Empty for now — every terrain texture uses the
     * world-tiled DEFAULT. Add entries here as ids are identified, e.g.
     * <pre>OVERRIDES[GRASS_ID] = new Material(true, 256f, 1.05f);</pre>
     * to make grass tile at one repeat per two tiles and read slightly brighter.
     */
    private static final Material[] OVERRIDES = new Material[256];

    static {
        // Identified rev-254 terrain texture ids (via Ctrl+H texture-isolate):
        //   1  = water   46 = walkable path/cobblestone   3 = other textured ground
        //   grass/dirt = untextured underlay (no texture id, handled elsewhere)

        // Water (STOPGAP tint until a real water material/shader exists).
        // RLHD water (WaterType.java) is a semi-transparent surface that reflects
        // the pale sky and fades to teal depthColor #00758E with a tan foam edge —
        // a muted steel-blue overall, NOT a saturated cyan. Approximate that here by
        // lightening (vanilla water texture is dark) and pulling to a desaturated
        // teal-blue. World-tiled so the surface flows across the pond.
        OVERRIDES[1] = new Material(true, TILE_SIZE, 1.2f, 0.52f, 0.74f, 0.88f);
    }

    static Material forTerrainTexture(int textureId) {
        if (textureId >= 0 && textureId < OVERRIDES.length && OVERRIDES[textureId] != null) {
            return OVERRIDES[textureId];
        }
        return DEFAULT;
    }

    private HdMaterials() {
    }
}
