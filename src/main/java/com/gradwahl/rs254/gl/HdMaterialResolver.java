package com.gradwahl.rs254.gl;

import java.util.ArrayList;
import java.util.List;

/**
 * Small progressive-native material/light resolver.
 *
 * <p>This is intentionally not an RLHD data loader yet. It is the local decision
 * point where rev-254 ids, face colours, and textures are classified before
 * later RLHD override tables are translated into this client's terms.
 */
final class HdMaterialResolver {
    private static final int MATERIAL_DEFAULT = 0;
    private static final int MATERIAL_FOLIAGE = 1;
    private static final int MATERIAL_WOOD = 2;
    private static final int MATERIAL_STONE = 3;
    private static final int MATERIAL_METAL = 4;
    private static final int MATERIAL_FIRE = 5;

    private HdMaterialResolver() {
    }

    static int materialForRgb(int objectId, int textureId, int rgb) {
        if (textureId == 1 || isWaterLike(rgb)) {
            return MATERIAL_DEFAULT;
        }
        if (isFireLike(rgb) || isKnownLightObject(objectId)) {
            return MATERIAL_FIRE;
        }
        if (isFoliageLike(rgb)) {
            return MATERIAL_FOLIAGE;
        }
        if (isWoodLike(rgb)) {
            return MATERIAL_WOOD;
        }
        if (isMetalLike(rgb)) {
            return MATERIAL_METAL;
        }
        if (isStoneLike(rgb)) {
            return MATERIAL_STONE;
        }
        return MATERIAL_DEFAULT;
    }

    static int resolveRgb(int objectId, int textureId, int rgb) {
        int material = materialForRgb(objectId, textureId, rgb);
        return switch (material) {
            case MATERIAL_FOLIAGE -> tint(rgb, 0.86f, 1.08f, 0.82f);
            case MATERIAL_WOOD -> tint(rgb, 1.04f, 0.92f, 0.76f);
            case MATERIAL_STONE -> tint(rgb, 0.92f, 0.94f, 0.96f);
            case MATERIAL_METAL -> tint(rgb, 1.08f, 1.08f, 1.06f);
            case MATERIAL_FIRE -> tint(rgb, 1.25f, 1.08f, 0.76f);
            default -> rgb;
        };
    }

    static float materialLightBias(int objectId, int textureId, int rgb) {
        return switch (materialForRgb(objectId, textureId, rgb)) {
            case MATERIAL_FOLIAGE -> 0.96f;
            case MATERIAL_WOOD -> 0.98f;
            case MATERIAL_STONE -> 0.94f;
            case MATERIAL_METAL -> 1.10f;
            case MATERIAL_FIRE -> 1.22f;
            default -> 1.0f;
        };
    }

    static List<SourceLight> lightsFrom(HdSceneSources sources) {
        if (sources == null || sources.sceneObjects.isEmpty()) {
            return List.of();
        }
        ArrayList<SourceLight> lights = new ArrayList<>();
        for (HdSceneSources.SceneObject object : sources.sceneObjects) {
            int id = object.objectId();
            if (isKnownLightObject(id) || isLikelyLightType(object.typecode2())) {
                float radius = isKnownLightObject(id) ? 390f : 260f;
                lights.add(new SourceLight(object.x(), object.y() - 80f, object.z(),
                        radius, 1.00f, 0.72f, 0.42f, 0.30f));
            }
        }
        return lights;
    }

    private static boolean isKnownLightObject(int objectId) {
        // Common 2004-era fire/light object id bands. This is deliberately tiny;
        // translated RLHD light tables can replace/extend it later.
        return objectId == 10 || objectId == 114 || objectId == 115 || objectId == 116
                || objectId == 117 || objectId == 118 || objectId == 119
                || objectId == 2732 || objectId == 3038 || objectId == 3769;
    }

    private static boolean isLikelyLightType(int typecode2) {
        return typecode2 == 10 || typecode2 == 11 || typecode2 == 22;
    }

    private static boolean isFoliageLike(int rgb) {
        int r = rgb >> 16 & 0xFF;
        int g = rgb >> 8 & 0xFF;
        int b = rgb & 0xFF;
        return g > r + 12 && g > b + 8;
    }

    private static boolean isWoodLike(int rgb) {
        int r = rgb >> 16 & 0xFF;
        int g = rgb >> 8 & 0xFF;
        int b = rgb & 0xFF;
        return r > g && g > b && r - b > 35;
    }

    private static boolean isStoneLike(int rgb) {
        int r = rgb >> 16 & 0xFF;
        int g = rgb >> 8 & 0xFF;
        int b = rgb & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return max - min < 24 && max > 45 && max < 190;
    }

    private static boolean isMetalLike(int rgb) {
        int r = rgb >> 16 & 0xFF;
        int g = rgb >> 8 & 0xFF;
        int b = rgb & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return max - min < 18 && max >= 150;
    }

    private static boolean isFireLike(int rgb) {
        int r = rgb >> 16 & 0xFF;
        int g = rgb >> 8 & 0xFF;
        int b = rgb & 0xFF;
        return r > 160 && g > 70 && b < 65;
    }

    private static boolean isWaterLike(int rgb) {
        int r = rgb >> 16 & 0xFF;
        int g = rgb >> 8 & 0xFF;
        int b = rgb & 0xFF;
        return b > r + 12 && b >= g;
    }

    private static int tint(int rgb, float rr, float gg, float bb) {
        int r = clamp(Math.round(((rgb >> 16) & 0xFF) * rr));
        int g = clamp(Math.round(((rgb >> 8) & 0xFF) * gg));
        int b = clamp(Math.round((rgb & 0xFF) * bb));
        return r << 16 | g << 8 | b;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    record SourceLight(float x, float y, float z, float radius,
            float r, float g, float b, float strength) {
    }
}
