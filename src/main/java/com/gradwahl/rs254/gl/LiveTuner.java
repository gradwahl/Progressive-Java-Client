package com.gradwahl.rs254.gl;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;

/**
 * Floating Swing panel for live-tweaking the fixed OSRS/RuneLite-style UI.
 * Press F12 in-game to open/close.
 *
 * All fields are volatile so the game render thread reads the latest values
 * every frame without a recompile. Any spinner change bumps {@link #version};
 * Client.gameDraw() uses that to force the chat/sidebar/tab buffers to redraw.
 */
public class LiveTuner extends JFrame {

    // Incremented every time a control changes so cached PixMaps redraw live.
    public static volatile int version = 0;

    // ── Minimap / top-right ─────────────────────────────────────────────────
    public static volatile int minimapOffsetX = 11;
    public static volatile int minimapOffsetY = 7;
    public static volatile int frameOffsetX = 11;
    public static volatile int frameOffsetY = -10;
    public static volatile int compassOffsetX = 0;
    public static volatile int compassOffsetY = 0;
    public static volatile int leftBgOffsetX = 0;
    public static volatile int leftBgOffsetY = -10;
    public static volatile int rightBgOffsetX = 11;
    public static volatile int rightBgOffsetY = -14;
    public static volatile int rightTopBgOffsetX = 0;
    public static volatile int rightTopBgOffsetY = 0;

    // ── Orbs ─────────────────────────────────────────────────────────────────
    public static volatile int orbHpX = 25;
    public static volatile int orbHpY = 41;
    public static volatile int orbPrayerX = 25;
    public static volatile int orbPrayerY = 87;
    public static volatile int orbRunX = 49;
    public static volatile int orbRunY = 119;
    public static volatile int orbNumOffsetX = -14;
    public static volatile int orbNumOffsetY = 4;
    public static volatile int orbFillOffsetX = 3;
    public static volatile int orbFillOffsetY = 1;
    public static volatile int orbHpIconOffsetX = 0;
    public static volatile int orbHpIconOffsetY = 0;
    public static volatile int orbPrayerIconOffsetX = 0;
    public static volatile int orbPrayerIconOffsetY = 0;
    public static volatile int orbRunIconOffsetX = 0;
    public static volatile int orbRunIconOffsetY = 0;
    public static volatile int xpButtonX = 3;
    public static volatile int xpButtonY = 20;
    public static volatile int worldmapOrbX = 200;
    public static volatile int worldmapOrbY = 130;

    // ── Right-side tabs ──────────────────────────────────────────────────────
    public static volatile int topTabsOffsetX = 0;
    public static volatile int topTabsOffsetY = -12;
    public static volatile int bottomTabsOffsetX = 3;
    public static volatile int bottomTabsOffsetY = -4;

    // Per-icon X/Y offsets, indexed 0-12 matching imageSideicons / tab_icon_N.
    // Icons 0-6 are in the top tab row; icons 7-12 in the bottom tab row.
    // volatile on the array reference; element reads are fine for a UI tuner.
    public static volatile int[] sideIconOffsetX = {
        -12, -4, 1, -3, -6, 0, 6,   // top row: Attack..Magic
        -27, -22, -24, -29, -21, -13  // bot row: Friends..Music
    };
    public static volatile int[] sideIconOffsetY = {
          3,  3,  3, -1, -3,  0,  1,
          4,  4, -1,  4,  3,  3
    };

    // ── Stone offsets (redstone highlight only) ──────────────────────────────
    // Fine-tune the red-glow sprite position within the tabrow buffer.
    // X shifts left/right; Y shifts the glow up/down within the stone row buffer.
    // Click zones follow sideIconOffsetX/Y — these control only the visual glow.
    // Indices 0-6 match the tab order left→right in each row.
    public static volatile int[] topStoneOffsetX    = {7, 7, 4, 1, -1, -4, -3};
    public static volatile int[] topStoneOffsetY    = {-1, -1, -1, -1, -1, -1, -1};
    public static volatile int[] bottomStoneOffsetX = {0, 5, 4, 3, 1, 0, 1};
    public static volatile int[] bottomStoneOffsetY = {0, 0, 0, 0, 0, 0, 0};

    // ── Inventory / sidebar ──────────────────────────────────────────────────
    public static volatile int inventoryBgOffsetX = -1;
    public static volatile int inventoryBgOffsetY = -4;
    public static volatile int inventoryContentOffsetX = 0;
    public static volatile int inventoryContentOffsetY = 0;
    public static volatile int leftOuterOffsetX = 0;
    public static volatile int leftOuterOffsetY = 0;
    public static volatile int rightOuterOffsetX = 0;
    public static volatile int rightOuterOffsetY = 0;
    public static volatile int leftPillarAOffsetX = -12;
    public static volatile int leftPillarAOffsetY = -4;
    public static volatile int leftPillarBOffsetX = -9;
    public static volatile int leftPillarBOffsetY = 129;
    public static volatile int rightPillarOffsetX = 10;
    public static volatile int rightPillarOffsetY = -4;

    // ── Chatbox / bottom buttons ─────────────────────────────────────────────
    public static volatile int chatBoxOffsetX = 0;
    public static volatile int chatBoxOffsetY = -1;
    public static volatile int chatButtonBgOffsetX = 0;
    public static volatile int chatButtonBgOffsetY = -4;
    public static volatile int chatButtonOffsetX = 2;
    public static volatile int chatButtonOffsetY = 1;
    // Extra shift applied to dialogue / level-up interface content inside the chatbox.
    public static volatile int chatDialogOffsetX = 8;
    public static volatile int chatDialogOffsetY = 6;

    // ── Game window (ViewBox outer frame) ────────────────────────────────────
    public static volatile int canvasOffsetX = 0;
    public static volatile int canvasOffsetY = 0;
    public static volatile int titleBarH = 26;
    public static volatile int windowExtraW = 8;
    public static volatile int windowExtraH = 5;

    // ── 3-D viewport only (scene moves; UI/buttons/minimap stay fixed) ───────
    public static volatile int viewportOffsetX = 0;
    public static volatile int viewportOffsetY = 0;

    /** Set by ViewBox at startup; called on the EDT when any game-window field changes. */
    public static volatile Runnable windowRelayoutHook;

    private static void relayoutWindow() {
        Runnable h = windowRelayoutHook;
        if (h != null) SwingUtilities.invokeLater(h);
    }

    // ── Singleton ────────────────────────────────────────────────────────────
    private static LiveTuner instance;

    public static void toggle() {
        SwingUtilities.invokeLater(() -> {
            if (instance == null || !instance.isDisplayable()) {
                instance = new LiveTuner();
                instance.setVisible(true);
            } else if (instance.isVisible()) {
                instance.setVisible(false);
            } else {
                instance.setVisible(true);
                instance.toFront();
            }
        });
    }

    private static final String[] ICON_NAMES = {
        "Attack", "Skills", "Quest", "Invent", "Equip", "Prayer", "Magic",
        "Friends", "Ignore", "Logout", "Settings", "Emotes", "Music"
    };

    private LiveTuner() {
        super("Fixed UI Tuner");
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setAlwaysOnTop(true);
        setResizable(true);

        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        root.add(buildSection("Minimap / top-right",
            row("Map X",       -200, 200, minimapOffsetX,  v -> minimapOffsetX  = v),
            row("Map Y",       -200, 200, minimapOffsetY,  v -> minimapOffsetY  = v),
            row("Frame X",     -200, 200, frameOffsetX,    v -> frameOffsetX    = v),
            row("Frame Y",     -200, 200, frameOffsetY,    v -> frameOffsetY    = v),
            row("Compass X",   -200, 200, compassOffsetX,  v -> compassOffsetX  = v),
            row("Compass Y",   -200, 200, compassOffsetY,  v -> compassOffsetY  = v),
            row("Left BG X",   -200, 200, leftBgOffsetX,   v -> leftBgOffsetX   = v),
            row("Left BG Y",   -200, 200, leftBgOffsetY,   v -> leftBgOffsetY   = v),
            row("Right BG X",  -200, 200, rightBgOffsetX,  v -> rightBgOffsetX  = v),
            row("Right BG Y",  -200, 200, rightBgOffsetY,  v -> rightBgOffsetY  = v),
            row("Top BG X",    -200, 200, rightTopBgOffsetX, v -> rightTopBgOffsetX = v),
            row("Top BG Y",    -200, 200, rightTopBgOffsetY, v -> rightTopBgOffsetY = v)
        ));
        root.add(buildOrbSection());
        root.add(buildSection("Side tabs / clickable icons",
            row("Top tabs X",  -200, 200, topTabsOffsetX,    v -> topTabsOffsetX    = v),
            row("Top tabs Y",  -200, 200, topTabsOffsetY,    v -> topTabsOffsetY    = v),
            row("Bot tabs X",  -200, 200, bottomTabsOffsetX, v -> bottomTabsOffsetX = v),
            row("Bot tabs Y",  -200, 200, bottomTabsOffsetY, v -> bottomTabsOffsetY = v)
        ));
        root.add(buildIconSection());
        root.add(buildStoneXYSection("Top stone glow offsets",
            new String[]{"Attack","Skills","Quest","Invent","Equip","Prayer","Magic"},
            topStoneOffsetX, topStoneOffsetY));
        root.add(buildStoneXYSection("Bottom stone glow offsets",
            new String[]{"(none)","Friends","Ignore","Logout","Settings","Emotes","Music"},
            bottomStoneOffsetX, bottomStoneOffsetY));
        root.add(buildSection("Inventory / sidebar",
            row("BG X",        -200, 200, inventoryBgOffsetX,      v -> inventoryBgOffsetX      = v),
            row("BG Y",        -200, 200, inventoryBgOffsetY,      v -> inventoryBgOffsetY      = v),
            row("Content X",   -200, 200, inventoryContentOffsetX, v -> inventoryContentOffsetX = v),
            row("Content Y",   -200, 200, inventoryContentOffsetY, v -> inventoryContentOffsetY = v),
            row("Left outer X",-200, 200, leftOuterOffsetX,        v -> leftOuterOffsetX        = v),
            row("Left outer Y",-200, 200, leftOuterOffsetY,        v -> leftOuterOffsetY        = v),
            row("Right outer X",-200,200, rightOuterOffsetX,    v -> rightOuterOffsetX    = v),
            row("Right outer Y",-200,200, rightOuterOffsetY,    v -> rightOuterOffsetY    = v),
            row("Left pil A X", -200,200, leftPillarAOffsetX,   v -> leftPillarAOffsetX   = v),
            row("Left pil A Y", -200,200, leftPillarAOffsetY,   v -> leftPillarAOffsetY   = v),
            row("Left pil B X", -200,200, leftPillarBOffsetX,   v -> leftPillarBOffsetX   = v),
            row("Left pil B Y", -200,200, leftPillarBOffsetY,   v -> leftPillarBOffsetY   = v),
            row("Right pillar X",-200,200, rightPillarOffsetX,  v -> rightPillarOffsetX   = v),
            row("Right pillar Y",-200,200, rightPillarOffsetY,  v -> rightPillarOffsetY   = v)
        ));
        root.add(buildSection("Chatbox / bottom bar",
            row("Chat X",      -200, 200, chatBoxOffsetX,           v -> chatBoxOffsetX           = v),
            row("Chat Y",      -200, 200, chatBoxOffsetY,           v -> chatBoxOffsetY           = v),
            row("Bar BG X",    -200, 200, chatButtonBgOffsetX,      v -> chatButtonBgOffsetX      = v),
            row("Bar BG Y",    -200, 200, chatButtonBgOffsetY, v -> chatButtonBgOffsetY = v),
            row("Buttons X",   -200, 200, chatButtonOffsetX, v -> chatButtonOffsetX = v),
            row("Buttons Y",   -200, 200, chatButtonOffsetY, v -> chatButtonOffsetY = v),
            row("Dialog X",    -200, 200, chatDialogOffsetX, v -> chatDialogOffsetX = v),
            row("Dialog Y",    -200, 200, chatDialogOffsetY, v -> chatDialogOffsetY = v)
        ));
        root.add(buildSection("Game window",
            row("Canvas X", -2000, 2000, canvasOffsetX, v -> { canvasOffsetX = v; relayoutWindow(); }),
            row("Canvas Y", -2000, 2000, canvasOffsetY, v -> { canvasOffsetY = v; relayoutWindow(); }),
            row("Title H",     10,  50, titleBarH,     v -> { titleBarH     = v; relayoutWindow(); }),
            row("Extra W",    -10,  30, windowExtraW,  v -> { windowExtraW  = v; relayoutWindow(); }),
            row("Extra H",    -10,  30, windowExtraH,  v -> { windowExtraH  = v; relayoutWindow(); })
        ));
        root.add(buildSection("3D viewport (scene only)",
            row("View X",    -2000, 2000, viewportOffsetX, v -> viewportOffsetX = v),
            row("View Y",    -2000, 2000, viewportOffsetY, v -> viewportOffsetY = v)
        ));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 4));
        JButton copy  = new JButton("Copy constants");
        JButton reset = new JButton("Reset");
        copy.addActionListener(e  -> copyConstants());
        reset.addActionListener(e -> resetDefaults());
        buttons.add(copy);
        buttons.add(reset);
        root.add(buttons);

        JScrollPane scroll = new JScrollPane(root);
        scroll.setPreferredSize(new Dimension(380, 720));
        setContentPane(scroll);
        pack();
        setLocationRelativeTo(null);
    }

    private static JPanel buildOrbSection() {
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(), "Orbs",
            TitledBorder.LEFT, TitledBorder.TOP,
            new Font(Font.SANS_SERIF, Font.BOLD, 11)
        ));
        section.add(iconRow("HP",       orbHpX,        orbHpY,        vx -> orbHpX        = vx, vy -> orbHpY        = vy));
        section.add(iconRow("Prayer",   orbPrayerX,    orbPrayerY,    vx -> orbPrayerX    = vx, vy -> orbPrayerY    = vy));
        section.add(iconRow("Run",      orbRunX,       orbRunY,       vx -> orbRunX       = vx, vy -> orbRunY       = vy));
        section.add(iconRow("Num off",  orbNumOffsetX, orbNumOffsetY, vx -> orbNumOffsetX = vx, vy -> orbNumOffsetY = vy));
        section.add(iconRow("Fill off", orbFillOffsetX, orbFillOffsetY, vx -> orbFillOffsetX = vx, vy -> orbFillOffsetY = vy));
        section.add(iconRow("Icon HP",  orbHpIconOffsetX,     orbHpIconOffsetY,     vx -> orbHpIconOffsetX     = vx, vy -> orbHpIconOffsetY     = vy));
        section.add(iconRow("Icon Pr",  orbPrayerIconOffsetX, orbPrayerIconOffsetY, vx -> orbPrayerIconOffsetX = vx, vy -> orbPrayerIconOffsetY = vy));
        section.add(iconRow("Icon Run", orbRunIconOffsetX,    orbRunIconOffsetY,    vx -> orbRunIconOffsetX    = vx, vy -> orbRunIconOffsetY    = vy));
        section.add(iconRow("XP btn",    xpButtonX,    xpButtonY,    vx -> xpButtonX    = vx, vy -> xpButtonY    = vy));
        section.add(iconRow("World map", worldmapOrbX, worldmapOrbY, vx -> worldmapOrbX = vx, vy -> worldmapOrbY = vy));
        return section;
    }

    private static JPanel buildIconSection() {
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(), "Side icon offsets (per icon)",
            TitledBorder.LEFT, TitledBorder.TOP,
            new Font(Font.SANS_SERIF, Font.BOLD, 11)
        ));
        for (int i = 0; i < ICON_NAMES.length; i++) {
            final int idx = i;
            section.add(iconRow(ICON_NAMES[i], sideIconOffsetX[i], sideIconOffsetY[i],
                vx -> sideIconOffsetX[idx] = vx,
                vy -> sideIconOffsetY[idx] = vy));
        }
        return section;
    }

    private static JPanel buildStoneXYSection(String title, String[] names, int[] arrX, int[] arrY) {
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(), title,
            TitledBorder.LEFT, TitledBorder.TOP,
            new Font(Font.SANS_SERIF, Font.BOLD, 11)
        ));
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            JLabel lbl = new JLabel(String.format("%-8s", names[i]));
            lbl.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

            JLabel xLbl = new JLabel("X");
            xLbl.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            JSpinner spX = new JSpinner(new SpinnerNumberModel(arrX[i], -200, 200, 1));
            spX.setPreferredSize(new Dimension(60, 24));
            ((JSpinner.DefaultEditor) spX.getEditor()).getTextField().setColumns(4);
            spX.addChangeListener(e -> { arrX[idx] = (Integer) spX.getValue(); version++; });

            JLabel yLbl = new JLabel("Y");
            yLbl.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            JSpinner spY = new JSpinner(new SpinnerNumberModel(arrY[i], -50, 50, 1));
            spY.setPreferredSize(new Dimension(60, 24));
            ((JSpinner.DefaultEditor) spY.getEditor()).getTextField().setColumns(4);
            spY.addChangeListener(e -> { arrY[idx] = (Integer) spY.getValue(); version++; });

            p.add(lbl); p.add(xLbl); p.add(spX); p.add(yLbl); p.add(spY);
            section.add(p);
        }
        return section;
    }

    @FunctionalInterface
    private interface IntSetter { void set(int v); }

    private static JPanel iconRow(String label, int initX, int initY, IntSetter setterX, IntSetter setterY) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        JLabel lbl = new JLabel(String.format("%-8s", label));
        lbl.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JLabel xLbl = new JLabel("X");
        xLbl.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JSpinner spX = new JSpinner(new SpinnerNumberModel(initX, -200, 200, 1));
        spX.setPreferredSize(new Dimension(60, 24));
        ((JSpinner.DefaultEditor) spX.getEditor()).getTextField().setColumns(4);
        spX.addChangeListener(e -> { setterX.set((Integer) spX.getValue()); version++; });

        JLabel yLbl = new JLabel("Y");
        yLbl.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JSpinner spY = new JSpinner(new SpinnerNumberModel(initY, -200, 200, 1));
        spY.setPreferredSize(new Dimension(60, 24));
        ((JSpinner.DefaultEditor) spY.getEditor()).getTextField().setColumns(4);
        spY.addChangeListener(e -> { setterY.set((Integer) spY.getValue()); version++; });

        p.add(lbl);
        p.add(xLbl); p.add(spX);
        p.add(yLbl); p.add(spY);
        return p;
    }

    private static JPanel row(String label, int min, int max, int initial, IntSetter setter) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        JLabel lbl = new JLabel(String.format("%-14s", label));
        lbl.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JSpinner sp = new JSpinner(new SpinnerNumberModel(initial, min, max, 1));
        sp.setPreferredSize(new Dimension(70, 24));
        ((JSpinner.DefaultEditor) sp.getEditor()).getTextField().setColumns(5);
        sp.addChangeListener(e -> {
            setter.set((Integer) sp.getValue());
            version++;
        });
        p.add(lbl);
        p.add(sp);
        return p;
    }

    @SafeVarargs
    private static JPanel buildSection(String title, JPanel... rows) {
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(), title,
            TitledBorder.LEFT, TitledBorder.TOP,
            new Font(Font.SANS_SERIF, Font.BOLD, 11)
        ));
        for (JPanel row : rows) section.add(row);
        return section;
    }

    private void copyConstants() {
        StringBuilder iconX = new StringBuilder("{");
        StringBuilder iconY = new StringBuilder("{");
        for (int i = 0; i < sideIconOffsetX.length; i++) {
            if (i > 0) { iconX.append(", "); iconY.append(", "); }
            iconX.append(sideIconOffsetX[i]);
            iconY.append(sideIconOffsetY[i]);
        }
        iconX.append("}"); iconY.append("}");

        StringBuilder topStoneX = new StringBuilder("{");
        StringBuilder topStoneY = new StringBuilder("{");
        StringBuilder botStoneX = new StringBuilder("{");
        StringBuilder botStoneY = new StringBuilder("{");
        for (int i = 0; i < topStoneOffsetX.length; i++) {
            if (i > 0) { topStoneX.append(", "); topStoneY.append(", "); botStoneX.append(", "); botStoneY.append(", "); }
            topStoneX.append(topStoneOffsetX[i]);
            topStoneY.append(topStoneOffsetY[i]);
            botStoneX.append(bottomStoneOffsetX[i]);
            botStoneY.append(bottomStoneOffsetY[i]);
        }
        topStoneX.append("}"); topStoneY.append("}"); botStoneX.append("}"); botStoneY.append("}");

        String out = String.format(
            "// ── Paste into LiveTuner.java defaults ──%n" +
            "minimapOffsetX=%d; minimapOffsetY=%d;%n" +
            "frameOffsetX=%d; frameOffsetY=%d;%n" +
            "compassOffsetX=%d; compassOffsetY=%d;%n" +
            "leftBgOffsetX=%d; leftBgOffsetY=%d; rightBgOffsetX=%d; rightBgOffsetY=%d;%n" +
            "rightTopBgOffsetX=%d; rightTopBgOffsetY=%d;%n" +
            "orbHpX=%d; orbHpY=%d; orbPrayerX=%d; orbPrayerY=%d; orbRunX=%d; orbRunY=%d; orbNumOffsetX=%d; orbNumOffsetY=%d; orbFillOffsetX=%d; orbFillOffsetY=%d;%n" +
            "orbHpIconOffsetX=%d; orbHpIconOffsetY=%d; orbPrayerIconOffsetX=%d; orbPrayerIconOffsetY=%d; orbRunIconOffsetX=%d; orbRunIconOffsetY=%d;%n" +
            "xpButtonX=%d; xpButtonY=%d;%n" +
            "worldmapOrbX=%d; worldmapOrbY=%d;%n" +
            "topTabsOffsetX=%d; topTabsOffsetY=%d; bottomTabsOffsetX=%d; bottomTabsOffsetY=%d;%n" +
            "sideIconOffsetX=%s;%n" +
            "sideIconOffsetY=%s;%n" +
            "topStoneOffsetX=%s; topStoneOffsetY=%s;%n" +
            "bottomStoneOffsetX=%s; bottomStoneOffsetY=%s;%n" +
            "inventoryBgOffsetX=%d; inventoryBgOffsetY=%d; inventoryContentOffsetX=%d; inventoryContentOffsetY=%d;%n" +
            "leftOuterOffsetX=%d; leftOuterOffsetY=%d; rightOuterOffsetX=%d; rightOuterOffsetY=%d;%n" +
            "leftPillarAOffsetX=%d; leftPillarAOffsetY=%d; leftPillarBOffsetX=%d; leftPillarBOffsetY=%d;%n" +
            "rightPillarOffsetX=%d; rightPillarOffsetY=%d;%n" +
            "chatBoxOffsetX=%d; chatBoxOffsetY=%d; chatButtonBgOffsetX=%d; chatButtonBgOffsetY=%d;%n" +
            "chatButtonOffsetX=%d; chatButtonOffsetY=%d;%n" +
            "chatDialogOffsetX=%d; chatDialogOffsetY=%d;%n" +
            "canvasOffsetX=%d; canvasOffsetY=%d; titleBarH=%d; windowExtraW=%d; windowExtraH=%d;%n",
            minimapOffsetX, minimapOffsetY,
            frameOffsetX, frameOffsetY,
            compassOffsetX, compassOffsetY,
            leftBgOffsetX, leftBgOffsetY, rightBgOffsetX, rightBgOffsetY,
            rightTopBgOffsetX, rightTopBgOffsetY,
            orbHpX, orbHpY, orbPrayerX, orbPrayerY, orbRunX, orbRunY, orbNumOffsetX, orbNumOffsetY, orbFillOffsetX, orbFillOffsetY,
            orbHpIconOffsetX, orbHpIconOffsetY, orbPrayerIconOffsetX, orbPrayerIconOffsetY, orbRunIconOffsetX, orbRunIconOffsetY,
            xpButtonX, xpButtonY,
            worldmapOrbX, worldmapOrbY,
            topTabsOffsetX, topTabsOffsetY, bottomTabsOffsetX, bottomTabsOffsetY,
            iconX, iconY,
            topStoneX, topStoneY, botStoneX, botStoneY,
            inventoryBgOffsetX, inventoryBgOffsetY, inventoryContentOffsetX, inventoryContentOffsetY,
            leftOuterOffsetX, leftOuterOffsetY, rightOuterOffsetX, rightOuterOffsetY,
            leftPillarAOffsetX, leftPillarAOffsetY, leftPillarBOffsetX, leftPillarBOffsetY,
            rightPillarOffsetX, rightPillarOffsetY,
            chatBoxOffsetX, chatBoxOffsetY, chatButtonBgOffsetX, chatButtonBgOffsetY,
            chatButtonOffsetX, chatButtonOffsetY,
            chatDialogOffsetX, chatDialogOffsetY,
            canvasOffsetX, canvasOffsetY, titleBarH, windowExtraW, windowExtraH
        );
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(out), null);
        JOptionPane.showMessageDialog(this,
            "<html><pre>" + out.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>") + "</pre></html>",
            "Copied to clipboard", JOptionPane.INFORMATION_MESSAGE);
    }

    private void resetDefaults() {
        minimapOffsetX = 11; minimapOffsetY = 7;
        frameOffsetX = 11; frameOffsetY = -10;
        compassOffsetX = 0; compassOffsetY = 0;
        leftBgOffsetX = 0; leftBgOffsetY = -10;
        rightBgOffsetX = 11; rightBgOffsetY = -14;
        rightTopBgOffsetX = 0; rightTopBgOffsetY = 0;
        orbHpX = 25; orbHpY = 41; orbPrayerX = 25; orbPrayerY = 87; orbRunX = 49; orbRunY = 119;
        orbNumOffsetX = -14; orbNumOffsetY = 4;
        orbFillOffsetX = 3; orbFillOffsetY = 1;
        orbHpIconOffsetX = 0; orbHpIconOffsetY = 0;
        orbPrayerIconOffsetX = 0; orbPrayerIconOffsetY = 0;
        orbRunIconOffsetX = 0; orbRunIconOffsetY = 0;
        xpButtonX = 3; xpButtonY = 20;
        worldmapOrbX = 200; worldmapOrbY = 130;
        topTabsOffsetX = 0; topTabsOffsetY = -12;
        bottomTabsOffsetX = 3; bottomTabsOffsetY = -4;
        sideIconOffsetX = new int[]{-12, -4, 1, -3, -6, 0, 6, -27, -22, -24, -29, -21, -13};
        sideIconOffsetY = new int[]{  3,  3,  3,  -1, -3,  0,  1,   4,   4,  -1,   4,   3,   3};
        topStoneOffsetX    = new int[]{7, 7, 4, 1, -1, -4, -3};
        topStoneOffsetY    = new int[]{-1, -1, -1, -1, -1, -1, -1};
        bottomStoneOffsetX = new int[]{0, 5, 4, 3, 1, 0, 1};
        bottomStoneOffsetY = new int[]{0, 0, 0, 0, 0, 0, 0};
        inventoryBgOffsetX = -1; inventoryBgOffsetY = -4;
        inventoryContentOffsetX = 0; inventoryContentOffsetY = 0;
        leftOuterOffsetX = 0; leftOuterOffsetY = 0;
        rightOuterOffsetX = 0; rightOuterOffsetY = 0;
        leftPillarAOffsetX = -12; leftPillarAOffsetY = -4;
        leftPillarBOffsetX = -9; leftPillarBOffsetY = 129;
        rightPillarOffsetX = 10; rightPillarOffsetY = -4;
        chatBoxOffsetX = 0; chatBoxOffsetY = -1;
        chatButtonBgOffsetX = 0; chatButtonBgOffsetY = -4;
        chatButtonOffsetX = 2; chatButtonOffsetY = 1;
        chatDialogOffsetX = 8; chatDialogOffsetY = 6;
        canvasOffsetX = 0; canvasOffsetY = 0;
        titleBarH = 26; windowExtraW = 8; windowExtraH = 5;
        viewportOffsetX = 0; viewportOffsetY = 0;
        version++;
        relayoutWindow();
        SwingUtilities.invokeLater(() -> {
            dispose();
            instance = new LiveTuner();
            instance.setVisible(true);
        });
    }
}
