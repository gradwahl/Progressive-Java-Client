package jagex2.graphics;

import deob.ObfuscatedName;

import java.awt.*;
import java.awt.image.*;

@ObfuscatedName("rb")
public class PixMap implements ImageProducer, ImageObserver {

	@ObfuscatedName("rb.a")
	public int[] data;

	@ObfuscatedName("rb.b")
	public int width;

	@ObfuscatedName("rb.c")
	public int height;

	/** When non-null, every draw() blits pixels here so GLRenderer can overlay them. */
	public static int[] uiBuffer = null;
	public static int   uiWidth  = 0;
	public static int   uiHeight = 0;

	@ObfuscatedName("rb.d")
	public ColorModel model;

	@ObfuscatedName("rb.e")
	public ImageConsumer ic;

	@ObfuscatedName("rb.f")
	public Image img;

	public PixMap(Component arg1, int arg2, int arg3) {
		this.width = arg2;
		this.height = arg3;
		this.data = new int[arg2 * arg3];
		this.model = new DirectColorModel(32, 16711680, 65280, 255);
		this.img = arg1.createImage(this);
		this.setPixels();
		arg1.prepareImage(this.img, this);
		this.setPixels();
		arg1.prepareImage(this.img, this);
		this.setPixels();
		arg1.prepareImage(this.img, this);
		this.bind();
	}

	@ObfuscatedName("rb.a(B)V")
	public void bind() {
		Pix2D.setPixels(this.height, this.data, this.width);
	}

	@ObfuscatedName("rb.a(IILjava/awt/Graphics;Z)V")
	public void draw(int arg0, int arg1, Graphics arg2) {
		if (arg2 != null) {
			this.setPixels();
			arg2.drawImage(this.img, arg1, arg0, this);
		}
		// Mirror pixels into uiBuffer so GLRenderer can draw them as a 2D overlay.
		// Viewport PixMap uses an out-of-range sentinel so we can distinguish
		// "never drawn" (transparent) from valid RGB pixels such as black and item outlines.
		// All other PixMaps: force alpha=FF so every drawn pixel (including black) is opaque.
		if (uiBuffer != null) {
			boolean isViewport = (this.data == com.gradwahl.rs254.gl.GLRenderer.viewportPixels);
			int dstX = arg1, dstY = arg0;
			int srcSkip = 0;
			if (dstX < 0) { srcSkip = -dstX; dstX = 0; }
			for (int row = 0; row < this.height; row++) {
				int dy = dstY + row;
				if (dy < 0 || dy >= uiHeight) continue;
				int srcOff = row * this.width + srcSkip;
				int dstOff = dy * uiWidth + dstX;
				int cols   = Math.min(this.width - srcSkip, uiWidth - dstX);
				if (cols <= 0) continue;
				if (isViewport) {
					// sentinel → transparent (show 3D)  |  RGB pixel → opaque
					for (int c = 0; c < cols; c++) {
						int px = this.data[srcOff + c];
						uiBuffer[dstOff + c] =
								(px == com.gradwahl.rs254.gl.GLRenderer.UI_TRANSPARENT_SENTINEL)
										? 0
										: (px | 0xFF000000);
					}
				} else {
					// All pixels opaque — black fills (sidebars, menus) must show.
					for (int c = 0; c < cols; c++) {
						uiBuffer[dstOff + c] = this.data[srcOff + c] | 0xFF000000;
					}
				}
			}
		}
	}


	/**
	 * Like draw(), but pixels equal to UI_TRANSPARENT_SENTINEL are skipped when
	 * writing to uiBuffer so the content already drawn there (e.g. inventory)
	 * shows through. Used for the stone tab rows which have transparent gaps.
	 */
	public void drawTransparent(int arg0, int arg1, Graphics arg2) {
		if (arg2 != null) {
			this.setPixels();
			arg2.drawImage(this.img, arg1, arg0, this);
		}
		if (uiBuffer == null) {
			return;
		}
		final int SENTINEL = com.gradwahl.rs254.gl.GLRenderer.UI_TRANSPARENT_SENTINEL;
		int dstX = arg1, dstY = arg0;
		int srcSkip = 0;
		if (dstX < 0) { srcSkip = -dstX; dstX = 0; }
		for (int row = 0; row < this.height; row++) {
			int dy = dstY + row;
			if (dy < 0 || dy >= uiHeight) continue;
			int srcOff = row * this.width + srcSkip;
			int dstOff = dy * uiWidth + dstX;
			int cols   = Math.min(this.width - srcSkip, uiWidth - dstX);
			if (cols <= 0) continue;
			for (int c = 0; c < cols; c++) {
				int px = this.data[srcOff + c];
				if (px == SENTINEL) continue;
				uiBuffer[dstOff + c] = px | 0xFF000000;
			}
		}
	}

	/**
	 * Draws only a rectangular portion of this pixmap to the screen/UI overlay.
	 * Used by the fixed OSRS chatbox so its lower edge can sit above the
	 * bottom button strip without re-covering the whole Public/Private bar.
	 */
	public void drawPartial(int dstY, int dstX, Graphics graphics, int srcY, int srcX, int w, int h) {
		if (w <= 0 || h <= 0) {
			return;
		}
		if (srcX < 0) { dstX -= srcX; w += srcX; srcX = 0; }
		if (srcY < 0) { dstY -= srcY; h += srcY; srcY = 0; }
		if (srcX + w > this.width) {
			w = this.width - srcX;
		}
		if (srcY + h > this.height) {
			h = this.height - srcY;
		}
		if (w <= 0 || h <= 0) {
			return;
		}
		if (graphics != null) {
			this.setPixels();
			graphics.drawImage(this.img,
					dstX, dstY, dstX + w, dstY + h,
					srcX, srcY, srcX + w, srcY + h,
					this);
		}
		if (uiBuffer == null) {
			return;
		}
		boolean isViewport = (this.data == com.gradwahl.rs254.gl.GLRenderer.viewportPixels);
		int copySrcX = srcX;
		int copySrcY = srcY;
		int copyDstX = dstX;
		int copyDstY = dstY;
		int copyW = w;
		int copyH = h;
		if (copyDstX < 0) { copySrcX -= copyDstX; copyW += copyDstX; copyDstX = 0; }
		if (copyDstY < 0) { copySrcY -= copyDstY; copyH += copyDstY; copyDstY = 0; }
		if (copyDstX + copyW > uiWidth) {
			copyW = uiWidth - copyDstX;
		}
		if (copyDstY + copyH > uiHeight) {
			copyH = uiHeight - copyDstY;
		}
		if (copyW <= 0 || copyH <= 0) {
			return;
		}
		for (int row = 0; row < copyH; row++) {
			int srcOff = (copySrcY + row) * this.width + copySrcX;
			int dstOff = (copyDstY + row) * uiWidth + copyDstX;
			if (isViewport) {
				for (int col = 0; col < copyW; col++) {
					int px = this.data[srcOff + col];
					uiBuffer[dstOff + col] =
							(px == com.gradwahl.rs254.gl.GLRenderer.UI_TRANSPARENT_SENTINEL)
									? 0
									: (px | 0xFF000000);
				}
			} else {
				for (int col = 0; col < copyW; col++) {
					uiBuffer[dstOff + col] = this.data[srcOff + col] | 0xFF000000;
				}
			}
		}
	}

	public synchronized void addConsumer(ImageConsumer arg0) {
		this.ic = arg0;
		arg0.setDimensions(this.width, this.height);
		arg0.setProperties(null);
		arg0.setColorModel(this.model);
		arg0.setHints(14);
	}

	public synchronized boolean isConsumer(ImageConsumer arg0) {
		return this.ic == arg0;
	}

	public synchronized void removeConsumer(ImageConsumer arg0) {
		if (this.ic == arg0) {
			this.ic = null;
		}
	}

	public void startProduction(ImageConsumer arg0) {
		this.addConsumer(arg0);
	}

	public void requestTopDownLeftRightResend(ImageConsumer arg0) {
		System.out.println("TDLR");
	}

	@ObfuscatedName("rb.a()V")
	public synchronized void setPixels() {
		if (this.ic != null) {
			this.ic.setPixels(0, 0, this.width, this.height, this.model, this.data, 0, this.width);
			this.ic.imageComplete(2);
		}
	}

	public boolean imageUpdate(Image arg0, int arg1, int arg2, int arg3, int arg4, int arg5) {
		return true;
	}
}
