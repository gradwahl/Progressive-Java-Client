package com.gradwahl.rs254.gl;

import jagex2.client.Client;
import jagex2.dash3d.World;

/**
 * Progressive-native scene snapshot for the HD world pass.
 *
 * <p>This is the boundary future RLHD-inspired systems should target instead of
 * importing RuneLite's client API. It captures the rev-254 scene, active plane,
 * camera, visible tile window, and model/object/entity source inventory at the
 * moment the legacy scene render ran.
 */
final class HdSceneFrame {
    final World world;
    final int plane;
    final int minTileX;
    final int maxTileX;
    final int minTileZ;
    final int maxTileZ;
    final HdSceneRenderer.HdCamera camera;
    final HdSceneSources sources;

    private HdSceneFrame(World world, int plane, int minTileX, int maxTileX,
            int minTileZ, int maxTileZ, HdSceneRenderer.HdCamera camera,
            HdSceneSources sources) {
        this.world = world;
        this.plane = plane;
        this.minTileX = minTileX;
        this.maxTileX = maxTileX;
        this.minTileZ = minTileZ;
        this.maxTileZ = maxTileZ;
        this.camera = camera;
        this.sources = sources;
    }

    static HdSceneFrame capture(World world, int plane, HdSceneRenderer.HdCamera sourceCamera, Client client) {
        HdSceneRenderer.HdCamera camera = new HdSceneRenderer.HdCamera();
        camera.x = sourceCamera.x;
        camera.y = sourceCamera.y;
        camera.z = sourceCamera.z;
        camera.sinX = sourceCamera.sinX;
        camera.cosX = sourceCamera.cosX;
        camera.sinY = sourceCamera.sinY;
        camera.cosY = sourceCamera.cosY;
        camera.projX = sourceCamera.projX;
        camera.projY = sourceCamera.projY;
        int minTileX = World.minX;
        int maxTileX = World.maxX;
        int minTileZ = World.minZ;
        int maxTileZ = World.maxZ;
        HdSceneSources sources = HdSceneSources.capture(world, plane, minTileX, maxTileX, minTileZ, maxTileZ, client);
        return new HdSceneFrame(world, plane, minTileX, maxTileX, minTileZ, maxTileZ, camera, sources);
    }
}
