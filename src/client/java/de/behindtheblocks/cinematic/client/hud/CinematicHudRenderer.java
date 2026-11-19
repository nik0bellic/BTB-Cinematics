package de.behindtheblocks.cinematic.client.hud;

import com.google.gson.JsonObject;
import de.behindtheblocks.cinematic.BtbCinematic;
import de.behindtheblocks.cinematic.camera.Easing;
import de.behindtheblocks.cinematic.client.ClientSceneState;
import de.behindtheblocks.cinematic.client.network.ClientCueDispatcher;
import de.behindtheblocks.cinematic.client.timeline.ClientTimeline;
import de.behindtheblocks.cinematic.hud.HudTextSpec;
import de.behindtheblocks.cinematic.hud.HudTextSpec.AnimIn;
import de.behindtheblocks.cinematic.hud.HudTextSpec.AnimOut;
import de.behindtheblocks.cinematic.util.Colors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders cinematic HUD texts (title cards, subtitles, captions) with per-character animation.
 *
 * <p>Fonts are regular Minecraft fonts from resource packs: {@code cinematic} and
 * {@code cinematic_bold} map to the bundled Cinzel TTF ({@code assets/btb_cinematic/font}), any
 * other {@code namespace:id} is used as-is — so the film team can drop in their own TTF font with a
 * resource pack and reference it from JSON.
 */
public final class CinematicHudRenderer {
	private static final class Element {
		final String sceneId;
		final HudTextSpec spec;
		final ClientTimeline.Stamp start;
		final Style style;
		final int totalChars;
		final int effectiveIn;
		ClientTimeline.Stamp hideStart;
		int hideOut;
		/** Per-line, per-character components and widths, built once on first draw (no per-frame allocation). */
		Component[][] glyphs;
		float[][] glyphWidths;

		void ensureGlyphs(Font font) {
			if (glyphs != null) {
				return;
			}
			int n = spec.lines().size();
			glyphs = new Component[n][];
			glyphWidths = new float[n][];
			for (int li = 0; li < n; li++) {
				String line = spec.lines().get(li);
				glyphs[li] = new Component[line.length()];
				glyphWidths[li] = new float[line.length()];
				for (int ci = 0; ci < line.length(); ci++) {
					glyphs[li][ci] = Component.literal(String.valueOf(line.charAt(ci))).withStyle(style);
					glyphWidths[li][ci] = font.width(glyphs[li][ci]);
				}
			}
		}

		Element(String sceneId, HudTextSpec spec) {
			this.sceneId = sceneId;
			this.spec = spec;
			this.start = ClientTimeline.Stamp.now(sceneId);
			this.style = Style.EMPTY.withFont(new FontDescription.Resource(fontId(spec.font())));
			this.totalChars = spec.lines().stream().mapToInt(String::length).sum();
			int typeIn = (int) Math.ceil(totalChars * spec.charTicks());
			this.effectiveIn = switch (spec.animIn()) {
				// The book first opens its pages (in), then the ink appears.
				case TYPEWRITER -> spec.style() == HudTextSpec.Kind.BOOK ? spec.in() + typeIn : Math.max(spec.in(), typeIn);
				case LETTERS -> Math.max(spec.in(), typeIn + spec.in() / 2);
				default -> spec.in();
			};
		}
	}

	private static final Map<String, Element> ELEMENTS = new LinkedHashMap<>();

	private CinematicHudRenderer() {
	}

	public static void init() {
		ClientCueDispatcher.register("hud_show", (scene, tick, data) -> {
			JsonObject specJson = data.getAsJsonObject("spec");
			HudTextSpec spec = HudTextSpec.fromJson(specJson.has("id") ? specJson.get("id").getAsString() : "text", specJson);
			ELEMENTS.remove(spec.id());
			ELEMENTS.put(spec.id(), new Element(scene, spec));
		});
		ClientCueDispatcher.register("hud_hide", (scene, tick, data) -> {
			int out = data.has("out") ? data.get("out").getAsInt() : 20;
			String id = data.has("id") ? data.get("id").getAsString() : null;
			for (Element e : ELEMENTS.values()) {
				if ((id == null || e.spec.id().equals(id)) && e.hideStart == null) {
					e.hideStart = ClientTimeline.Stamp.now(e.sceneId);
					e.hideOut = Math.max(0, out);
				}
			}
		});
		// Stopped by hand: the scene's texts fade out instead of staying on screen (a scene that finishes
		// normally keeps its last card, e.g. an end card with hold -1).
		ClientSceneState.onSceneStopped(sceneId -> {
			for (Element e : ELEMENTS.values()) {
				if (e.sceneId.equals(sceneId) && e.hideStart == null) {
					e.hideStart = ClientTimeline.Stamp.now(e.sceneId);
					e.hideOut = 15;
				}
			}
		});
		ClientSceneState.onSceneEnded((sceneId, reset) -> {
			if (reset) {
				ELEMENTS.values().removeIf(e -> e.sceneId.equals(sceneId));
			}
		});
	}

	static Identifier fontId(String font) {
		return switch (font) {
			case "cinematic" -> Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, "cinematic");
			case "cinematic_bold" -> Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, "cinematic_bold");
			case "default" -> Identifier.withDefaultNamespace("default");
			case "uniform" -> Identifier.withDefaultNamespace("uniform");
			default -> {
				Identifier id = Identifier.tryParse(font);
				yield id != null ? id : Identifier.withDefaultNamespace("default");
			}
		};
	}

	public static void tick() {
		Iterator<Element> it = ELEMENTS.values().iterator();
		while (it.hasNext()) {
			Element e = it.next();
			if (e.hideStart != null && e.hideStart.elapsed(0f) > e.hideOut) {
				it.remove();
			} else if (e.spec.hold() >= 0 && e.start.elapsed(0f) > e.spec.delay() + e.effectiveIn + e.spec.hold() + e.spec.out()) {
				it.remove();
			}
		}
	}

	public static void clearAll() {
		ELEMENTS.clear();
	}

	/** @param aboveEffects which group to draw (texts below or above fades/transitions) */
	public static void render(GuiGraphicsExtractor g, float partial, boolean aboveEffects) {
		if (ELEMENTS.isEmpty()) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		for (Element e : ELEMENTS.values()) {
			if (e.spec.aboveEffects() == aboveEffects) {
				draw(g, font, e, partial);
			}
		}
	}

	// ------------------------------------------------------------------ animation state

	/** Per-frame animation values for the whole element. */
	private record Frame(float alpha, float dy, float scale, float extraSpacing, float glitch, float blur,
			float revealChars, boolean perLetter, float time) {
	}

	private static Frame frame(Element e, float partial) {
		HudTextSpec s = e.spec;
		float t = (float) e.start.elapsed(partial) - s.delay();
		if (t < 0) {
			return null;
		}
		float alpha = 1, dy = 0, scale = 1, spacing = 0, glitch = 0, blur = 0;
		float reveal = Float.MAX_VALUE;
		boolean perLetter = false;

		// ---- in
		if (t < e.effectiveIn && e.effectiveIn > 0) {
			float p = t / e.effectiveIn;
			float ep = (float) Easing.EASE_OUT.apply(p);
			switch (s.animIn()) {
				case NONE -> { }
				case FADE -> alpha = (float) Easing.EASE_IN_OUT.apply(p);
				case TYPEWRITER -> reveal = t / s.charTicks();
				case LETTERS -> {
					reveal = t / s.charTicks();
					perLetter = true;
				}
				case SLIDE_UP -> {
					alpha = ep;
					dy = (1 - ep) * 18;
				}
				case SLIDE_DOWN -> {
					alpha = ep;
					dy = -(1 - ep) * 18;
				}
				case ZOOM -> {
					alpha = ep;
					scale = 1.35f - 0.35f * ep;
				}
				case TRACKING -> {
					alpha = (float) Easing.EASE_IN_OUT.apply(p);
					spacing = (1 - ep) * 6;
				}
				case GLITCH -> {
					alpha = Math.min(1, p * 3);
					glitch = 1 - p;
				}
				case BLUR -> {
					alpha = ep;
					blur = 1 - ep;
				}
			}
		}
		float afterIn = t - e.effectiveIn;
		// Trailer look: tracking titles keep breathing apart very slowly while visible.
		if (s.animIn() == AnimIn.TRACKING && afterIn > 0) {
			spacing += Math.min(afterIn, 400) * 0.006f;
		}

		// ---- out (explicit hide or end of hold)
		float outP = -1;
		if (e.hideStart != null) {
			outP = e.hideOut <= 0 ? 1 : (float) Math.min(1, e.hideStart.elapsed(partial) / e.hideOut);
		} else if (s.hold() >= 0 && afterIn > s.hold()) {
			outP = s.out() <= 0 ? 1 : Math.min(1, (afterIn - s.hold()) / s.out());
		}
		if (outP >= 0) {
			float ep = (float) Easing.EASE_IN.apply(outP);
			float fadeOut = 1 - (float) Easing.EASE_IN_OUT.apply(outP);
			switch (s.animOut()) {
				case NONE -> alpha = outP >= 1 ? 0 : alpha;
				case FADE -> alpha *= fadeOut;
				case SLIDE_UP -> {
					alpha *= fadeOut;
					dy -= ep * 18;
				}
				case SLIDE_DOWN -> {
					alpha *= fadeOut;
					dy += ep * 18;
				}
				case ZOOM -> {
					alpha *= fadeOut;
					scale *= 1 + 0.3f * ep;
				}
				case TRACKING -> {
					alpha *= fadeOut;
					spacing += ep * 8;
				}
				case GLITCH -> {
					alpha *= fadeOut;
					glitch = Math.max(glitch, ep);
				}
			}
		}
		return new Frame(alpha, dy, scale, spacing, glitch, blur, reveal, perLetter, t);
	}

	// ------------------------------------------------------------------ drawing

	private static void draw(GuiGraphicsExtractor g, Font font, Element e, float partial) {
		Frame f = frame(e, partial);
		if (f == null || f.alpha < 0.02f) {
			return;
		}
		HudTextSpec s = e.spec;
		if (s.style() != HudTextSpec.Kind.TEXT) {
			FilmOverlays.draw(g, font, e.spec, e.style, f.alpha, inProgress(e, partial), f.time, f.revealChars);
			return;
		}
		float scale = s.scale() * f.scale;
		float letterSpacing = s.letterSpacing() + f.extraSpacing;
		int lineHeight = font.lineHeight + (s.font().startsWith("cinematic") ? 2 : 0);

		// Measure lines (unscaled units).
		e.ensureGlyphs(font);
		int n = s.lines().size();
		float[] widths = new float[n];
		float blockW = 0;
		for (int i = 0; i < n; i++) {
			float w = 0;
			for (float cw : e.glyphWidths[i]) {
				w += cw;
			}
			widths[i] = w + (e.glyphWidths[i].length > 1 ? letterSpacing * (e.glyphWidths[i].length - 1) : 0);
			blockW = Math.max(blockW, widths[i]);
		}
		float blockH = n * lineHeight + (n - 1) * s.lineSpacing();

		// Anchor.
		int sw = g.guiWidth(), sh = g.guiHeight();
		float margin = 12;
		// Auto-fit: never let a title run off-screen, whatever the GUI scale or window size.
		float maxW = sw - 2 * margin;
		float maxH = sh - 2 * margin;
		if (blockW * scale > maxW) {
			scale = maxW / blockW;
		}
		if (blockH * scale > maxH) {
			scale = maxH / blockH;
		}
		float ax = switch (s.anchor()) {
			case LEFT, TOP_LEFT, BOTTOM_LEFT -> margin;
			case RIGHT, TOP_RIGHT, BOTTOM_RIGHT -> sw - margin - blockW * scale;
			default -> sw / 2f - blockW * scale / 2f;
		};
		float ay = switch (s.anchor()) {
			case TOP, TOP_LEFT, TOP_RIGHT -> margin;
			case BOTTOM, BOTTOM_LEFT, BOTTOM_RIGHT -> sh - margin - blockH * scale;
			default -> sh / 2f - blockH * scale / 2f;
		};
		ax += s.offsetX();
		ay += s.offsetY() + f.dy;

		g.pose().pushMatrix();
		g.pose().translate(ax, ay);
		g.pose().scale(scale, scale);

		if ((s.background() >>> 24) > 0) {
			int pad = Math.round(s.padding());
			g.fill(-pad, -pad, Math.round(blockW) + pad, Math.round(blockH) + pad,
					Colors.withAlpha(s.background(), f.alpha));
		}

		int charIndex = 0;
		for (int li = 0; li < n; li++) {
			String line = s.lines().get(li);
			float lx = switch (s.align()) {
				case LEFT -> 0;
				case RIGHT -> blockW - widths[li];
				case CENTER -> (blockW - widths[li]) / 2f;
			};
			float ly = li * (lineHeight + s.lineSpacing());
			for (int ci = 0; ci < line.length(); ci++, charIndex++) {
				Component glyph = e.glyphs[li][ci];
				float cw = e.glyphWidths[li][ci];
				float charAlpha = f.alpha;
				float cy = 0, cx = 0;

				if (f.revealChars != Float.MAX_VALUE) {
					float local = f.revealChars - charIndex;
					if (local <= 0) {
						break; // later chars of this line are not revealed yet
					}
					if (f.perLetter) {
						float p = Math.min(1, local * s.charTicks() / Math.max(1f, s.in() * 0.5f));
						charAlpha *= (float) Easing.EASE_OUT.apply(p);
						cy += (1 - p) * 6;
					} else {
						charAlpha *= Math.min(1, local);
					}
				}

				int color = s.color();
				float fringe = 0;
				switch (s.effect()) {
					case WAVE -> cy += (float) Math.sin(f.time * 0.18 + charIndex * 0.45) * 1.6f;
					case PULSE -> charAlpha *= 0.75f + 0.25f * (float) Math.sin(f.time * 0.15);
					case SHAKE -> {
						cx += (hash(charIndex, (int) f.time) - 0.5f) * 1.2f;
						cy += (hash(charIndex + 97, (int) f.time) - 0.5f) * 1.2f;
					}
					case GLITCH -> {
						if (hash(charIndex, (int) (f.time / 3)) > 0.93f) {
							cx += (hash(charIndex + 13, (int) f.time) - 0.5f) * 6;
							color = 0xFF55FFFF;
						}
					}
					case RAINBOW -> color = 0xFF000000 | hsv((f.time * 0.01f + charIndex * 0.04f) % 1f);
					case ETHEREAL -> {
						cy += (float) Math.sin(f.time * 0.09 + charIndex * 0.35) * 1.8f;
						cx += (float) Math.sin(f.time * 0.05 + charIndex * 0.9) * 0.6f;
						charAlpha *= 0.65f + 0.35f * (float) Math.sin(f.time * 0.06 + charIndex * 0.2);
						fringe = 0.8f + (float) Math.sin(f.time * 0.07) * 0.6f;
					}
					case FLICKER -> {
						if (hash(7, (int) (f.time / 2)) > 0.86f) {
							charAlpha *= 0.35f;
						}
					}
					case NONE -> { }
				}
				if (f.glitch > 0 && hash(charIndex, (int) f.time) < f.glitch * 0.6f) {
					cx += (hash(charIndex + 31, (int) f.time) - 0.5f) * 10 * f.glitch;
					glyph = Component.literal(String.valueOf(GLITCH_CHARS.charAt(
							(int) (hash(charIndex + 7, (int) f.time) * GLITCH_CHARS.length())))).withStyle(e.style);
					color = hash(charIndex + 3, (int) f.time) > 0.5f ? 0xFFFF3355 : 0xFF33E6FF;
				}

				if (fringe > 0 && charAlpha > 0.05f) {
					// chromatic fringe: faint red and cyan copies drifting apart
					drawChar(g, font, glyph, lx + cx - fringe, ly + cy, 0xFFFF4060, 0, false, charAlpha * 0.35f, 0);
					drawChar(g, font, glyph, lx + cx + fringe, ly + cy, 0xFF40E0FF, 0, false, charAlpha * 0.35f, 0);
				}
				if (charAlpha > 0.02f) {
					drawChar(g, font, glyph, lx + cx, ly + cy, color, s.glowColor(), s.shadow(), charAlpha, f.blur);
				}
				lx += cw + letterSpacing;
			}
		}
		g.pose().popMatrix();
	}

	/** 0..1 progress of the entrance (1 while holding), falling back to 0 during the exit. */
	private static float inProgress(Element e, float partial) {
		float t = (float) e.start.elapsed(partial) - e.spec.delay();
		float in = e.effectiveIn <= 0 ? 1 : Math.min(1, t / e.effectiveIn);
		if (e.hideStart != null) {
			float out = e.hideOut <= 0 ? 1 : (float) Math.min(1, e.hideStart.elapsed(partial) / e.hideOut);
			return in * (1 - out);
		}
		float afterIn = t - e.effectiveIn;
		if (e.spec.hold() >= 0 && afterIn > e.spec.hold()) {
			float out = e.spec.out() <= 0 ? 1 : Math.min(1, (afterIn - e.spec.hold()) / e.spec.out());
			return in * (1 - out);
		}
		return in;
	}

	private static final String GLITCH_CHARS = "#$%&*+=?@ABCDEFGHJKLMNPRSTUVWXYZ0123456789";

	private static void drawChar(GuiGraphicsExtractor g, Font font, Component c, float x, float y, int color,
			int glow, boolean shadow, float alpha, float blur) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		if (glow != 0) {
			int gc = Colors.withAlpha(glow, alpha * 0.22f);
			if ((gc >>> 24) >= 4) {
				for (int i = 0; i < 8; i++) {
					double a = i * Math.PI / 4;
					g.pose().pushMatrix();
					g.pose().translate((float) Math.cos(a) * 0.9f, (float) Math.sin(a) * 0.9f);
					g.text(font, c, 0, 0, gc, false);
					g.pose().popMatrix();
				}
			}
		}
		if (blur > 0.02f) {
			// Approximated blur-in: offset ghost copies that converge onto the glyph.
			int bc = Colors.withAlpha(color, alpha * 0.3f);
			if ((bc >>> 24) >= 4) {
				for (int i = -2; i <= 2; i++) {
					if (i == 0) {
						continue;
					}
					g.pose().pushMatrix();
					g.pose().translate(i * blur * 2.2f, 0);
					g.text(font, c, 0, 0, bc, false);
					g.pose().popMatrix();
				}
			}
		}
		int main = Colors.withAlpha(color, alpha * (blur > 0 ? 1 - blur * 0.6f : 1));
		if ((main >>> 24) >= 4) {
			g.text(font, c, 0, 0, main, shadow);
		}
		g.pose().popMatrix();
	}

	/** Deterministic pseudo random 0..1 so glitches look the same on every client and replay. */
	private static float hash(int a, int b) {
		int h = a * 374761393 + b * 668265263;
		h = (h ^ (h >>> 13)) * 1274126177;
		return ((h ^ (h >>> 16)) & 0xFFFFFF) / (float) 0x1000000;
	}

	private static int hsv(float h) {
		float r = Math.abs(h * 6 - 3) - 1, gg = 2 - Math.abs(h * 6 - 2), b = 2 - Math.abs(h * 6 - 4);
		int ri = (int) (Math.max(0, Math.min(1, r)) * 255), gi = (int) (Math.max(0, Math.min(1, gg)) * 255),
				bi = (int) (Math.max(0, Math.min(1, b)) * 255);
		return (ri << 16) | (gi << 8) | bi;
	}
}
