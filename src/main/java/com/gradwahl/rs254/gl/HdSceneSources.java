package com.gradwahl.rs254.gl;

import jagex2.client.Client;
import jagex2.dash3d.ClientEntity;
import jagex2.dash3d.ClientNpc;
import jagex2.dash3d.ClientPlayer;
import jagex2.dash3d.Decor;
import jagex2.dash3d.Ground;
import jagex2.dash3d.GroundDecor;
import jagex2.dash3d.GroundObject;
import jagex2.dash3d.ModelSource;
import jagex2.dash3d.QuickGround;
import jagex2.dash3d.Sprite;
import jagex2.dash3d.Square;
import jagex2.dash3d.Wall;
import jagex2.dash3d.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Progressive-native source inventory for the HD scene pass.
 *
 * <p>This deliberately names rev-254 concepts instead of RuneLite API concepts.
 * Future RLHD-inspired material, override, and light systems should consume this
 * boundary and translate rev-254 ids/kinds here, not pretend the client has
 * RuneLite {@code TileObject}, {@code NPC}, or {@code Player} contracts.
 */
final class HdSceneSources {
    static final HdSceneSources EMPTY = new HdSceneSources(
            List.of(), List.of(), List.of(), List.of());

    final List<TerrainTile> terrainTiles;
    final List<SceneObject> sceneObjects;
    final List<EntitySource> entities;
    final List<ModelSourceRef> models;

    private HdSceneSources(List<TerrainTile> terrainTiles, List<SceneObject> sceneObjects,
            List<EntitySource> entities, List<ModelSourceRef> models) {
        this.terrainTiles = Collections.unmodifiableList(terrainTiles);
        this.sceneObjects = Collections.unmodifiableList(sceneObjects);
        this.entities = Collections.unmodifiableList(entities);
        this.models = Collections.unmodifiableList(models);
    }

    static HdSceneSources capture(World world, int plane, int minTileX, int maxTileX,
            int minTileZ, int maxTileZ, Client client) {
        if (world == null || world.groundh == null || plane < 0 || plane >= world.groundh.length) {
            return EMPTY;
        }

        ArrayList<TerrainTile> terrainTiles = new ArrayList<>();
        ArrayList<SceneObject> sceneObjects = new ArrayList<>();
        ArrayList<EntitySource> entities = new ArrayList<>();
        ArrayList<ModelSourceRef> models = new ArrayList<>();
        Set<ModelSource> seenModels = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Sprite> seenSprites = Collections.newSetFromMap(new IdentityHashMap<>());

        Square[][] planeTiles = world.groundh[plane];
        if (planeTiles != null) {
            int minX = Math.max(0, minTileX);
            int minZ = Math.max(0, minTileZ);
            int maxX = Math.min(maxTileX, planeTiles.length);
            for (int tileX = minX; tileX < maxX; tileX++) {
                if (planeTiles[tileX] == null) {
                    continue;
                }
                int maxZBound = Math.min(maxTileZ, planeTiles[tileX].length);
                for (int tileZ = minZ; tileZ < maxZBound; tileZ++) {
                    Square tile = planeTiles[tileX][tileZ];
                    if (tile == null) {
                        continue;
                    }
                    if (tile.quickGround != null || tile.ground != null) {
                        terrainTiles.add(new TerrainTile(plane, tileX, tileZ, tile.quickGround, tile.ground));
                    }
                    addSceneObjects(tile, sceneObjects, models, seenModels, seenSprites);
                }
            }
        }

        addEntities(client, entities, models, seenModels);
        return new HdSceneSources(terrainTiles, sceneObjects, entities, models);
    }

    private static void addSceneObjects(Square tile, ArrayList<SceneObject> out,
            ArrayList<ModelSourceRef> models, Set<ModelSource> seenModels, Set<Sprite> seenSprites) {
        int plane = tile.level;
        int tileX = tile.x;
        int tileZ = tile.z;

        Wall wall = tile.wall;
        if (wall != null) {
            addObject(out, models, seenModels, ObjectKind.WALL_PRIMARY, plane, tileX, tileZ,
                    wall.x, wall.y, wall.z, wall.angle1, wall.typecode, wall.typecode2, wall.model1);
            addObject(out, models, seenModels, ObjectKind.WALL_SECONDARY, plane, tileX, tileZ,
                    wall.x, wall.y, wall.z, wall.angle2, wall.typecode, wall.typecode2, wall.model2);
        }

        Decor decor = tile.decor;
        if (decor != null) {
            addObject(out, models, seenModels, ObjectKind.WALL_DECOR, plane, tileX, tileZ,
                    decor.x, decor.y, decor.z, decor.angle, decor.typecode, decor.typecode2, decor.model);
        }

        GroundDecor groundDecor = tile.groundDecor;
        if (groundDecor != null) {
            addObject(out, models, seenModels, ObjectKind.GROUND_DECOR, plane, tileX, tileZ,
                    groundDecor.x, groundDecor.y, groundDecor.z, 0, groundDecor.typecode,
                    groundDecor.typecode2, groundDecor.model);
        }

        GroundObject groundObject = tile.groundObject;
        if (groundObject != null) {
            addObject(out, models, seenModels, ObjectKind.GROUND_OBJECT_TOP, plane, tileX, tileZ,
                    groundObject.x, groundObject.y, groundObject.z, 0, groundObject.typecode,
                    0, groundObject.top);
            addObject(out, models, seenModels, ObjectKind.GROUND_OBJECT_MIDDLE, plane, tileX, tileZ,
                    groundObject.x, groundObject.y, groundObject.z, 0, groundObject.typecode,
                    0, groundObject.middle);
            addObject(out, models, seenModels, ObjectKind.GROUND_OBJECT_BOTTOM, plane, tileX, tileZ,
                    groundObject.x, groundObject.y, groundObject.z, 0, groundObject.typecode,
                    0, groundObject.bottom);
        }

        for (int i = 0; i < tile.primaryCount; i++) {
            Sprite sprite = tile.sprite[i];
            if (sprite == null || !seenSprites.add(sprite)) {
                continue;
            }
            addObject(out, models, seenModels, ObjectKind.SCENERY, sprite.level, tileX, tileZ,
                    sprite.x, sprite.y, sprite.z, sprite.angle, sprite.typecode,
                    sprite.typecode2, sprite.model);
        }
    }

    private static void addObject(ArrayList<SceneObject> out, ArrayList<ModelSourceRef> models,
            Set<ModelSource> seenModels, ObjectKind kind, int plane, int tileX, int tileZ,
            int x, int y, int z, int angle, int typecode, int typecode2, ModelSource model) {
        if (model == null) {
            return;
        }
        SceneObject object = new SceneObject(kind, plane, tileX, tileZ, x, y, z, angle,
                typecode, typecode2 & 0xFF, objectId(typecode), model);
        out.add(object);
        addModel(models, seenModels, ModelOwner.SCENE_OBJECT, kind.name(), typecode, model);
    }

    private static void addEntities(Client client, ArrayList<EntitySource> entities,
            ArrayList<ModelSourceRef> models, Set<ModelSource> seenModels) {
        if (client == null) {
            return;
        }

        ClientPlayer local = Client.localPlayer;
        if (local != null) {
            addEntity(entities, models, seenModels, EntityKind.LOCAL_PLAYER,
                    client.LOCAL_PLAYER_INDEX, local.name, local.combatLevel, local);
        }

        for (int i = 0; i < client.playerCount; i++) {
            int index = client.playerIds[i];
            ClientPlayer player = index >= 0 && index < client.players.length ? client.players[index] : null;
            if (player == null || player == local) {
                continue;
            }
            addEntity(entities, models, seenModels, EntityKind.PLAYER,
                    index, player.name, player.combatLevel, player);
        }

        for (int i = 0; i < client.npcCount; i++) {
            int index = client.npcIds[i];
            ClientNpc npc = index >= 0 && index < client.npcs.length ? client.npcs[index] : null;
            if (npc == null) {
                continue;
            }
            int npcId = npc.type != null ? (int) npc.type.id : -1;
            String label = npc.type != null ? npc.type.name : null;
            addEntity(entities, models, seenModels, EntityKind.NPC, index, label, npcId, npc);
        }
    }

    private static void addEntity(ArrayList<EntitySource> entities, ArrayList<ModelSourceRef> models,
            Set<ModelSource> seenModels, EntityKind kind, int index, String label, int semanticId,
            ClientEntity entity) {
        entities.add(new EntitySource(kind, index, label, semanticId, entity.x, entity.z,
                entity.yaw, entity.size, entity));
        addModel(models, seenModels, ModelOwner.ENTITY, kind.name(), semanticId, entity);
    }

    private static void addModel(ArrayList<ModelSourceRef> models, Set<ModelSource> seenModels,
            ModelOwner owner, String role, int id, ModelSource source) {
        if (source != null && seenModels.add(source)) {
            models.add(new ModelSourceRef(owner, role, id, source));
        }
    }

    private static int objectId(int typecode) {
        return typecode >>> 14 & 0x7FFF;
    }

    enum ObjectKind {
        WALL_PRIMARY,
        WALL_SECONDARY,
        WALL_DECOR,
        GROUND_DECOR,
        GROUND_OBJECT_TOP,
        GROUND_OBJECT_MIDDLE,
        GROUND_OBJECT_BOTTOM,
        SCENERY
    }

    enum EntityKind {
        LOCAL_PLAYER,
        PLAYER,
        NPC
    }

    enum ModelOwner {
        SCENE_OBJECT,
        ENTITY
    }

    record TerrainTile(int plane, int tileX, int tileZ, QuickGround quickGround, Ground ground) {
    }

    record SceneObject(ObjectKind kind, int plane, int tileX, int tileZ, int x, int y, int z,
            int angle, int typecode, int typecode2, int objectId, ModelSource model) {
    }

    record EntitySource(EntityKind kind, int index, String label, int semanticId, int x, int z,
            int yaw, int size, ModelSource model) {
    }

    record ModelSourceRef(ModelOwner owner, String role, int id, ModelSource source) {
    }
}
