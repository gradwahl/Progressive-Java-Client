package com.gradwahl.rs254.overlay;

import com.gradwahl.rs254.ClientDebugger;
import jagex2.config.SeqType;
import jagex2.config.SpotAnimType;
import jagex2.dash3d.AnimBase;
import jagex2.dash3d.AnimFrame;
import jagex2.dash3d.ClientPlayer;
import jagex2.dash3d.Model;
import jagex2.datastruct.LruCache;
import jagex2.io.Packet;

import java.io.InputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Runtime overlay for OSRS skillcape emote assets.
 *
 * The legacy client can already render player and spotanim sequences, but its
 * built-in cache data for the modern skillcape emotes is incomplete. This
 * overlay injects the missing frame data and spotanim models directly in memory
 * so every skillcape emote can use the OSRS sequence set.
 *
 * The body is still the client's assembled player model, but it is now driven
 * by the OSRS skillcape sequence frames instead of the earlier strength-only
 * pose approximation.
 */
public final class SkillcapeOverlay {
    private static final SequenceSpec[] BODY_SEQUENCES = {
            new SequenceSpec("attack", 4959, 11517, 44),
            new SequenceSpec("strength", 4981, 11511, 110),
            new SequenceSpec("defence", 4961, 11525, 55),
            new SequenceSpec("ranged", 4973, 11534, 62),
            new SequenceSpec("prayer", 4979, 11509, 58),
            new SequenceSpec("magic", 4939, 11528, 36),
            new SequenceSpec("runecraft", 4947, 11526, 60),
            new SequenceSpec("hitpoints", 4971, 11519, 60),
            new SequenceSpec("agility", 4977, 11523, 48),
            new SequenceSpec("herblore", 4969, 11514, 138),
            new SequenceSpec("thieving", 4965, 11512, 26),
            new SequenceSpec("crafting", 4949, 11518, 118),
            new SequenceSpec("fletching", 4937, 11522, 96),
            new SequenceSpec("slayer", 4967, 11529, 30),
            new SequenceSpec("construction", 4953, 11524, 113),
            new SequenceSpec("mining", 4941, 11520, 43),
            new SequenceSpec("smithing", 4943, 11531, 137),
            new SequenceSpec("fishing", 4951, 11516, 89),
            new SequenceSpec("cooking", 4955, 11513, 181),
            new SequenceSpec("firemaking", 4975, 11530, 70),
            new SequenceSpec("woodcutting", 4957, 11515, 150),
            new SequenceSpec("farming", 4963, 11521, 59),
            new SequenceSpec("quest_point", 4945, 11510, 85),
            new SequenceSpec("hunter", 5158, 11532, 112),
            new SequenceSpec("achievement_diary", 2709, 12195, 187),
            new SequenceSpec("music", 4751, 12213, 41),
            new SequenceSpec("max", 7121, 11443, 44)
    };

    private static final SpotanimSpec[] SPOTANIMS = {
            new SpotanimSpec(823, 19071, new SequenceSpec("attack-gfx", 4960, 10074, 44)),
            new SpotanimSpec(828, 19090, new SequenceSpec("strength-gfx", 4982, 10062, 98)),
            new SpotanimSpec(824, 19075, new SequenceSpec("defence-gfx", 4962, 10063, 42)),
            new SpotanimSpec(832, 19087, new SequenceSpec("ranged-gfx", 4974, 10056, 60)),
            new SpotanimSpec(829, 19085, new SequenceSpec("prayer-gfx", 4980, 10065, 58)),
            new SpotanimSpec(813, 19083, new SequenceSpec("magic-gfx", 4940, 10067, 36)),
            new SpotanimSpec(817, 19069, new SequenceSpec("runecraft-gfx", 4948, 10061, 60)),
            new SpotanimSpec(833, 19081, new SequenceSpec("hitpoints-gfx-a", 4972, 10053, 60)),
            new SpotanimSpec(834, 19082, new SequenceSpec("hitpoints-gfx-b", 4972, 10053, 60)),
            new SpotanimSpec(830, 19070, new SequenceSpec("agility-gfx", 4978, 10055, 48)),
            new SpotanimSpec(835, 19080, new SequenceSpec("herblore-gfx", 4970, 10054, 136)),
            new SpotanimSpec(826, 19091, new SequenceSpec("thieving-gfx", 4966, 10060, 26)),
            new SpotanimSpec(818, 19074, new SequenceSpec("crafting-gfx", 4950, 10066, 112)),
            new SpotanimSpec(812, 19079, new SequenceSpec("fletching-gfx", 4938, 10071, 96)),
            new SpotanimSpec(827, 19088, new SequenceSpec("slayer-gfx", 4968, 10076, 30)),
            new SpotanimSpec(820, 19072, new SequenceSpec("construction-gfx", 4954, 10072, 113)),
            new SpotanimSpec(814, 19084, new SequenceSpec("mining-gfx", 4942, 10059, 43)),
            new SpotanimSpec(815, 19089, new SequenceSpec("smithing-gfx", 4944, 10073, 137)),
            new SpotanimSpec(819, 19078, new SequenceSpec("fishing-gfx", 4952, 10058, 89)),
            new SpotanimSpec(821, 19073, new SequenceSpec("cooking-gfx", 4956, 10050, 181)),
            new SpotanimSpec(831, 19077, new SequenceSpec("firemaking-gfx", 4976, 10069, 70)),
            new SpotanimSpec(822, 19092, new SequenceSpec("woodcutting-gfx", 4958, 10068, 147)),
            new SpotanimSpec(825, 19076, new SequenceSpec("farming-gfx", 4964, 10057, 59)),
            new SpotanimSpec(816, 19086, new SequenceSpec("quest-gfx", 4946, 10070, 85)),
            new SpotanimSpec(907, 19978, new SequenceSpec("hunter-gfx", 5159, 10075, 105)),
            new SpotanimSpec(323, 29172, new SequenceSpec("diary-gfx", 2419, 8939, 157)),
            new SpotanimSpec(1537, 35330, new SequenceSpec("music-gfx", 8046, 12548, 38))
    };

    private static final Map<Integer, Model> OVERLAY_MODELS = new HashMap<>();
    private static final Map<Integer, SequenceSpec> BODY_SEQUENCE_BY_ID = new HashMap<>();
    private static final Map<Integer, CapeWearSpec> CAPE_BY_SEQ = new HashMap<>();
    private static final Map<String, Model> ACTOR_BASE_MODELS = new HashMap<>();
    private static ActorPartSet maleActorParts;
    private static ActorPartSet femaleActorParts;
    private static Path actorBasePath;
    private static Path rawBasePath;
    private static boolean installed;
    private static boolean replacementModelLogged;
    private static boolean replacementFailureLogged;
    private static boolean[] supportedRigLabels;
    private static boolean bodyFramesInstalledForRig;
    private static int nextGeneratedFrameId = -1;

    static {
        for (SequenceSpec spec : BODY_SEQUENCES) {
            BODY_SEQUENCE_BY_ID.put(spec.seqId, spec);
        }
    }

    private SkillcapeOverlay() {
    }

    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;

        rawBasePath = resolveRawBase();
        if (rawBasePath == null) {
            log("Raw OSRS pack not found in runtime search paths");
            return;
        }

        try {
            patchAllSpotanimConfigs();
            installAllSpotanimFrames();
            installAllBodyFrames(null);
            loadActorPack();
            loadAllOverlayModels();
        } catch (Exception ex) {
            log("Install failed: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    public static synchronized Model getOverlayModel(int modelId) {
        if (!installed) {
            install();
        }
        return OVERLAY_MODELS.get(modelId);
    }

    public static synchronized void onLocalPlayerRigPrepared(Model model) {
        // Legacy rig filtering is no longer used now that skillcape emotes can
        // swap to a dedicated OSRS actor assembled from matching body assets.
    }

    public static synchronized boolean isSkillcapeBodySeq(int seqId) {
        return BODY_SEQUENCE_BY_ID.containsKey(seqId);
    }

    public static synchronized Model buildReplacementPlayerModel(ClientPlayer player, int seqId, int frameA, int frameB, int interpFrom, int interpT) {
        if (!installed) {
            install();
        }
        if (!isSkillcapeBodySeq(seqId)) {
            return null;
        }

        Model base = getActorBaseModel(player.gender, seqId);
        if (base == null) {
            if (!replacementFailureLogged) {
                replacementFailureLogged = true;
                log("Replacement actor unavailable for seq " + seqId + " gender=" + player.gender);
            }
            return null;
        }
        if (!replacementModelLogged) {
            replacementModelLogged = true;
            log("Using OSRS replacement actor for seq " + seqId + " gender=" + player.gender
                    + " vertexCount=" + base.vertexCount + " faceCount=" + base.faceCount);
        }
        ClientDebugger.dumpStrengthBodyFrame("replacement-player", seqId, frameA);

        Model animated = Model.empty;
        animated.set(AnimFrame.shareAlpha(frameA) & AnimFrame.shareAlpha(frameB), base);
        if (frameA != -1 && frameB != -1) {
            SeqType seq = SeqType.list != null && seqId >= 0 && seqId < SeqType.list.length ? SeqType.list[seqId] : null;
            if (seq != null && seq.walkmerge != null) {
                animated.maskAnimate(seq.walkmerge, frameA, frameB);
            } else if (interpFrom != -1) {
                animated.animateInterpolated(interpFrom, frameA, interpT);
            } else {
                animated.animate(frameA);
            }
        } else if (frameA != -1) {
            if (interpFrom != -1) {
                animated.animateInterpolated(interpFrom, frameA, interpT);
            } else {
                animated.animate(frameA);
            }
        }
        if (ClientDebugger.isEnabled()) {
            int movedVertices = 0;
            int maxDelta = 0;
            for (int i = 0; i < animated.vertexCount; i++) {
                int dx = Math.abs(animated.vertexX[i] - base.vertexX[i]);
                int dy = Math.abs(animated.vertexY[i] - base.vertexY[i]);
                int dz = Math.abs(animated.vertexZ[i] - base.vertexZ[i]);
                int delta = dx + dy + dz;
                if (delta > 0) {
                    movedVertices++;
                    if (delta > maxDelta) {
                        maxDelta = delta;
                    }
                }
            }
            int frameIndex = SeqType.list != null && seqId >= 0 && seqId < SeqType.list.length && SeqType.list[seqId] != null
                    ? indexOfFrame(SeqType.list[seqId].frames, frameA)
                    : -1;
            ClientDebugger.onSkillcapeBodyDelta("replacement-player", seqId, frameIndex, movedVertices, maxDelta);
        }
        animated.calcBoundingCylinder();
        animated.labelFaces = null;
        animated.labelVertices = null;
        return animated;
    }

    private static void patchAllSpotanimConfigs() {
        ensureSpotanimCapacity(maxSpotanimId());
        ensureSeqCapacity(maxSpotanimSeqId());
        SpotAnimType.modelCache = new LruCache(30);

        for (SpotanimSpec spec : SPOTANIMS) {
            SeqType seq = ensureSeq(spec.sequence.seqId);
            int actualFrameCount = spec.sequence.frameCount;
            Path framesDir = rawBasePath.resolve("frames").resolve("index0_group_" + spec.sequence.frameGroup);
            if (Files.isDirectory(framesDir)) {
                try {
                    actualFrameCount = detectAvailableFrameCount(framesDir, spec.sequence.frameCount);
                } catch (IOException ignored) {
                }
            }
            prepareSequenceFrames(seq, spec.sequence, actualFrameCount);

            SpotAnimType spotanim = SpotAnimType.list[spec.spotanimId];
            if (spotanim == null) {
                spotanim = new SpotAnimType();
                SpotAnimType.list[spec.spotanimId] = spotanim;
            }
            spotanim.id = spec.spotanimId;
            spotanim.model = spec.modelId;
            spotanim.anim = spec.sequence.seqId;
            spotanim.seq = seq;
        }

        log("Patched " + SPOTANIMS.length + " skillcape spotanim definition(s)");
    }

    private static void installAllSpotanimFrames() throws IOException {
        int installedFrames = 0;
        for (SpotanimSpec spec : SPOTANIMS) {
            int before = installedFrames;
            installedFrames += installSeqFrames(spec.sequence, null);
            log("  spotanim " + spec.spotanimId + " (" + spec.sequence.key + "): installed " + (installedFrames - before) + "/" + spec.sequence.frameCount + " frames");
        }
        log("Installed " + installedFrames + " skillcape spotanim frame(s)");
    }

    private static void installAllBodyFrames(boolean[] rigLabels) throws IOException {
        int installedFrames = 0;
        for (SequenceSpec spec : BODY_SEQUENCES) {
            installedFrames += installSeqFrames(spec, rigLabels);
        }
        bodyFramesInstalledForRig = true;
        log("Installed " + installedFrames + " skillcape body frame(s)");
    }

    private static void loadAllOverlayModels() throws IOException {
        int loaded = 0;
        for (SpotanimSpec spec : SPOTANIMS) {
            if (OVERLAY_MODELS.containsKey(spec.modelId)) {
                continue;
            }
            Model model = loadOverlayModel(spec.modelId);
            if (model != null) {
                OVERLAY_MODELS.put(spec.modelId, model);
                loaded++;
            } else {
                log("Missing overlay model " + spec.modelId + " for spotanim " + spec.spotanimId);
            }
        }
        log("Loaded " + loaded + " skillcape overlay model(s)");
    }

    private static void loadActorPack() throws IOException {
        Path actorBase = resolveActorBase();
        if (actorBase == null) {
            log("OSRS actor pack not found in runtime search paths");
            return;
        }
        actorBasePath = actorBase;
        Path actorProperties = actorBase.resolve("data").resolve("osrs_skillcape_actor.properties");
        if (!Files.isRegularFile(actorProperties)) {
            log("OSRS actor pack metadata missing at " + actorProperties);
            return;
        }

        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(actorProperties)) {
            properties.load(in);
        }

        maleActorParts = new ActorPartSet(
                parseModelSlots(properties.getProperty("male.parts")),
                parseRecolorSlots(properties.getProperty("male.partRecolors"))
        );
        femaleActorParts = new ActorPartSet(
                parseModelSlots(properties.getProperty("female.parts")),
                parseRecolorSlots(properties.getProperty("female.partRecolors"))
        );

        CAPE_BY_SEQ.clear();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith("seq.") || !key.endsWith(".itemId")) {
                continue;
            }
            int seqId = Integer.parseInt(key.substring(4, key.length() - 7));
            CAPE_BY_SEQ.put(seqId, new CapeWearSpec(
                    parseModelList(properties.getProperty("seq." + seqId + ".maleModels")),
                    parseModelList(properties.getProperty("seq." + seqId + ".femaleModels")),
                    parseRecolors(properties.getProperty("seq." + seqId + ".recolors")),
                    parseInt(properties.getProperty("seq." + seqId + ".maleOffset"), 0),
                    parseInt(properties.getProperty("seq." + seqId + ".femaleOffset"), 0)
            ));
        }
        log("Loaded OSRS actor pack from " + actorBase + " with " + CAPE_BY_SEQ.size() + " skillcape cape mapping(s)");
    }

    private static Model getActorBaseModel(int gender, int seqId) {
        ActorPartSet parts = gender == 1 ? femaleActorParts : maleActorParts;
        if (parts == null) {
            return null;
        }
        CapeWearSpec cape = CAPE_BY_SEQ.get(seqId);
        if (cape == null) {
            return null;
        }

        String cacheKey = gender + ":" + seqId;
        Model cached = ACTOR_BASE_MODELS.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        List<Model> segments = new ArrayList<>();
        for (int i = 0; i < parts.modelSlots.length; i++) {
            Model model = buildPartModel(parts.modelSlots[i], parts.recolorsBySlot[i], 0);
            if (model != null) {
                segments.add(model);
            }
        }

        int[] capeModels = gender == 1 ? cape.femaleModels : cape.maleModels;
        int capeOffset = gender == 1 ? cape.femaleOffset : cape.maleOffset;
        Model capeModel = buildPartModel(capeModels, cape.recolors, capeOffset);
        if (capeModel != null) {
            segments.add(capeModel);
        }

        if (segments.isEmpty()) {
            return null;
        }

        Model base = new Model(segments.toArray(new Model[0]), segments.size());
        base.prepareAnim();
        base.calculateNormals(64, 850, -30, -50, -30, true);
        ACTOR_BASE_MODELS.put(cacheKey, base);
        return base;
    }

    private static Model buildPartModel(int[] modelIds, int[][] recolors, int translateY) {
        if (modelIds == null || modelIds.length == 0) {
            return null;
        }
        List<Model> loadedParts = new ArrayList<>();
        for (int modelId : modelIds) {
            if (modelId < 0) {
                continue;
            }
            Model model = loadActorModel(modelId);
            if (model != null) {
                loadedParts.add(model);
            }
        }
        if (loadedParts.isEmpty()) {
            return null;
        }
        Model combined = loadedParts.size() == 1
                ? loadedParts.get(0)
                : new Model(loadedParts.toArray(new Model[0]), loadedParts.size());
        if (translateY != 0) {
            combined.translate(0, 0, translateY);
        }
        if (recolors != null) {
            for (int[] pair : recolors) {
                if (pair != null && pair.length == 2) {
                    combined.recolour(pair[0], pair[1]);
                }
            }
        }
        return combined;
    }

    private static Model loadActorModel(int modelId) {
        Path modelPath = resolvePackModelPath(modelId);
        if (!Files.isRegularFile(modelPath)) {
            return null;
        }
        try {
            OsrsModel decoded = OsrsModel.decode(Files.readAllBytes(modelPath));
            if (decoded == null) {
                return null;
            }
            Model model = new Model();
            model.vertexCount = decoded.vertexCount;
            model.faceCount = decoded.faceCount;
            model.texturedFaceCount = 0;
            model.vertexX = decoded.verticesX;
            model.vertexY = decoded.verticesY;
            model.vertexZ = decoded.verticesZ;
            model.faceVertexA = decoded.faceVertexA;
            model.faceVertexB = decoded.faceVertexB;
            model.faceVertexC = decoded.faceVertexC;
            model.faceColour = new int[decoded.faceCount];
            for (int i = 0; i < decoded.faceCount; i++) {
                model.faceColour[i] = decoded.faceColors[i] & 0xFFFF;
            }
            model.vertexLabel = decoded.packedVertexGroups;
            model.texturedVertexA = new int[0];
            model.texturedVertexB = new int[0];
            model.texturedVertexC = new int[0];
            model.calcBoundingCylinder();
            return model;
        } catch (IOException ex) {
            log("Failed to read actor model " + modelId + ": " + ex.getMessage());
            return null;
        }
    }

    private static Path resolveActorBase() {
        Path[] directCandidates = new Path[] {
                Path.of("cache", "osrs_skillcape_actor_pack"),
                Path.of("Jar Output", "cache", "osrs_skillcape_actor_pack")
        };
        for (Path candidate : directCandidates) {
            if (Files.isDirectory(candidate.resolve("data")) && Files.isDirectory(candidate.resolve("models"))) {
                return candidate;
            }
        }

        try {
            Path codeSource = Path.of(SkillcapeOverlay.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path codeDir = Files.isDirectory(codeSource) ? codeSource : codeSource.getParent();
            if (codeDir != null) {
                Path[] nearbyCandidates = new Path[] {
                        codeDir.resolve("cache").resolve("osrs_skillcape_actor_pack"),
                        codeDir.getParent() != null ? codeDir.getParent().resolve("cache").resolve("osrs_skillcape_actor_pack") : null
                };
                for (Path candidate : nearbyCandidates) {
                    if (candidate != null && Files.isDirectory(candidate.resolve("data")) && Files.isDirectory(candidate.resolve("models"))) {
                        return candidate;
                    }
                }
            }
        } catch (URISyntaxException | IllegalArgumentException ignored) {
        }

        return null;
    }

    private static Path resolveRawBase() {
        String env = System.getenv("RS254_SKILLCAPE_RAW_DIR");
        if (env != null && !env.isBlank()) {
            Path envPath = Path.of(env);
            if (Files.isDirectory(envPath.resolve("frames")) && Files.isDirectory(envPath.resolve("skeletons"))) {
                return envPath;
            }
            log("Ignoring RS254_SKILLCAPE_RAW_DIR without frames/skeletons: " + envPath);
        }

        Path[] directCandidates = new Path[] {
                Path.of("osrs", "skillcape_emote_pack_b238", "raw"),
                Path.of("cache", "skillcape_emote_pack_b238", "raw"),
                Path.of("cache", "osrs_skillcape_emote_pack", "raw"),
                Path.of("Jar Output", "osrs", "skillcape_emote_pack_b238", "raw"),
                Path.of("Jar Output", "cache", "skillcape_emote_pack_b238", "raw"),
                Path.of("Jar Output", "cache", "osrs_skillcape_emote_pack", "raw")
        };
        for (Path candidate : directCandidates) {
            if (Files.isDirectory(candidate.resolve("frames")) && Files.isDirectory(candidate.resolve("skeletons"))) {
                return candidate;
            }
        }

        try {
            Path codeSource = Path.of(SkillcapeOverlay.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path codeDir = Files.isDirectory(codeSource) ? codeSource : codeSource.getParent();
            if (codeDir != null) {
                Path[] nearbyCandidates = new Path[] {
                        codeDir.resolve("osrs").resolve("skillcape_emote_pack_b238").resolve("raw"),
                        codeDir.resolve("cache").resolve("skillcape_emote_pack_b238").resolve("raw"),
                        codeDir.resolve("cache").resolve("osrs_skillcape_emote_pack").resolve("raw"),
                        codeDir.getParent() != null ? codeDir.getParent().resolve("osrs").resolve("skillcape_emote_pack_b238").resolve("raw") : null,
                        codeDir.getParent() != null ? codeDir.getParent().resolve("cache").resolve("skillcape_emote_pack_b238").resolve("raw") : null,
                        codeDir.getParent() != null ? codeDir.getParent().resolve("cache").resolve("osrs_skillcape_emote_pack").resolve("raw") : null
                };
                for (Path candidate : nearbyCandidates) {
                    if (candidate != null && Files.isDirectory(candidate.resolve("frames")) && Files.isDirectory(candidate.resolve("skeletons"))) {
                        return candidate;
                    }
                }
            }
        } catch (URISyntaxException | IllegalArgumentException ignored) {
        }

        return null;
    }

    private static int installSeqFrames(SequenceSpec spec, boolean[] rigLabels) throws IOException {
        Path framesDir = rawBasePath.resolve("frames").resolve("index0_group_" + spec.frameGroup);
        if (!Files.isDirectory(framesDir)) {
            log("Missing frame directory " + framesDir);
            return 0;
        }
        int availableFrameCount = detectAvailableFrameCount(framesDir, spec.frameCount);
        if (availableFrameCount <= 0) {
            log("No frame files found for key=" + spec.key + " group=" + spec.frameGroup);
            return 0;
        }

        SeqType seq = ensureSeq(spec.seqId);
        prepareSequenceFrames(seq, spec, availableFrameCount);

        Map<Integer, AnimBase> bases = new HashMap<>();
        int installedFrames = 0;
        for (int i = 0; i < availableFrameCount && i < spec.generatedFrameIds.length; i++) {
            Path framePath = framesDir.resolve("frame_" + (i + 1) + ".dat");
            if (!Files.isRegularFile(framePath)) {
                continue;
            }

            byte[] frameData = Files.readAllBytes(framePath);
            if (frameData.length < 3) {
                continue;
            }

            int skeletonId = ((frameData[0] & 0xFF) << 8) | (frameData[1] & 0xFF);
            AnimBase base = bases.get(skeletonId);
            if (base == null) {
                base = loadOsrsBase(skeletonId);
                if (base == null) {
                    continue;
                }
                bases.put(skeletonId, base);
            }

            OsrsFrame frame;
            try {
                frame = OsrsFrame.decode(base, frameData);
            } catch (RuntimeException ex) {
                log("Skipping malformed frame key=" + spec.key
                        + " seq=" + spec.seqId
                        + " group=" + spec.frameGroup
                        + " file=" + (i + 1)
                        + " len=" + frameData.length
                        + " cause=" + ex.getClass().getSimpleName()
                        + ": " + ex.getMessage());
                continue;
            }
            if (frame == null) {
                continue;
            }

            if (rigLabels != null) {
                frame = frame.filter(base, rigLabels);
                if (frame == null) {
                    continue;
                }
            }

            int globalFrameId = spec.generatedFrameIds[i];
            AnimFrame out = new AnimFrame();
            out.delay = seq.delay[i] > 0 ? seq.delay[i] : 1;
            out.base = base;
            out.size = frame.transformCount;
            out.ti = frame.transformIndices;
            out.tx = frame.translatorX;
            out.ty = frame.translatorY;
            out.tz = frame.translatorZ;
            AnimFrame.list[globalFrameId] = out;
            AnimFrame.opaque[globalFrameId] = !frame.hasAlpha;
            installedFrames++;
        }
        return installedFrames;
    }

    private static void prepareSequenceFrames(SeqType seq, SequenceSpec spec, int actualFrameCount) {
        if (spec.generatedFrameIds == null || spec.generatedFrameIds.length != actualFrameCount) {
            int start = reserveFrameIds(actualFrameCount);
            spec.generatedFrameIds = new int[actualFrameCount];
            for (int i = 0; i < actualFrameCount; i++) {
                spec.generatedFrameIds[i] = start + i;
            }
        }

        int[] existingDelay = seq.delay;
        seq.numFrames = actualFrameCount;
        seq.frames = Arrays.copyOf(spec.generatedFrameIds, actualFrameCount);
        seq.iframes = new int[actualFrameCount];
        Arrays.fill(seq.iframes, -1);
        seq.delay = new int[actualFrameCount];
        for (int i = 0; i < actualFrameCount; i++) {
            int delay = existingDelay != null && i < existingDelay.length ? existingDelay[i] : 0;
            seq.delay[i] = delay > 0 ? delay : 1;
        }
        if (seq.preanim_move == -1) {
            seq.preanim_move = seq.walkmerge == null ? 0 : 2;
        }
        if (seq.postanim_move == -1) {
            seq.postanim_move = seq.walkmerge == null ? 0 : 2;
        }
    }

    private static SeqType ensureSeq(int seqId) {
        ensureSeqCapacity(seqId);
        SeqType seq = SeqType.list[seqId];
        if (seq == null) {
            seq = new SeqType();
            SeqType.list[seqId] = seq;
        }
        return seq;
    }

    private static void ensureSeqCapacity(int seqId) {
        if (SeqType.list == null) {
            SeqType.list = new SeqType[seqId + 1];
            SeqType.count = SeqType.list.length;
            return;
        }
        if (seqId < SeqType.list.length) {
            return;
        }
        SeqType.list = Arrays.copyOf(SeqType.list, seqId + 1);
        SeqType.count = SeqType.list.length;
    }

    private static void ensureSpotanimCapacity(int spotanimId) {
        if (SpotAnimType.list == null) {
            SpotAnimType.list = new SpotAnimType[spotanimId + 1];
            SpotAnimType.count = SpotAnimType.list.length;
            return;
        }
        if (spotanimId < SpotAnimType.list.length) {
            return;
        }
        SpotAnimType.list = Arrays.copyOf(SpotAnimType.list, spotanimId + 1);
        SpotAnimType.count = SpotAnimType.list.length;
    }

    private static int reserveFrameIds(int count) {
        if (AnimFrame.list == null || AnimFrame.opaque == null) {
            AnimFrame.init(Math.max(4096, count + 1));
        }
        if (nextGeneratedFrameId < 0) {
            nextGeneratedFrameId = AnimFrame.list.length;
        }
        int start = nextGeneratedFrameId;
        nextGeneratedFrameId += count;
        ensureAnimFrameCapacity(nextGeneratedFrameId + 1);
        return start;
    }

    private static int detectAvailableFrameCount(Path framesDir, int fallbackCount) throws IOException {
        int maxIndex = 0;
        try (Stream<Path> stream = Files.list(framesDir)) {
            for (Path path : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                String name = path.getFileName().toString();
                if (!name.startsWith("frame_") || !name.endsWith(".dat")) {
                    continue;
                }
                String numberPart = name.substring(6, name.length() - 4);
                try {
                    int index = Integer.parseInt(numberPart);
                    if (index > maxIndex) {
                        maxIndex = index;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return maxIndex > 0 ? maxIndex : fallbackCount;
    }

    private static void ensureAnimFrameCapacity(int neededLength) {
        if (AnimFrame.list == null || AnimFrame.opaque == null) {
            AnimFrame.init(neededLength);
            return;
        }
        if (neededLength <= AnimFrame.list.length) {
            return;
        }
        int oldLength = AnimFrame.list.length;
        AnimFrame.list = Arrays.copyOf(AnimFrame.list, neededLength);
        AnimFrame.opaque = Arrays.copyOf(AnimFrame.opaque, neededLength);
        Arrays.fill(AnimFrame.opaque, oldLength, neededLength, true);
    }

    private static int maxSpotanimId() {
        int max = 0;
        for (SpotanimSpec spec : SPOTANIMS) {
            if (spec.spotanimId > max) {
                max = spec.spotanimId;
            }
        }
        return max;
    }

    private static int maxSpotanimSeqId() {
        int max = 0;
        for (SpotanimSpec spec : SPOTANIMS) {
            if (spec.sequence.seqId > max) {
                max = spec.sequence.seqId;
            }
        }
        return max;
    }

    private static int indexOfFrame(int[] frames, int frameId) {
        if (frames == null) {
            return -1;
        }
        for (int i = 0; i < frames.length; i++) {
            if (frames[i] == frameId) {
                return i;
            }
        }
        return -1;
    }

    private static void log(String message) {
        String line = "[SkillcapeOverlay] " + message;
        System.out.println(line);
        if (ClientDebugger.isEnabled()) {
            ClientDebugger.log(line);
        }
    }

    private static AnimBase loadOsrsBase(int skeletonId) throws IOException {
        Path skeletonDir = rawBasePath.resolve("skeletons").resolve("index1_group_" + skeletonId);
        if (!Files.isDirectory(skeletonDir)) {
            // OSRS skillcape pack exports skeletons with groupId = cacheArchiveId + 1024
            int remappedId = skeletonId + 1024;
            skeletonDir = rawBasePath.resolve("skeletons").resolve("index1_group_" + remappedId);
            if (!Files.isDirectory(skeletonDir)) {
                return null;
            }
            skeletonId = remappedId;
        }
        Path skeletonPath = skeletonDir.resolve("skeleton_" + skeletonId + ".dat");
        if (!Files.isRegularFile(skeletonPath)) {
            return null;
        }
        byte[] data = Files.readAllBytes(skeletonPath);
        if (data.length == 0) {
            return null;
        }
        int pos = 0;
        int size = data[pos++] & 0xFF;
        if (pos + size > data.length) {
            return null;
        }
        int[] types = new int[size];
        for (int i = 0; i < size; i++) {
            types[i] = data[pos++] & 0xFF;
        }
        if (pos + size > data.length) {
            return null;
        }
        int[][] labels = new int[size][];
        for (int i = 0; i < size; i++) {
            labels[i] = new int[data[pos++] & 0xFF];
        }
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < labels[i].length; j++) {
                if (pos >= data.length) {
                    return null;
                }
                labels[i][j] = data[pos++] & 0xFF;
            }
        }
        AnimBase base = new AnimBase(new Packet(new byte[] {0}));
        base.size = size;
        base.types = types;
        base.labels = labels;
        return base;
    }

    private static boolean[] collectSupportedLabels(Model model) {
        int size = model.labelVertices.length;
        boolean[] labels = new boolean[Math.max(256, size)];
        for (int i = 0; i < model.labelVertices.length; i++) {
            int[] vertices = model.labelVertices[i];
            if (vertices != null && vertices.length > 0) {
                labels[i] = true;
            }
        }
        return labels;
    }

    private static boolean sameLabels(boolean[] a, boolean[] b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static int[] parseModelList(String value) {
        if (value == null || value.isBlank()) {
            return new int[0];
        }
        String[] parts = value.split(",");
        int[] out = new int[parts.length];
        int count = 0;
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            out[count++] = Integer.parseInt(part.trim());
        }
        return Arrays.copyOf(out, count);
    }

    private static int[][] parseRecolors(String value) {
        if (value == null || value.isBlank()) {
            return new int[0][];
        }
        String[] parts = value.split(",");
        int[][] out = new int[parts.length][];
        int count = 0;
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            String[] pair = part.split(":");
            if (pair.length != 2) {
                continue;
            }
            out[count++] = new int[] {Integer.parseInt(pair[0].trim()), Integer.parseInt(pair[1].trim())};
        }
        return Arrays.copyOf(out, count);
    }

    private static int[][][] parseRecolorSlots(String value) {
        if (value == null || value.isBlank()) {
            return new int[0][][];
        }
        String[] slots = value.split("\\|", -1);
        int[][][] out = new int[slots.length][][];
        for (int i = 0; i < slots.length; i++) {
            out[i] = parseRecolors(slots[i]);
        }
        return out;
    }

    private static int[][] parseModelSlots(String value) {
        if (value == null || value.isBlank()) {
            return new int[0][];
        }
        String[] slots = value.split("\\|", -1);
        int[][] out = new int[slots.length][];
        for (int i = 0; i < slots.length; i++) {
            out[i] = parseModelList(slots[i]);
        }
        return out;
    }

    private static Model loadOverlayModel(int modelId) throws IOException {
        Path modelPath = rawBasePath.resolve("models")
                .resolve("index7_group_" + modelId)
                .resolve("model_" + modelId + ".dat");
        if (!Files.isRegularFile(modelPath)) {
            modelPath = resolvePackModelPath(modelId);
            if (!Files.isRegularFile(modelPath)) {
                return null;
            }
        }

        OsrsModel decoded = OsrsModel.decode(Files.readAllBytes(modelPath));
        if (decoded == null) {
            return null;
        }

        Model model = new Model();
        model.vertexCount = decoded.vertexCount;
        model.faceCount = decoded.faceCount;
        model.texturedFaceCount = 0;
        model.vertexX = decoded.verticesX;
        model.vertexY = decoded.verticesY;
        model.vertexZ = decoded.verticesZ;
        model.faceVertexA = decoded.faceVertexA;
        model.faceVertexB = decoded.faceVertexB;
        model.faceVertexC = decoded.faceVertexC;
        model.faceColour = new int[decoded.faceCount];
        for (int i = 0; i < decoded.faceCount; i++) {
            model.faceColour[i] = decoded.faceColors[i] & 0xFFFF;
        }
        model.vertexLabel = decoded.packedVertexGroups;
        model.texturedVertexA = new int[0];
        model.texturedVertexB = new int[0];
        model.texturedVertexC = new int[0];
        model.calcBoundingCylinder();
        return model;
    }

    private static Path resolvePackModelPath(int modelId) {
        Path base = actorBasePath != null ? actorBasePath : resolveActorBase();
        if (base == null) {
            return Path.of("__missing__");
        }
        return base.resolve("models")
                .resolve("index7_group_" + modelId)
                .resolve("model_" + modelId + ".dat");
    }

    private static final class SequenceSpec {
        final String key;
        final int seqId;
        final int frameGroup;
        final int frameCount;
        int[] generatedFrameIds;

        private SequenceSpec(String key, int seqId, int frameGroup, int frameCount) {
            this.key = key;
            this.seqId = seqId;
            this.frameGroup = frameGroup;
            this.frameCount = frameCount;
        }
    }

    private static final class SpotanimSpec {
        final int spotanimId;
        final int modelId;
        final SequenceSpec sequence;

        private SpotanimSpec(int spotanimId, int modelId, SequenceSpec sequence) {
            this.spotanimId = spotanimId;
            this.modelId = modelId;
            this.sequence = sequence;
        }
    }

    private static final class ActorPartSet {
        final int[][] modelSlots;
        final int[][][] recolorsBySlot;

        private ActorPartSet(int[][] modelSlots, int[][][] recolorsBySlot) {
            this.modelSlots = modelSlots;
            this.recolorsBySlot = recolorsBySlot;
        }
    }

    private static final class CapeWearSpec {
        final int[] maleModels;
        final int[] femaleModels;
        final int[][] recolors;
        final int maleOffset;
        final int femaleOffset;

        private CapeWearSpec(int[] maleModels, int[] femaleModels, int[][] recolors, int maleOffset, int femaleOffset) {
            this.maleModels = maleModels;
            this.femaleModels = femaleModels;
            this.recolors = recolors;
            this.maleOffset = maleOffset;
            this.femaleOffset = femaleOffset;
        }
    }

    private static final class OsrsFrame {
        final int transformCount;
        final int[] transformIndices;
        final int[] translatorX;
        final int[] translatorY;
        final int[] translatorZ;
        final boolean hasAlpha;

        private OsrsFrame(int transformCount, int[] transformIndices, int[] translatorX, int[] translatorY, int[] translatorZ, boolean hasAlpha) {
            this.transformCount = transformCount;
            this.transformIndices = transformIndices;
            this.translatorX = translatorX;
            this.translatorY = translatorY;
            this.translatorZ = translatorZ;
            this.hasAlpha = hasAlpha;
        }

        static OsrsFrame decode(AnimBase skeleton, byte[] data) {
            if (skeleton == null || data == null || data.length < 3) {
                return null;
            }
            Packet in = new Packet(data);
            Packet values = new Packet(data);
            in.pos = 0;
            in.g2(); // skeleton / framemap id
            int length = in.g1();
            values.pos = in.pos + length;
            if (values.pos > data.length) {
                return null;
            }

            int[] indexFrameIds = new int[500];
            int[] scratchX = new int[500];
            int[] scratchY = new int[500];
            int[] scratchZ = new int[500];

            int lastIndex = -1;
            int transformCount = 0;
            boolean hasAlpha = false;

            for (int i = 0; i < length && i < skeleton.types.length; i++) {
                if (in.pos >= data.length) {
                    return null;
                }
                int mask = in.g1();
                if (mask <= 0) {
                    continue;
                }

                if (skeleton.types[i] != 0) {
                    for (int j = i - 1; j > lastIndex; --j) {
                        if (skeleton.types[j] == 0) {
                            indexFrameIds[transformCount] = j;
                            scratchX[transformCount] = 0;
                            scratchY[transformCount] = 0;
                            scratchZ[transformCount] = 0;
                            transformCount++;
                            break;
                        }
                    }
                }

                indexFrameIds[transformCount] = i;
                int defaultValue = skeleton.types[i] == 3 ? 128 : 0;
                scratchX[transformCount] = (mask & 1) != 0 ? values.gsmart() : defaultValue;
                scratchY[transformCount] = (mask & 2) != 0 ? values.gsmart() : defaultValue;
                scratchZ[transformCount] = (mask & 4) != 0 ? values.gsmart() : defaultValue;

                lastIndex = i;
                if (skeleton.types[i] == 5) {
                    hasAlpha = true;
                }
                transformCount++;
            }

            if (values.pos != data.length) {
                return null;
            }

            int[] transformIndices = Arrays.copyOf(indexFrameIds, transformCount);
            int[] x = Arrays.copyOf(scratchX, transformCount);
            int[] y = Arrays.copyOf(scratchY, transformCount);
            int[] z = Arrays.copyOf(scratchZ, transformCount);
            return new OsrsFrame(transformCount, transformIndices, x, y, z, hasAlpha);
        }

        OsrsFrame filter(AnimBase skeleton, boolean[] supportedLabels) {
            if (skeleton == null || supportedLabels == null) {
                return this;
            }
            int kept = 0;
            int[] filteredIndices = new int[this.transformCount];
            int[] filteredX = new int[this.transformCount];
            int[] filteredY = new int[this.transformCount];
            int[] filteredZ = new int[this.transformCount];
            boolean hasAlphaKept = false;
            for (int i = 0; i < this.transformCount; i++) {
                int transformIndex = this.transformIndices[i];
                if (transformIndex < 0 || transformIndex >= skeleton.labels.length) {
                    continue;
                }
                if (skeleton.types[transformIndex] != 0 && !hasSupportedLabel(skeleton.labels[transformIndex], supportedLabels)) {
                    continue;
                }
                filteredIndices[kept] = transformIndex;
                filteredX[kept] = this.translatorX[i];
                filteredY[kept] = this.translatorY[i];
                filteredZ[kept] = this.translatorZ[i];
                if (skeleton.types[transformIndex] == 5) {
                    hasAlphaKept = true;
                }
                kept++;
            }
            if (kept == 0) {
                return null;
            }
            return new OsrsFrame(
                    kept,
                    Arrays.copyOf(filteredIndices, kept),
                    Arrays.copyOf(filteredX, kept),
                    Arrays.copyOf(filteredY, kept),
                    Arrays.copyOf(filteredZ, kept),
                    hasAlphaKept
            );
        }

        private boolean hasSupportedLabel(int[] labels, boolean[] supportedLabels) {
            if (labels == null) {
                return false;
            }
            for (int label : labels) {
                if (label >= 0 && label < supportedLabels.length && supportedLabels[label]) {
                    return true;
                }
            }
            return false;
        }

    }

    private static final class OsrsModel {
        int vertexCount;
        int faceCount;
        int[] verticesX;
        int[] verticesY;
        int[] verticesZ;
        int[] faceVertexA;
        int[] faceVertexB;
        int[] faceVertexC;
        short[] faceColors;
        int[] packedVertexGroups;

        static OsrsModel decode(byte[] data) {
            if (data == null || data.length < 18) {
                return null;
            }
            try {
                OsrsModel model = new OsrsModel();
                if (data[data.length - 1] == (byte) 0xFD && data[data.length - 2] == (byte) 0xFF) {
                    decodeType3(model, data);
                } else if (data[data.length - 1] == (byte) 0xFE && data[data.length - 2] == (byte) 0xFF) {
                    decodeType2(model, data);
                } else if (data[data.length - 1] == (byte) 0xFF && data[data.length - 2] == (byte) 0xFF) {
                    decodeType1(model, data);
                } else {
                    decodeOldFormat(model, data);
                }
                return model;
            } catch (Exception ex) {
                return null;
            }
        }

        private static void decodeType3(OsrsModel out, byte[] data) {
            Packet p0 = new Packet(data);
            Packet p1 = new Packet(data);
            Packet p2 = new Packet(data);
            Packet p3 = new Packet(data);
            Packet p4 = new Packet(data);
            Packet p5 = new Packet(data);
            Packet p6 = new Packet(data);
            p0.pos = data.length - 26;
            int vertexCount = p0.g2();
            int faceCount = p0.g2();
            int textureCount = p0.g1();
            int hasFaceRenderTypes = p0.g1();
            int facePriority = p0.g1();
            int hasFaceAlpha = p0.g1();
            int hasFaceSkins = p0.g1();
            int hasFaceTextures = p0.g1();
            int hasVertexSkins = p0.g1();
            int hasAnimaya = p0.g1();
            int xDataLength = p0.g2();
            int yDataLength = p0.g2();
            int zDataLength = p0.g2();
            int faceIndexDataLength = p0.g2();
            int faceTextureDataLength = p0.g2();
            int faceSkinDataLength = p0.g2();

            int textureType0 = 0;
            int textureType1To3 = 0;
            int textureType2 = 0;
            byte[] textureRenderTypes = new byte[textureCount];
            if (textureCount > 0) {
                p0.pos = 0;
                for (int i = 0; i < textureCount; i++) {
                    byte type = p0.g1b();
                    textureRenderTypes[i] = type;
                    if (type == 0) {
                        textureType0++;
                    } else if (type >= 1 && type <= 3) {
                        textureType1To3++;
                        if (type == 2) {
                            textureType2++;
                        }
                    }
                }
            }

            int pos = textureCount + vertexCount;
            int faceRenderTypesPos = pos;
            if (hasFaceRenderTypes == 1) {
                pos += faceCount;
            }
            int faceIndexCompressionPos = pos;
            pos += faceCount;
            int facePriorityPos = pos;
            if (facePriority == 255) {
                pos += faceCount;
            }
            int faceSkinPos = pos;
            if (hasFaceSkins == 1) {
                pos += faceCount;
            }
            int vertexSkinPos = pos;
            pos += faceSkinDataLength;
            int faceAlphaPos = pos;
            if (hasFaceAlpha == 1) {
                pos += faceCount;
            }
            int faceIndexDataPos = pos;
            pos += faceIndexDataLength;
            int faceTexturePos = pos;
            if (hasFaceTextures == 1) {
                pos += faceCount * 2;
            }
            int faceTextureCoordPos = pos;
            pos += faceTextureDataLength;
            int faceColorPos = pos;
            pos += faceCount * 2;
            int xDataPos = pos;
            pos += xDataLength;
            int yDataPos = pos;
            pos += yDataLength;
            int zDataPos = pos;
            pos += zDataLength;
            int textureIndices0Pos = pos;
            pos += textureType0 * 6;
            int textureIndices1Pos = pos;
            pos += textureType1To3 * 6;
            int textureIndices2Pos = pos;
            pos += textureType1To3 * 6;
            int textureExtra1Pos = pos;
            pos += textureType1To3 * 2;
            int textureExtra2Pos = pos;
            pos += textureType1To3;
            int animayaPos = pos;
            pos += textureType1To3 * 2 + textureType2 * 2;

            out.vertexCount = vertexCount;
            out.faceCount = faceCount;
            out.verticesX = new int[vertexCount];
            out.verticesY = new int[vertexCount];
            out.verticesZ = new int[vertexCount];
            out.faceVertexA = new int[faceCount];
            out.faceVertexB = new int[faceCount];
            out.faceVertexC = new int[faceCount];
            out.faceColors = new short[faceCount];
            out.packedVertexGroups = new int[vertexCount];

            p0.pos = textureCount;
            p1.pos = xDataPos;
            p2.pos = yDataPos;
            p3.pos = zDataPos;
            p4.pos = vertexSkinPos;
            int lastX = 0;
            int lastY = 0;
            int lastZ = 0;
            for (int i = 0; i < vertexCount; i++) {
                int flags = p0.g1();
                int dx = (flags & 1) != 0 ? p1.gsmart() : 0;
                int dy = (flags & 2) != 0 ? p2.gsmart() : 0;
                int dz = (flags & 4) != 0 ? p3.gsmart() : 0;
                out.verticesX[i] = lastX + dx;
                out.verticesY[i] = lastY + dy;
                out.verticesZ[i] = lastZ + dz;
                lastX = out.verticesX[i];
                lastY = out.verticesY[i];
                lastZ = out.verticesZ[i];
                if (hasVertexSkins == 1) {
                    out.packedVertexGroups[i] = p4.g1();
                }
            }
            if (hasAnimaya == 1) {
                for (int i = 0; i < vertexCount; i++) {
                    int count = p4.g1();
                    for (int j = 0; j < count; j++) {
                        p4.g1();
                        p4.g1();
                    }
                }
            }

            p0.pos = faceColorPos;
            p1.pos = faceRenderTypesPos;
            p2.pos = facePriorityPos;
            p3.pos = faceAlphaPos;
            p4.pos = faceSkinPos;
            p5.pos = faceTexturePos;
            p6.pos = faceTextureCoordPos;
            for (int i = 0; i < faceCount; i++) {
                out.faceColors[i] = (short) p0.g2();
                if (hasFaceRenderTypes == 1) {
                    p1.g1b();
                }
                if (facePriority == 255) {
                    p2.g1b();
                }
                if (hasFaceAlpha == 1) {
                    p3.g1b();
                }
                if (hasFaceSkins == 1) {
                    p4.g1();
                }
                if (hasFaceTextures == 1) {
                    p5.g2();
                }
                if (hasFaceTextures == 1 && textureCount > 0) {
                    p6.g1();
                }
            }

            p0.pos = faceIndexDataPos;
            p1.pos = faceIndexCompressionPos;
            int a = 0;
            int b = 0;
            int c = 0;
            int last = 0;
            for (int i = 0; i < faceCount; i++) {
                int type = p1.g1();
                if (type == 1) {
                    a = p0.gsmart() + last;
                    b = p0.gsmart() + a;
                    c = p0.gsmart() + b;
                    last = c;
                } else if (type == 2) {
                    b = c;
                    c = p0.gsmart() + last;
                    last = c;
                } else if (type == 3) {
                    a = c;
                    c = p0.gsmart() + last;
                    last = c;
                } else if (type == 4) {
                    int swap = a;
                    a = b;
                    b = swap;
                    c = p0.gsmart() + last;
                    last = c;
                }
                out.faceVertexA[i] = a;
                out.faceVertexB[i] = b;
                out.faceVertexC[i] = c;
            }

            p0.pos = textureIndices0Pos;
            p1.pos = textureIndices1Pos;
            p2.pos = textureIndices2Pos;
            p3.pos = textureExtra1Pos;
            p4.pos = textureExtra2Pos;
            p5.pos = animayaPos;
            for (int i = 0; i < textureCount; i++) {
                int type = textureRenderTypes[i] & 0xFF;
                if (type == 0) {
                    p0.g2();
                    p0.g2();
                    p0.g2();
                } else if (type >= 1 && type <= 3) {
                    p1.g2();
                    p1.g2();
                    p1.g2();
                    p2.g2();
                    p2.g2();
                    p2.g2();
                    p3.g2();
                    p4.g1();
                    p5.g2();
                    if (type == 2) {
                        p5.g2();
                    }
                }
            }
        }

        private static void decodeType2(OsrsModel out, byte[] data) {
            Packet p0 = new Packet(data);
            Packet p1 = new Packet(data);
            Packet p2 = new Packet(data);
            Packet p3 = new Packet(data);
            Packet p4 = new Packet(data);
            p0.pos = data.length - 23;
            int vertexCount = p0.g2();
            int faceCount = p0.g2();
            int textureCount = p0.g1();
            int hasFaceRenderTypes = p0.g1();
            int facePriority = p0.g1();
            int hasFaceAlpha = p0.g1();
            int hasFaceSkins = p0.g1();
            int hasVertexSkins = p0.g1();
            int hasAnimaya = p0.g1();
            int xDataLength = p0.g2();
            int yDataLength = p0.g2();
            int zDataLength = p0.g2();
            int faceIndexDataLength = p0.g2();
            int vertexSkinDataLength = p0.g2();

            int pos = vertexCount;
            int faceIndexCompressionPos = pos;
            pos += faceCount;
            int facePriorityPos = pos;
            if (facePriority == 255) {
                pos += faceCount;
            }
            int faceSkinPos = pos;
            if (hasFaceSkins == 1) {
                pos += faceCount;
            }
            int faceRenderTypesPos = pos;
            if (hasFaceRenderTypes == 1) {
                pos += faceCount;
            }
            int vertexSkinPos = pos;
            pos += vertexSkinDataLength;
            int faceAlphaPos = pos;
            if (hasFaceAlpha == 1) {
                pos += faceCount;
            }
            int faceIndexDataPos = pos;
            pos += faceIndexDataLength;
            int faceColorPos = pos;
            pos += faceCount * 2;
            int textureIndicesPos = pos;
            pos += textureCount * 6;
            int xDataPos = pos;
            pos += xDataLength;
            int yDataPos = pos;
            pos += yDataLength;
            int zDataPos = pos;

            out.vertexCount = vertexCount;
            out.faceCount = faceCount;
            out.verticesX = new int[vertexCount];
            out.verticesY = new int[vertexCount];
            out.verticesZ = new int[vertexCount];
            out.faceVertexA = new int[faceCount];
            out.faceVertexB = new int[faceCount];
            out.faceVertexC = new int[faceCount];
            out.faceColors = new short[faceCount];
            out.packedVertexGroups = new int[vertexCount];

            p0.pos = 0;
            p1.pos = xDataPos;
            p2.pos = yDataPos;
            p3.pos = zDataPos;
            p4.pos = vertexSkinPos;
            int lastX = 0;
            int lastY = 0;
            int lastZ = 0;
            for (int i = 0; i < vertexCount; i++) {
                int flags = p0.g1();
                int dx = (flags & 1) != 0 ? p1.gsmart() : 0;
                int dy = (flags & 2) != 0 ? p2.gsmart() : 0;
                int dz = (flags & 4) != 0 ? p3.gsmart() : 0;
                out.verticesX[i] = lastX + dx;
                out.verticesY[i] = lastY + dy;
                out.verticesZ[i] = lastZ + dz;
                lastX = out.verticesX[i];
                lastY = out.verticesY[i];
                lastZ = out.verticesZ[i];
                if (hasVertexSkins == 1) {
                    out.packedVertexGroups[i] = p4.g1();
                }
            }
            if (hasAnimaya == 1) {
                for (int i = 0; i < vertexCount; i++) {
                    int count = p4.g1();
                    for (int j = 0; j < count; j++) {
                        p4.g1();
                        p4.g1();
                    }
                }
            }

            p0.pos = faceColorPos;
            p1.pos = faceRenderTypesPos;
            p2.pos = facePriorityPos;
            p3.pos = faceAlphaPos;
            p4.pos = faceSkinPos;
            for (int i = 0; i < faceCount; i++) {
                out.faceColors[i] = (short) p0.g2();
                if (hasFaceRenderTypes == 1) {
                    p1.g1();
                }
                if (facePriority == 255) {
                    p2.g1b();
                }
                if (hasFaceAlpha == 1) {
                    p3.g1b();
                }
                if (hasFaceSkins == 1) {
                    p4.g1();
                }
            }

            p0.pos = faceIndexDataPos;
            p1.pos = faceIndexCompressionPos;
            int a = 0;
            int b = 0;
            int c = 0;
            int last = 0;
            for (int i = 0; i < faceCount; i++) {
                int type = p1.g1();
                if (type == 1) {
                    a = p0.gsmart() + last;
                    b = p0.gsmart() + a;
                    c = p0.gsmart() + b;
                    last = c;
                } else if (type == 2) {
                    b = c;
                    c = p0.gsmart() + last;
                    last = c;
                } else if (type == 3) {
                    a = c;
                    c = p0.gsmart() + last;
                    last = c;
                } else if (type == 4) {
                    int swap = a;
                    a = b;
                    b = swap;
                    c = p0.gsmart() + last;
                    last = c;
                }
                out.faceVertexA[i] = a;
                out.faceVertexB[i] = b;
                out.faceVertexC[i] = c;
            }

            p0.pos = textureIndicesPos;
            for (int i = 0; i < textureCount; i++) {
                p0.g2();
                p0.g2();
                p0.g2();
            }
        }

        private static void decodeType1(OsrsModel out, byte[] data) {
            decodeType2(out, data);
        }

        private static void decodeOldFormat(OsrsModel out, byte[] data) {
            Packet footer = new Packet(data);
            footer.pos = data.length - 18;

            int vertexCount = footer.g2();
            int faceCount = footer.g2();
            int texturedFaceCount = footer.g1();
            int hasFaceTypes = footer.g1();
            int hasPriority = footer.g1();
            int hasAlpha = footer.g1();
            int hasFaceSkin = footer.g1();
            int hasVertexSkin = footer.g1();
            int xDataLength = footer.g2();
            int yDataLength = footer.g2();
            int zDataLength = footer.g2();
            int faceIndexLength = footer.g2();

            int pos = 0;
            int vertexFlagsPos = pos;
            pos += vertexCount;
            int faceTypePos = pos;
            pos += faceCount;
            int facePriorityPos = pos;
            if (hasPriority == 255) {
                pos += faceCount;
            }
            int faceSkinPos = pos;
            if (hasFaceSkin == 1) {
                pos += faceCount;
            }
            int faceVertexTypePos = pos;
            pos += faceCount;
            int vertexSkinPos = pos;
            if (hasVertexSkin == 1) {
                pos += vertexCount;
            }
            int faceAlphaPos = pos;
            if (hasAlpha == 1) {
                pos += faceCount;
            }
            int faceIndexPos = pos;
            pos += faceIndexLength;
            int faceColorPos = pos;
            pos += faceCount * 2;
            int texturePos = pos;
            pos += texturedFaceCount * 6;
            int xDataPos = pos;
            pos += xDataLength;
            int yDataPos = pos;
            pos += yDataLength;
            int zDataPos = pos;

            Packet vertexFlags = new Packet(data);
            vertexFlags.pos = vertexFlagsPos;
            Packet xData = new Packet(data);
            xData.pos = xDataPos;
            Packet yData = new Packet(data);
            yData.pos = yDataPos;
            Packet zData = new Packet(data);
            zData.pos = zDataPos;
            Packet vertexSkin = new Packet(data);
            vertexSkin.pos = vertexSkinPos;

            out.vertexCount = vertexCount;
            out.faceCount = faceCount;
            out.verticesX = new int[vertexCount];
            out.verticesY = new int[vertexCount];
            out.verticesZ = new int[vertexCount];
            out.packedVertexGroups = new int[vertexCount];

            int lastX = 0;
            int lastY = 0;
            int lastZ = 0;
            for (int i = 0; i < vertexCount; i++) {
                int flags = vertexFlags.g1();
                int dx = (flags & 1) != 0 ? xData.gsmart() : 0;
                int dy = (flags & 2) != 0 ? yData.gsmart() : 0;
                int dz = (flags & 4) != 0 ? zData.gsmart() : 0;
                lastX += dx;
                lastY += dy;
                lastZ += dz;
                out.verticesX[i] = lastX;
                out.verticesY[i] = lastY;
                out.verticesZ[i] = lastZ;
                out.packedVertexGroups[i] = hasVertexSkin == 1 ? vertexSkin.g1() : 0;
            }

            Packet faceColor = new Packet(data);
            faceColor.pos = faceColorPos;
            Packet faceVertexType = new Packet(data);
            faceVertexType.pos = faceVertexTypePos;
            Packet faceIndex = new Packet(data);
            faceIndex.pos = faceIndexPos;

            out.faceColors = new short[faceCount];
            out.faceVertexA = new int[faceCount];
            out.faceVertexB = new int[faceCount];
            out.faceVertexC = new int[faceCount];

            for (int i = 0; i < faceCount; i++) {
                out.faceColors[i] = (short) faceColor.g2();
            }

            int a = 0;
            int b = 0;
            int c = 0;
            int last = 0;
            for (int i = 0; i < faceCount; i++) {
                int typeFlag = faceVertexType.g1();
                if (typeFlag == 1) {
                    a = faceIndex.gsmarts() + last;
                    last = a;
                    b = faceIndex.gsmarts() + last;
                    last = b;
                    c = faceIndex.gsmarts() + last;
                    last = c;
                } else if (typeFlag == 2) {
                    b = c;
                    c = faceIndex.gsmarts() + last;
                    last = c;
                } else if (typeFlag == 3) {
                    a = c;
                    c = faceIndex.gsmarts() + last;
                    last = c;
                } else if (typeFlag == 4) {
                    int swap = a;
                    a = b;
                    b = swap;
                    c = faceIndex.gsmarts() + last;
                    last = c;
                }
                out.faceVertexA[i] = a;
                out.faceVertexB[i] = b;
                out.faceVertexC[i] = c;
            }
        }
    }
}
