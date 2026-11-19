package de.behindtheblocks.cinematic.client.effects;

import com.mojang.blaze3d.platform.NativeImage;
import de.behindtheblocks.cinematic.BtbCinematic;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Colour grading with 3D LUTs: built-in looks, or any {@code .cube} file (DaVinci Resolve, Premiere, …)
 * from {@code config/btb_cinematic/luts/}. The active LUT is resampled to 33³ and uploaded into one
 * texture that the {@code lut} post shader reads.
 */
public final class LutManager {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/lut");
	private static final int N = 33;
	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, "textures/effect/lut_active.png");
	public static final List<String> BUILT_IN = List.of("nether", "film", "kalt", "bleach", "nacht", "glut");
	private static DynamicTexture texture;
	private static String loaded = "";

	private LutManager() {
	}

	public static Path folder() {
		return FabricLoader.getInstance().getConfigDir().resolve(BtbCinematic.MOD_ID).resolve("luts");
	}

	/** Must run before the lut post chain is first used (client start). */
	public static void init() {
		try {
			Files.createDirectories(folder());
		} catch (IOException ignored) {
			// not critical
		}
		texture = new DynamicTexture(() -> "btb lut", N * N, N, false);
		fill((r, g, b) -> new float[] {r, g, b});
		Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
	}

	private interface Grade {
		float[] apply(float r, float g, float b);
	}

	/** Loads a look by name; returns false (and keeps the old one) if it does not exist. */
	public static boolean load(String name) {
		if (texture == null || name.equals(loaded)) {
			return texture != null;
		}
		Grade g = builtIn(name);
		if (g == null) {
			Path f = folder().resolve(name.endsWith(".cube") ? name : name + ".cube");
			if (!Files.exists(f)) {
				LOG.warn("LUT '{}' not found (built-in: {}, files in {})", name, BUILT_IN, folder());
				return false;
			}
			try {
				g = parseCube(Files.readAllLines(f));
			} catch (RuntimeException | IOException e) {
				LOG.warn("Cannot read LUT {}: {}", f, e.getMessage());
				return false;
			}
		}
		fill(g);
		loaded = name;
		return true;
	}

	private static void fill(Grade g) {
		NativeImage img = texture.getPixels();
		for (int b = 0; b < N; b++) {
			for (int gr = 0; gr < N; gr++) {
				for (int r = 0; r < N; r++) {
					float[] c = g.apply(r / (N - 1f), gr / (N - 1f), b / (N - 1f));
					int R = clamp(c[0]), G = clamp(c[1]), B = clamp(c[2]);
					img.setPixel(b * N + r, gr, 0xFF000000 | R << 16 | G << 8 | B);
				}
			}
		}
		texture.upload();
	}

	private static int clamp(float v) {
		return Math.max(0, Math.min(255, Math.round(v * 255)));
	}

	// ------------------------------------------------------------------ built-in looks

	private static float lum(float r, float g, float b) {
		return 0.2126f * r + 0.7152f * g + 0.0722f * b;
	}

	private static float s(float x, float k) { // S-curve contrast around 0.5
		float y = x - 0.5f;
		return 0.5f + y * (1 + k) / (1 + k * 4 * Math.abs(y) * Math.abs(y) * 2);
	}

	private static Grade builtIn(String name) {
		return switch (name) {
			// teal shadows, warm (lava) highlights, gentle contrast – the classic blockbuster look
			case "nether" -> (r, g, b) -> {
				float l = lum(r, g, b), sh = 1 - Math.min(1, l * 2), hi = Math.max(0, l * 2 - 1);
				return new float[] {s(r + 0.06f * hi - 0.03f * sh, 0.25f), s(g + 0.02f * hi + 0.01f * sh, 0.25f), s(b - 0.05f * hi + 0.05f * sh, 0.25f)};
			};
			// film stock: lifted blacks, soft highlights, a bit warm and a bit less saturated
			case "film" -> (r, g, b) -> {
				float l = lum(r, g, b);
				float[] c = {r * 1.03f, g, b * 0.94f};
				for (int i = 0; i < 3; i++) {
					c[i] = 0.035f + 0.93f * s(l + (c[i] - l) * 0.85f, 0.3f);
				}
				return c;
			};
			case "kalt" -> (r, g, b) -> {
				float l = lum(r, g, b);
				return new float[] {s(l + (r - l) * 0.65f - 0.03f, 0.2f), s(l + (g - l) * 0.65f, 0.2f), s(l + (b - l) * 0.65f + 0.06f, 0.2f)};
			};
			// bleach bypass: silver, hard contrast, little colour
			case "bleach" -> (r, g, b) -> {
				float l = s(lum(r, g, b), 0.6f);
				return new float[] {l + (r - l) * 0.45f, l + (g - l) * 0.45f, l + (b - l) * 0.45f};
			};
			// day-for-night: dark, blue, low saturation
			case "nacht" -> (r, g, b) -> {
				float l = lum(r, g, b);
				return new float[] {(l + (r - l) * 0.35f) * 0.45f, (l + (g - l) * 0.35f) * 0.52f, (l + (b - l) * 0.35f) * 0.75f + 0.02f};
			};
			// ember: everything glows from within, deep red shadows
			case "glut" -> (r, g, b) -> {
				float l = lum(r, g, b);
				return new float[] {s(l * 1.15f + (r - l) * 0.9f + 0.02f, 0.3f), s(l * 0.82f + (g - l) * 0.8f, 0.3f), s(l * 0.6f + (b - l) * 0.6f, 0.3f)};
			};
			default -> null;
		};
	}

	// ------------------------------------------------------------------ .cube files

	private static Grade parseCube(List<String> lines) {
		int size = 0;
		float[] min = {0, 0, 0}, max = {1, 1, 1};
		List<float[]> data = new ArrayList<>();
		for (String raw : lines) {
			String l = raw.trim();
			if (l.isEmpty() || l.startsWith("#") || l.startsWith("TITLE")) {
				continue;
			}
			String[] p = l.split("\\s+");
			switch (p[0]) {
				case "LUT_3D_SIZE" -> size = Integer.parseInt(p[1]);
				case "DOMAIN_MIN" -> min = new float[] {Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])};
				case "DOMAIN_MAX" -> max = new float[] {Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])};
				case "LUT_1D_SIZE" -> throw new IllegalArgumentException("1D LUTs are not supported, please export a 3D LUT");
				default -> {
					if (Character.isDigit(p[0].charAt(0)) || p[0].charAt(0) == '-' || p[0].charAt(0) == '.') {
						data.add(new float[] {Float.parseFloat(p[0]), Float.parseFloat(p[1]), Float.parseFloat(p[2])});
					}
				}
			}
		}
		if (size < 2 || data.size() < size * size * size) {
			throw new IllegalArgumentException("not a 3D LUT (size " + size + ", " + data.size() + " entries)");
		}
		final int n = size;
		final float[] lo = min, hi = max;
		return (r, g, b) -> {
			float x = (r - lo[0]) / (hi[0] - lo[0]) * (n - 1), y = (g - lo[1]) / (hi[1] - lo[1]) * (n - 1), z = (b - lo[2]) / (hi[2] - lo[2]) * (n - 1);
			x = Math.max(0, Math.min(n - 1, x));
			y = Math.max(0, Math.min(n - 1, y));
			z = Math.max(0, Math.min(n - 1, z));
			int x0 = (int) x, y0 = (int) y, z0 = (int) z;
			int x1 = Math.min(n - 1, x0 + 1), y1 = Math.min(n - 1, y0 + 1), z1 = Math.min(n - 1, z0 + 1);
			float fx = x - x0, fy = y - y0, fz = z - z0;
			float[] out = new float[3];
			for (int c = 0; c < 3; c++) {
				float c00 = lerp(data.get(idx(x0, y0, z0, n))[c], data.get(idx(x1, y0, z0, n))[c], fx);
				float c10 = lerp(data.get(idx(x0, y1, z0, n))[c], data.get(idx(x1, y1, z0, n))[c], fx);
				float c01 = lerp(data.get(idx(x0, y0, z1, n))[c], data.get(idx(x1, y0, z1, n))[c], fx);
				float c11 = lerp(data.get(idx(x0, y1, z1, n))[c], data.get(idx(x1, y1, z1, n))[c], fx);
				out[c] = lerp(lerp(c00, c10, fy), lerp(c01, c11, fy), fz);
			}
			return out;
		};
	}

	private static int idx(int r, int g, int b, int n) {
		return r + g * n + b * n * n; // .cube order: red changes fastest
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}
}
