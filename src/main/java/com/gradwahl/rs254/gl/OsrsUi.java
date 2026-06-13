package com.gradwahl.rs254.gl;

import jagex2.graphics.Pix32;
import jagex2.io.JagFile;

import java.awt.Component;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads the modern OSRS UI sprites packed into <cacheDir>/osrsui.jag by the
 * tools/osrs-extract packer. Each entry is the raw PNG bytes of one sprite;
 * we decode them through Pix32's image constructor so partial alpha survives
 * (blit them with {@link Pix32#plotAlpha}). The archive is read directly rather
 * than via Client.getJagFile() to avoid its CRC/versionlist + URL download path.
 *
 * See memory: osrs-ui-sprite-extractor.
 */
public final class OsrsUi {

    /** Names must match the packer's MANIFEST keys. */
    public static final String[] NAMES = {
        "minimap_frame", "minimap_frame_fixed", "minimap_frame_thin",
        "minimap_mask_fixed", "compass_texture", "compass_socket", "compass_arrow", "compass_bg", "tab_stone", "tab_stone_selected",
        "sidebar_panel", "right_panel_tall_pillar", "frame_top",
        "sidebar_outer_left", "sidebar_outer_right", "sidebar_top_stone", "sidebar_bottom_stone",
        "minimap_side_left", "minimap_side_right", "right_top_strip",
        "tabrow_top", "tabrow_bottom", "chat_bg",
        "tab_sel_1026", "tab_sel_1027", "tab_sel_1028", "tab_sel_1029", "tab_sel_1030", "tab_1032",
        "chatbox_pillar_left", "chatbox_pillar_right",
        "chat_bg_fixed", "chat_bg_full", "chat_tab_backing",
        "chat_tab_grey", "chat_tab_blue", "chat_tab_red",
        "chat_tab_small", "chat_tab_small_hover", "chat_tab_small_blue",
        "chat_tab_report", "chat_tab_report_hover",
        "orb_red", "orb_green", "orb_yellow", "orb_purple", "orb_grey", "orb_gold", "orb_blue",
        "orb_icon_hp", "orb_icon_prayer", "orb_icon_spec", "orb_icon_run_off", "orb_icon_run_on",
        "orb_hp_bg", "orb_hp_bg_empty",
        "orb_prayer_bg", "orb_prayer_bg_empty",
        "orb_run_bg", "orb_run_bg_empty",
        "orb_mount_a", "orb_mount_b",
        "xp_a", "xp_b", "xp_c", "xp_d",
        "bar_wood", "bar_red",
        "spec_star_filled", "spec_star_outline",
        "resize_arrow_a", "resize_arrow_b",
        // Sidebar tab icons — extracted by extract_sidebar_icons.py (OSRS sprite IDs 62-74).
        // Indices match imageSideicons[0..12] in the 2004 client.
        "tab_icon_0", "tab_icon_1", "tab_icon_2", "tab_icon_3", "tab_icon_4",
        "tab_icon_5", "tab_icon_6", "tab_icon_7", "tab_icon_8", "tab_icon_9",
        "tab_icon_10", "tab_icon_11", "tab_icon_12",
        // World map orb button (b238 cache: 1697/1698/1699)
        "worldmap_orb_base", "worldmap_orb_icon", "worldmap_orb_icon_active",
        // Mute/unmute icons (OSRS 811:0/1)
        "title_mute_unmuted", "title_mute_muted",
        // Chat mod crown icons (OSRS 423:0/1)
        "mod_icon_player", "mod_icon_jagex",
        // Character design arrow buttons (OSRS 425:0, 426:0)
        "design_arrow_left", "design_arrow_right",
        // XP progress bar (sprite 5615 = yellow fill, 5616 = dark background, 90x5)
        "xp_bar_fill", "xp_bar_bg",
    };

    private static final Map<String, Pix32> SPRITES = new HashMap<>();
    private static boolean loaded = false;

    private OsrsUi() {
    }

    public static boolean isLoaded() {
        return loaded;
    }

    /**
     * Loads osrsui.jag from the given cache directory. Safe to call once at
     * startup after the AWT component exists (needed to decode PNG bytes).
     * Returns true if the archive was found and at least one sprite loaded.
     */
    public static boolean load(String cacheDir, Component comp) {
        if (loaded) {
            return true;
        }
        try {
            File jag = new File(cacheDir, "osrsui.jag");
            JagFile archive = jag.isFile() ? new JagFile(Files.readAllBytes(jag.toPath())) : null;
            if (archive == null) {
                System.out.println("[OsrsUi] osrsui.jag not found in " + cacheDir + " - trying bundled UI sprites");
            }
            int ok = 0;
            for (String name : NAMES) {
                byte[] png = readClasspathSprite(name);
                if (png == null) {
                    png = archive == null ? null : archive.read(name + ".dat", null);
                }
                if (png == null) {
                    System.out.println("[OsrsUi] missing entry: " + name);
                    continue;
                }
                Pix32 sprite = new Pix32(png, comp);
                if (sprite.data == null || sprite.wi <= 0) {
                    System.out.println("[OsrsUi] failed to decode: " + name);
                    continue;
                }
                SPRITES.put(name, sprite);
                ok++;
            }
            // Enable the modern fixed gameframe whenever a complete enough OSRS
            // sprite set is available, whether it came from bundled PNGs or the cache archive.
            loaded = ok >= 10;
            System.out.println("[OsrsUi] loaded " + ok + "/" + NAMES.length + " UI sprites");

            // Generate a black-filled orb that matches the run/prayer orb shape.
            // Used as the "empty" background so the unfilled area above the gold/blue
            // fill gauge is black rather than the silver of orb_run_bg_empty.
            Pix32 orbMask = SPRITES.get("orb_red");
            if (orbMask == null) {
                orbMask = SPRITES.get("orb_run_bg");
            }
            if (orbMask != null) {
                Pix32 black = new Pix32(orbMask.wi, orbMask.hi);
                for (int i = 0; i < orbMask.data.length; i++) {
                    int a = orbMask.data[i] >>> 24;
                    black.data[i] = a > 0 ? 0xFF000000 : 0;
                }
                SPRITES.put("orb_bg_black", black);
            }

            // Generate a vertically-flipped tab_stone_selected for the top tab row,
            // whose stones hang downward (opposite orientation to the bottom row).
            Pix32 sel = SPRITES.get("tab_stone_selected");
            if (sel != null) {
                Pix32 selV = new Pix32(sel.wi, sel.hi);
                for (int row = 0; row < sel.hi; row++) {
                    System.arraycopy(sel.data, row * sel.wi, selV.data, (sel.hi - 1 - row) * sel.wi, sel.wi);
                }
                SPRITES.put("tab_stone_selected_v", selV);
            }

            return loaded;
        } catch (Exception e) {
            System.out.println("[OsrsUi] load failed: " + e);
            return false;
        }
    }

    private static byte[] readClasspathSprite(String name) {
        try (java.io.InputStream in = OsrsUi.class.getResourceAsStream("/osrsui/" + name + ".png")) {
            return in == null ? null : in.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns the named sprite, or null if not loaded/missing. */
    public static Pix32 get(String name) {
        return SPRITES.get(name);
    }
}
