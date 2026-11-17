package dev.recur.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.recur.Checkpoint;
import dev.recur.LoopNetworking;
import dev.recur.Recur;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Checkpoint thumbnails. Creating a checkpoint hides the HUD (and closes any menu) for a few ticks, grabs the game
 * view, shrinks it to a tiny picture and sends it with the request. Thumbnails from the server become textures.
 */
public final class Thumbnails {
	private static final int W = Checkpoint.THUMB_W, H = Checkpoint.THUMB_H;
	private static final Map<Integer, Identifier> TEXTURES = new HashMap<>();

	private static int waitTicks = -1;
	private static boolean hudWasHidden;
	/** What to do with the picture once it's taken. */
	private static java.util.function.Consumer<byte[]> onTaken;

	private Thumbnails() {}

	/** Starts creating a checkpoint: the request is sent once the picture is taken. */
	public static void createCheckpoint() {
		capture(data -> ClientPlayNetworking.send(new LoopNetworking.CreatePayload(data)));
	}

	/** Takes the loop start's picture (asked by the server right after you started the loop). */
	public static void captureLoopStart() {
		capture(data -> { if (data.length > 0) ClientPlayNetworking.send(new LoopNetworking.StartPicturePayload(data)); });
	}

	private static void capture(java.util.function.Consumer<byte[]> then) {
		if (waitTicks >= 0) return;
		onTaken = then;
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.screen() != null) mc.setScreenAndShow(null);
		hudWasHidden = mc.gui.hud.isHidden();
		if (!hudWasHidden) mc.gui.hud.toggle();
		waitTicks = 3; // let a couple of frames render without the HUD
	}

	public static void tick() {
		if (waitTicks < 0 || --waitTicks > 0) return;
		waitTicks = -1;
		Minecraft mc = Minecraft.getInstance();
		try {
			Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
				byte[] data;
				try { data = shrink(image); } finally { image.close(); }
				restoreHud();
				onTaken.accept(data);
			});
		} catch (Exception e) {
			Recur.LOGGER.warn("Could not take a checkpoint thumbnail", e);
			restoreHud();
			onTaken.accept(new byte[0]);
		}
	}

	private static void restoreHud() {
		Minecraft mc = Minecraft.getInstance();
		if (!hudWasHidden && mc.gui.hud.isHidden()) mc.gui.hud.toggle();
	}

	/** Crops the centre to 16:9 and averages it down to W x H, as RGB bytes. */
	private static byte[] shrink(NativeImage img) {
		int iw = img.getWidth(), ih = img.getHeight();
		int cw = iw, ch = iw * H / W;
		if (ch > ih) { ch = ih; cw = ih * W / H; }
		int ox = (iw - cw) / 2, oy = (ih - ch) / 2;
		byte[] out = new byte[W * H * 3];
		for (int ty = 0; ty < H; ty++) for (int tx = 0; tx < W; tx++) {
			int x0 = ox + tx * cw / W, x1 = Math.max(x0 + 1, ox + (tx + 1) * cw / W);
			int y0 = oy + ty * ch / H, y1 = Math.max(y0 + 1, oy + (ty + 1) * ch / H);
			long r = 0, g = 0, b = 0, n = 0;
			// Sampling a sparse grid inside the cell is enough for a picture this small, and much faster than reading every pixel.
			int sx = Math.max(1, (x1 - x0) / 4), sy = Math.max(1, (y1 - y0) / 4);
			for (int y = y0; y < y1; y += sy) for (int x = x0; x < x1; x += sx) {
				int argb = img.getPixel(x, y);
				r += (argb >> 16) & 0xFF; g += (argb >> 8) & 0xFF; b += argb & 0xFF; n++;
			}
			int i = (ty * W + tx) * 3;
			out[i] = (byte) (r / n); out[i + 1] = (byte) (g / n); out[i + 2] = (byte) (b / n);
		}
		return out;
	}

	public static void onThumbnail(int id, byte[] data) {
		if (data.length != W * H * 3) return;
		NativeImage img = new NativeImage(W, H, false);
		for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
			int i = (y * W + x) * 3;
			img.setPixel(x, y, 0xFF000000 | (data[i] & 0xFF) << 16 | (data[i + 1] & 0xFF) << 8 | (data[i + 2] & 0xFF));
		}
		Identifier key = Recur.id("thumb/" + id);
		Minecraft.getInstance().getTextureManager().register(key, new DynamicTexture(() -> "Recur checkpoint " + id, img));
		TEXTURES.put(id, key);
	}

	public static Identifier get(int id) { return TEXTURES.get(id); }

	public static void clear() {
		var tm = Minecraft.getInstance().getTextureManager();
		TEXTURES.values().forEach(tm::release);
		TEXTURES.clear();
	}

	public static int width() { return W; }
	public static int height() { return H; }
}
