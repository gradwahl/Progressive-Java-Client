package jagex2.client;

import com.gradwahl.rs254.gl.ClientTitleBar;
import com.gradwahl.rs254.gl.LiveTuner;
import deob.ObfuscatedName;
import sign.signlink;

import java.awt.*;
import java.awt.event.*;
import java.net.URI;

@ObfuscatedName("b")
public class ViewBox extends Frame {

	// Title bar height, canvas offset, and window padding defaults live in LiveTuner
	// so the F12 tuner can adjust them live.
	private static final int TITLE_BAR_BG = ClientTitleBar.COL_BG & 0xFFFFFF;

	// 5-pixel decorative border on sides and bottom:
	// pixel 0 = white, pixels 1-3 = TITLE_BAR_BG, pixel 4 = black background.
	private static final int BORDER_INSET = 5;
	private static final int SIDEBAR_W    = 10;

	@ObfuscatedName("b.a")
	public GameShell shell;

	private int dragOffsetX;
	private int dragOffsetY;

	private Panel gameArea;
	private RuneLiteTitleBar titleBar;
	private Canvas sidebarPanel;
	private volatile boolean sidebarOpen = false;
	private Rectangle savedBounds      = null;  // non-null while maximised
	private Rectangle preIconifyBounds = null;  // position saved before iconify
	private final int canvasW, canvasH;

	public ViewBox(boolean arg0, int arg1, GameShell arg2, int arg3) {
		this.shell  = arg2;
		this.canvasW = arg3;
		this.canvasH = arg1;
		String title = "Java-OpenGL Client - release #" + signlink.clientversion;
		this.setTitle(title);
		this.setResizable(true);
		this.setUndecorated(true);

		this.setLayout(new BorderLayout());

		this.titleBar = new RuneLiteTitleBar(title);
		this.add(this.titleBar, BorderLayout.NORTH);

		this.gameArea = new Panel(null) {
			@Override
			public void doLayout() {
				// Center the canvas horizontally; pin it to the top of the game area.
				int areaW = getWidth();
				int cx = Math.max(BORDER_INSET, (areaW - canvasW) / 2);
				shell.setBounds(cx, 0, canvasW, canvasH);
			}
			@Override
			public void update(Graphics g) {
				paint(g);
			}
			@Override
			public void paint(Graphics g) {
				// Skip super.paint() — it erases to background colour before drawing,
				// which is the main cause of white flicker on resize.
				drawBorder(g, getWidth(), getHeight());
			}
		};
		this.gameArea.setIgnoreRepaint(true);

		// Black background — the border and canvas sit on top.
		gameArea.setBackground(Color.BLACK);
		this.shell.setBackground(Color.BLACK);

		gameArea.setPreferredSize(new Dimension(
			canvasW + 2 * BORDER_INSET,
			canvasH + BORDER_INSET
		));
		gameArea.add(this.shell);
		this.add(gameArea, BorderLayout.CENTER);

		this.sidebarPanel = createSidebarPanel();
		this.add(this.sidebarPanel, BorderLayout.EAST);

		this.pack();
		this.setMinimumSize(new Dimension(
			canvasW + 2 * BORDER_INSET,
			LiveTuner.titleBarH + canvasH + BORDER_INSET
		));

		this.setLocationRelativeTo(null);
		this.setVisible(true);
		this.toFront();

		LiveTuner.windowRelayoutHook = this::relayout;
		installResizeHandler();

		// Repaint maximize icon on state changes; restore position after deiconify.
		addWindowStateListener(e -> {
			boolean wasIconified  = (e.getOldState() & Frame.ICONIFIED) != 0;
			boolean nowIconified  = (e.getNewState() & Frame.ICONIFIED) != 0;
			if (wasIconified && !nowIconified && preIconifyBounds != null) {
				setBounds(preIconifyBounds);
				preIconifyBounds = null;
				gameArea.doLayout();
			}
			titleBar.repaint();
		});
	}

	/** 4-pixel border: all TITLE_BAR_BG → 1px black background. */
	private void drawBorder(Graphics g, int w, int h) {
		g.setColor(new Color(TITLE_BAR_BG));
		g.fillRect(0, 0, 4, h);           // left
		g.fillRect(w - 4, 0, 4, h);       // right
		g.fillRect(0, h - 4, w, 4);       // bottom
	}

	private void relayout() {
		titleBar.setPreferredSize(new Dimension(1, LiveTuner.titleBarH));
		gameArea.setPreferredSize(new Dimension(
			canvasW + 2 * BORDER_INSET,
			canvasH + BORDER_INSET
		));
		gameArea.doLayout();
		setMinimumSize(new Dimension(
			canvasW + 2 * BORDER_INSET + (sidebarOpen ? SIDEBAR_W : 0),
			LiveTuner.titleBarH + canvasH + BORDER_INSET
		));
		pack();
	}

	boolean isSidebarOpen() { return sidebarOpen; }

	boolean isMaximized() { return savedBounds != null; }

	void toggleSidebar() {
		sidebarOpen = !sidebarOpen;
		sidebarPanel.setVisible(sidebarOpen);
		setMinimumSize(new Dimension(
			canvasW + 2 * BORDER_INSET + (sidebarOpen ? SIDEBAR_W : 0),
			LiveTuner.titleBarH + canvasH + BORDER_INSET
		));
		if (sidebarOpen) {
			setSize(getWidth() + SIDEBAR_W, getHeight());
		} else {
			setSize(Math.max(getWidth() - SIDEBAR_W, canvasW + 2 * BORDER_INSET), getHeight());
		}
		validate();
		gameArea.doLayout();
		titleBar.repaint();
	}

	void toggleMaximize() {
		if (isMaximized()) {
			setBounds(savedBounds);
			savedBounds = null;
		} else {
			savedBounds = getBounds();
			// getMaximumWindowBounds() returns the usable screen area (work area)
			// in AWT coordinates — equivalent to glfwGetMonitorWorkarea, and
			// reliable across DPI scales and multi-monitor layouts.
			Rectangle wa = GraphicsEnvironment.getLocalGraphicsEnvironment()
			                                  .getMaximumWindowBounds();
			setBounds(wa);
			setLocation(wa.x, wa.y);
		}
		gameArea.doLayout();
		titleBar.repaint();
	}

	void openDiscord() {
		new Thread(() -> {
			try {
				Desktop.getDesktop().browse(new URI("https://discord.gg/Uz3B8JJjEN"));
			} catch (Exception e) {
				System.err.println("[ViewBox] Failed to open Discord: " + e);
			}
		}, "discord-link").start();
	}

	private Canvas createSidebarPanel() {
		Canvas p = new Canvas() {
			@Override
			public void paint(Graphics g) {
				int w = getWidth(), h = getHeight();
				g.setColor(new Color(TITLE_BAR_BG));
				g.fillRect(0, 0, w, h);
				// Left-edge separator line
				g.setColor(new Color(ClientTitleBar.COL_BORDER & 0xFFFFFF));
				g.fillRect(0, 0, 1, h);
			}
		};
		p.setBackground(new Color(TITLE_BAR_BG));
		p.setPreferredSize(new Dimension(SIDEBAR_W, 1));
		p.setVisible(false);
		return p;
	}

	// ── Edge-drag resize ─────────────────────────────────────────────────────

	private static final int RESIZE_BORDER = 6;

	private int resizeEdge = 0; // bitmask: 1=left 2=right 4=top 8=bottom
	private int resizeStartX, resizeStartY, resizeStartWinX, resizeStartWinY, resizeStartW, resizeStartH;
	private AWTEventListener resizeListener;

	private int edgeAtScreen(int sx, int sy) {
		if (!isShowing()) {
			return 0;
		}
		Point loc = getLocationOnScreen();
		int wx = sx - loc.x, wy = sy - loc.y;
		int w = getWidth(), h = getHeight(), b = RESIZE_BORDER;
		int edge = 0;
		if (wx < b)      edge |= 1;
		if (wx >= w - b) edge |= 2;
		if (wy < b)      edge |= 4;
		if (wy >= h - b) edge |= 8;
		return edge;
	}

	private static Cursor cursorFor(int edge) {
		return switch (edge) {
			case 1       -> Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR);
			case 2       -> Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR);
			case 4       -> Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR);
			case 8       -> Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR);
			case 1 | 4   -> Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR);
			case 2 | 4   -> Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR);
			case 1 | 8   -> Cursor.getPredefinedCursor(Cursor.SW_RESIZE_CURSOR);
			case 2 | 8   -> Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR);
			default      -> Cursor.getDefaultCursor();
		};
	}

	private void installResizeHandler() {
		resizeListener = awtEvent -> {
			if (!(awtEvent instanceof MouseEvent e)) return;
			// Only handle events from components belonging to this window.
			Component src = (Component) e.getSource();
			Component c = src;
			while (c != null && c != ViewBox.this) c = c.getParent();
			if (c == null) return;

			switch (e.getID()) {
				case MouseEvent.MOUSE_MOVED, MouseEvent.MOUSE_ENTERED -> {
					src.setCursor(cursorFor(edgeAtScreen(e.getXOnScreen(), e.getYOnScreen())));
				}
				case MouseEvent.MOUSE_PRESSED -> {
					resizeEdge = edgeAtScreen(e.getXOnScreen(), e.getYOnScreen());
					if (resizeEdge != 0) {
						Point loc = getLocationOnScreen();
						resizeStartX    = e.getXOnScreen();
						resizeStartY    = e.getYOnScreen();
						resizeStartWinX = loc.x;
						resizeStartWinY = loc.y;
						resizeStartW    = getWidth();
						resizeStartH    = getHeight();
					}
				}
				case MouseEvent.MOUSE_DRAGGED -> {
					if (resizeEdge == 0) return;
					int dx = e.getXOnScreen() - resizeStartX;
					int dy = e.getYOnScreen() - resizeStartY;
					int nx = resizeStartWinX, ny = resizeStartWinY;
					int nw = resizeStartW,    nh = resizeStartH;
					Dimension min = getMinimumSize();
					if ((resizeEdge & 2) != 0) nw = Math.max(min.width,  resizeStartW + dx);
					if ((resizeEdge & 1) != 0) { nw = Math.max(min.width,  resizeStartW - dx); nx = resizeStartWinX + resizeStartW - nw; }
					if ((resizeEdge & 8) != 0) nh = Math.max(min.height, resizeStartH + dy);
					if ((resizeEdge & 4) != 0) { nh = Math.max(min.height, resizeStartH - dy); ny = resizeStartWinY + resizeStartH - nh; }
					setBounds(nx, ny, nw, nh);
					gameArea.doLayout();
				}
				case MouseEvent.MOUSE_RELEASED -> resizeEdge = 0;
				case MouseEvent.MOUSE_EXITED -> {
					if (src == ViewBox.this) src.setCursor(Cursor.getDefaultCursor());
				}
			}
		};

		Toolkit.getDefaultToolkit().addAWTEventListener(
			resizeListener,
			AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK
		);

		addWindowListener(new WindowAdapter() {
			@Override public void windowClosing(WindowEvent e) {
				Toolkit.getDefaultToolkit().removeAWTEventListener(resizeListener);
			}
		});
	}

	@Override public void update(Graphics arg0) { this.shell.update(arg0); }
	@Override public void paint(Graphics arg0)  { this.shell.paint(arg0);  }

	// ── Custom title bar ─────────────────────────────────────────────────────

	private final class RuneLiteTitleBar extends Panel implements MouseListener, MouseMotionListener {
		private final String title;
		private boolean closeHover, minimizeHover, maximizeHover, sidebarHover, discordHover;

		RuneLiteTitleBar(String title) {
			this.title = title;
			this.setPreferredSize(new Dimension(1, LiveTuner.titleBarH));
			this.addMouseListener(this);
			this.addMouseMotionListener(this);
		}

		public void paint(Graphics g) {
			ClientTitleBar.paint((Graphics2D) g, getWidth(), title,
				closeHover, minimizeHover, maximizeHover, sidebarHover, discordHover,
				ViewBox.this.isMaximized(), ViewBox.this.isSidebarOpen());
		}

		public void mousePressed(MouseEvent e) {
			if (e.getButton() != MouseEvent.BUTTON1) return;
			switch (ClientTitleBar.hitTest(e.getX(), getWidth())) {
				case ClientTitleBar.BTN_CLOSE:
					ViewBox.this.dispatchEvent(new WindowEvent(ViewBox.this, WindowEvent.WINDOW_CLOSING));
					break;
				case ClientTitleBar.BTN_MINIMIZE:
					ViewBox.this.preIconifyBounds = ViewBox.this.getBounds();
					ViewBox.this.setState(Frame.ICONIFIED);
					break;
				case ClientTitleBar.BTN_SIDEBAR:   ViewBox.this.toggleSidebar();  break;
				case ClientTitleBar.BTN_MAXIMIZE:  ViewBox.this.toggleMaximize(); break;
				case ClientTitleBar.BTN_DISCORD:   ViewBox.this.openDiscord();    break;
				default:
					dragOffsetX = e.getX();
					dragOffsetY = e.getY();
			}
		}

		public void mouseDragged(MouseEvent e) {
			if (ClientTitleBar.hitTest(e.getX(), getWidth()) != ClientTitleBar.BTN_NONE) return;
			Point p = e.getLocationOnScreen();
			ViewBox.this.setLocation(p.x - dragOffsetX, p.y - dragOffsetY);
		}

		public void mouseMoved(MouseEvent e) {
			int btn = ClientTitleBar.hitTest(e.getX(), getWidth());
			boolean nc = btn == ClientTitleBar.BTN_CLOSE,    nm = btn == ClientTitleBar.BTN_MINIMIZE,
			        ns = btn == ClientTitleBar.BTN_SIDEBAR,  nmax = btn == ClientTitleBar.BTN_MAXIMIZE,
			        nd = btn == ClientTitleBar.BTN_DISCORD;
			if (nc != closeHover || nm != minimizeHover || ns != sidebarHover
			        || nmax != maximizeHover || nd != discordHover) {
				closeHover = nc; minimizeHover = nm; sidebarHover = ns;
				maximizeHover = nmax; discordHover = nd;
				repaint();
			}
		}

		public void mouseExited(MouseEvent e) {
			if (closeHover || minimizeHover || sidebarHover || maximizeHover || discordHover) {
				closeHover = false; minimizeHover = false; sidebarHover = false;
				maximizeHover = false; discordHover = false;
				repaint();
			}
		}

		public void mouseClicked(MouseEvent e)  {}
		public void mouseReleased(MouseEvent e) {}
		public void mouseEntered(MouseEvent e)  {}
	}
}
