package de.behindtheblocks.cinematic.client.hud;

import de.behindtheblocks.cinematic.BtbCinematic;
import de.behindtheblocks.cinematic.camera.Easing;
import de.behindtheblocks.cinematic.hud.HudTextSpec;
import de.behindtheblocks.cinematic.util.Colors;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * Ready-made film overlays drawn by the cinematic HUD: lower third, dialogue box, objective
 * pop-up and camcorder frame. All of them are plain 2D fills and text — no textures, no shaders —
 * so they cost next to nothing per frame.
 */
final class FilmOverlays {
	private static final Style BOLD = Style.EMPTY.withFont(
			new FontDescription.Resource(Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, "cinematic_bold")));

	private FilmOverlays() {
	}

	static void draw(GuiGraphicsExtractor g, Font font, HudTextSpec s, Style style, float alpha, float progress, float time,
			float revealChars) {
		switch (s.style()) {
			case LOWER_THIRD -> lowerThird(g, font, s, style, alpha, progress);
			case DIALOGUE -> dialogue(g, font, s, style, alpha, progress, revealChars, time);
			case OBJECTIVE -> objective(g, font, s, style, alpha, progress);
			case CAMCORDER -> camcorder(g, font, s, alpha, time);
			case CREDITS -> credits(g, font, s, alpha, time);
			case ROLL -> roll(g, font, s, alpha, time);
			case END_CARD -> endCard(g, font, s, alpha, progress, time);
			case BOOK -> book(g, font, s, style, alpha, time, revealChars);
			case BOSS_INTRO -> bossIntro(g, font, s, alpha, progress, time);
			default -> { }
		}
	}

	private static int a(int argb, float alpha) {
		return Colors.withAlpha(argb, alpha);
	}

	/** Name and role, bottom left: accent bar grows, then the plate wipes open. */
	private static void lowerThird(GuiGraphicsExtractor g, Font font, HudTextSpec s, Style style, float alpha, float p) {
		String name = s.lines().get(0);
		String role = s.lines().size() > 1 ? s.lines().get(1) : "";
		float scale = s.scale();
		int nameW = (int) (font.width(Component.literal(name).withStyle(BOLD)) * scale);
		int roleW = font.width(role);
		int plateW = Math.max(nameW, roleW) + 22;
		int plateH = (int) (font.lineHeight * scale) + (role.isEmpty() ? 10 : 22);
		int x = 24 + (int) s.offsetX(), y = g.guiHeight() - 74 - plateH + (int) s.offsetY(); // above the subtitle line
		float bar = (float) Easing.EASE_OUT.apply(Math.min(1, p / 0.35f));
		float wipe = (float) Easing.EASE_IN_OUT.apply(Math.max(0, (p - 0.25f) / 0.75f));
		g.fill(x, y + plateH - (int) (plateH * bar), x + 3, y + plateH, a(s.accent(), alpha));
		int w = (int) (plateW * wipe);
		if (w > 2) {
			g.fill(x + 3, y, x + 3 + w, y + plateH, a(s.background() != 0 ? s.background() : 0xB0000000, alpha));
			g.enableScissor(x + 3, y, x + 3 + w, y + plateH);
			g.pose().pushMatrix();
			g.pose().translate(x + 13, y + 6);
			g.pose().scale(scale, scale);
			g.text(font, Component.literal(name).withStyle(BOLD), 0, 0, a(s.color(), alpha), s.shadow());
			g.pose().popMatrix();
			if (!role.isEmpty()) {
				g.text(font, Component.literal(role.toUpperCase()), x + 13, y + 10 + (int) (font.lineHeight * scale), a(0xFFC8C8C8, alpha), false);
			}
			g.disableScissor();
		}
	}

	/** Dialogue box with speaker tab and typewriter text, bottom centre, wraps automatically. */
	private static void dialogue(GuiGraphicsExtractor g, Font font, HudTextSpec s, Style style, float alpha, float p, float reveal,
			float time) {
		int sw = g.guiWidth(), sh = g.guiHeight();
		int boxW = Math.min(sw - 40, 360);
		String full = String.join(" ", s.lines());
		int chars = reveal == Float.MAX_VALUE ? full.length() : Math.min(full.length(), (int) reveal);
		List<FormattedCharSequence> wrapped = font.split(Component.literal(full.substring(0, chars)).withStyle(style), boxW - 20);
		int lines = Math.max(2, font.split(Component.literal(full).withStyle(style), boxW - 20).size());
		int boxH = lines * (font.lineHeight + 2) + 16;
		float slide = (float) Easing.EASE_OUT.apply(p);
		int x = (sw - boxW) / 2 + (int) s.offsetX();
		int y = sh - boxH - 28 + (int) ((1 - slide) * 20) + (int) s.offsetY();
		g.fill(x, y, x + boxW, y + boxH, a(s.background() != 0 ? s.background() : 0xD0101014, alpha));
		g.fill(x, y, x + boxW, y + 1, a(s.accent(), alpha));
		if (!s.header().isEmpty()) {
			int tw = font.width(Component.literal(s.header()).withStyle(BOLD)) + 12;
			g.fill(x + 8, y - 12, x + 8 + tw, y, a(s.accent(), alpha));
			g.text(font, Component.literal(s.header()).withStyle(BOLD), x + 14, y - 10, a(0xFF101010, alpha), false);
		}
		int ty = y + 9;
		for (FormattedCharSequence line : wrapped) {
			g.text(font, line, x + 10, ty, a(s.color(), alpha), s.shadow());
			ty += font.lineHeight + 2;
		}
		if (chars >= full.length() && ((int) (time / 10)) % 2 == 0) {
			g.text(font, "▼", x + boxW - 14, y + boxH - 12, a(s.accent(), alpha), false);
		}
	}

	/** "New objective" card sliding in from the right edge. */
	private static void objective(GuiGraphicsExtractor g, Font font, HudTextSpec s, Style style, float alpha, float p) {
		int sw = g.guiWidth();
		String header = s.header().isEmpty() ? "NEUES ZIEL" : s.header();
		int w = 0;
		for (String l : s.lines()) {
			w = Math.max(w, font.width(Component.literal(l).withStyle(style)));
		}
		w = Math.max(w, font.width(header)) + 26;
		int h = 20 + s.lines().size() * (font.lineHeight + 2);
		float slide = (float) Easing.EASE_OUT_BACK.apply(p);
		int x = sw - 12 - (int) (w * slide) + (int) s.offsetX(), y = 40 + (int) s.offsetY();
		g.fill(x, y, x + w + 12, y + h, a(s.background() != 0 ? s.background() : 0xC0000000, alpha));
		g.fill(x, y, x + 3, y + h, a(s.accent(), alpha));
		g.text(font, Component.literal(header).withStyle(BOLD), x + 10, y + 5, a(s.accent(), alpha), false);
		int ty = y + 17;
		for (String l : s.lines()) {
			g.text(font, Component.literal(l).withStyle(style), x + 10, ty, a(s.color(), alpha), s.shadow());
			ty += font.lineHeight + 2;
		}
	}

	private static Identifier iconTexture(String icon) {
		return Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, "textures/gui/icons/" + icon + ".png");
	}

	/** Icon (12px) followed by the link text; returns the drawn width. */
	private static int linkRow(GuiGraphicsExtractor g, Font font, String icon, String link, int x, int y, float alpha, int color) {
		int w = 0;
		if (!icon.isEmpty()) {
			g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, iconTexture(icon), x, y - 2, 0, 0, 12, 12, 32, 32, 32, 32,
					a(0xFFFFFFFF, alpha));
			w += 15;
		}
		if (!link.isEmpty()) {
			g.text(font, link, x + w, y, a(color, alpha), true);
			w += font.width(link);
		}
		return w;
	}

	private static int linkWidth(Font font, HudTextSpec.Entry e) {
		return (e.icon().isEmpty() ? 0 : 15) + (e.link().isEmpty() ? 0 : font.width(e.link()));
	}

	/** Opening credits: one entry after another (role, name, icon + link), each fading in and out. */
	private static void credits(GuiGraphicsExtractor g, Font font, HudTextSpec s, float alpha, float time) {
		if (s.entries().isEmpty() || time < 0) {
			return;
		}
		// Smooth slide-in with sub-pixel precision (whole-pixel steps looked jerky).
		int idx = Math.min(s.entries().size() - 1, (int) (time / s.entryTicks()));
		float local = time - idx * s.entryTicks();
		float k = Math.min(1, local / 16f);
		float slide = (float) ((1 - (1 - Math.pow(1 - k, 3))) * 6);
		g.pose().pushMatrix();
		g.pose().translate(0, slide);
		creditsInner(g, font, s, alpha, time);
		g.pose().popMatrix();
	}

	private static void creditsInner(GuiGraphicsExtractor g, Font font, HudTextSpec s, float alpha, float time) {
		if (s.entries().isEmpty() || time < 0) {
			return;
		}
		int idx = Math.min(s.entries().size() - 1, (int) (time / s.entryTicks()));
		float local = time - idx * s.entryTicks();
		float fade = Math.min(1, Math.min(local / 14f, (s.entryTicks() - local) / 14f));
		fade = fade <= 0 ? 0 : fade * fade * (3 - 2 * fade); // smoothstep
		float a = alpha * Math.max(0, fade);
		if (a < 0.02f) {
			return;
		}
		HudTextSpec.Entry e = s.entries().get(idx);
		int sw = g.guiWidth(), sh = g.guiHeight();
		float scale = s.scale();
		Component name = Component.literal(e.name()).withStyle(BOLD);
		scale = Math.min(scale, (sw - 60f) / Math.max(1, font.width(name)));
		int nameW = (int) (font.width(name) * scale);
		int roleW = font.width(e.role().toUpperCase());
		int blockW = Math.max(nameW, Math.max(roleW, linkWidth(font, e)));
		boolean left = s.anchor() == HudTextSpec.Anchor.BOTTOM_LEFT || s.anchor() == HudTextSpec.Anchor.LEFT || s.anchor() == HudTextSpec.Anchor.TOP_LEFT;
		int x = left ? 30 : (sw - blockW) / 2;
		int y = s.anchor() == HudTextSpec.Anchor.BOTTOM_LEFT || s.anchor() == HudTextSpec.Anchor.BOTTOM ? sh - 70
				: s.anchor() == HudTextSpec.Anchor.TOP || s.anchor() == HudTextSpec.Anchor.TOP_LEFT ? 30 : sh / 2 - 24;
		x += (int) s.offsetX();
		y += (int) s.offsetY();
		if (e.heading()) {
			// Auto-fit: long headings shrink instead of running off screen.
			Component head = Component.literal(e.role()).withStyle(BOLD);
			float hs = Math.min(scale, (sw - 2f * Math.max(20, x)) / Math.max(1, font.width(head)));
			if (!left) {
				x = (int) ((sw - font.width(head) * hs) / 2 + s.offsetX());
			}
			g.pose().pushMatrix();
			g.pose().translate(x, y + 8);
			g.pose().scale(hs, hs);
			g.text(font, Component.literal(e.role()).withStyle(BOLD), 0, 0, a(s.accent(), a), true);
			g.pose().popMatrix();
			return;
		}
		if (!e.role().isEmpty()) {
			g.text(font, e.role().toUpperCase(), left ? x : (sw - roleW) / 2 + (int) s.offsetX(), y, a(0xFFB8B8B8, a), true);
		}
		g.pose().pushMatrix();
		g.pose().translate(left ? x : (sw - nameW) / 2f + s.offsetX(), y + 12);
		g.pose().scale(scale, scale);
		g.text(font, name, 0, 0, a(s.color(), a), true);
		g.pose().popMatrix();
		int lw = linkWidth(font, e);
		if (lw > 0) {
			linkRow(g, font, e.icon(), e.link(), left ? x : (sw - lw) / 2 + (int) s.offsetX(), y + 16 + (int) (font.lineHeight * scale),
					a, 0xFFDDDDDD);
		}
	}

	/** End credits roll: headings, role / name columns and link rows scrolling upwards. */
	private static void roll(GuiGraphicsExtractor g, Font font, HudTextSpec s, float alpha, float time) {
		int sw = g.guiWidth(), sh = g.guiHeight();
		float y = sh + 10 - time * s.rollSpeed();
		int cx = sw / 2;
		if (!s.lines().get(0).isEmpty()) {
			Component title = Component.literal(s.lines().get(0)).withStyle(BOLD);
			g.pose().pushMatrix();
			g.pose().translate(cx - font.width(title), y);
			g.pose().scale(2, 2);
			g.text(font, title, 0, 0, a(s.color(), alpha), true);
			g.pose().popMatrix();
			y += 34;
		}
		for (HudTextSpec.Entry e : s.entries()) {
			if (y > sh + 20) {
				break;
			}
			if (e.heading()) {
				y += 8;
				if (y > -20) {
					Component h = Component.literal(e.role().toUpperCase()).withStyle(BOLD);
					g.text(font, h, cx - font.width(h) / 2, (int) y, a(s.accent(), alpha), true);
				}
				y += 16;
				continue;
			}
			if (y > -20) {
				String role = e.role().toUpperCase();
				g.text(font, role, cx - 8 - font.width(role), (int) y, a(0xFFAAAAAA, alpha), true);
				g.text(font, e.name(), cx + 8, (int) y, a(s.color(), alpha), true);
			}
			y += 12;
			if (!e.icon().isEmpty() || !e.link().isEmpty()) {
				if (y > -20) {
					linkRow(g, font, e.icon(), e.link(), cx + 8, (int) y + 2, alpha, 0xFFCCCCCC);
				}
				y += 14;
			}
			y += 4;
		}
	}

	/** "THE END" card: big title, expanding rule, subtitle and a row of social links. */
	private static void endCard(GuiGraphicsExtractor g, Font font, HudTextSpec s, float alpha, float p, float time) {
		int sw = g.guiWidth(), sh = g.guiHeight();
		Component title = Component.literal(s.lines().get(0)).withStyle(BOLD);
		float scale = s.scale();
		float tw = font.width(title) * scale;
		float spacing = (float) Math.min(1, Math.max(0, time) / 120.0) * 0.6f; // slow tracking for a final feel
		g.pose().pushMatrix();
		g.pose().translate(sw / 2f - tw / 2f * (1 + spacing * 0.05f), sh / 2f - 30);
		g.pose().scale(scale * (1 + spacing * 0.05f), scale);
		g.text(font, title, 0, 0, a(s.color(), alpha), true);
		g.pose().popMatrix();
		int line = (int) (tw * 0.8f * (float) de.behindtheblocks.cinematic.camera.Easing.EASE_IN_OUT.apply(p));
		g.fill(sw / 2 - line / 2, sh / 2 - 26 + (int) (font.lineHeight * scale), sw / 2 + line / 2, sh / 2 - 25 + (int) (font.lineHeight * scale),
				a(s.accent(), alpha));
		if (s.lines().size() > 1) {
			String sub = s.lines().get(1);
			g.text(font, sub, sw / 2 - font.width(sub) / 2, sh / 2 - 16 + (int) (font.lineHeight * scale), a(0xFFCCCCCC, alpha * p), true);
		}
		if (!s.entries().isEmpty()) {
			int total = 0;
			for (HudTextSpec.Entry e : s.entries()) {
				total += linkWidth(font, e) + 16;
			}
			int x = sw / 2 - total / 2;
			for (HudTextSpec.Entry e : s.entries()) {
				x += linkRow(g, font, e.icon(), e.link(), x, sh / 2 + 34, alpha * p, 0xFFDDDDDD) + 16;
			}
		}
	}

	/** Open book with a title and handwritten-looking text revealed like ink. */
	private static void book(GuiGraphicsExtractor g, Font font, HudTextSpec s, Style style, float alpha, float time, float reveal) {
		int sw = g.guiWidth(), sh = g.guiHeight();
		float p = Math.min(1, time / Math.max(1, s.in()));
		if (reveal != Float.MAX_VALUE) {
			reveal = Math.max(0, (time - s.in()) / s.charTicks());
		}
		int pageW = Math.min(150, sw / 2 - 30), pageH = Math.min(180, sh - 40);
		float open = (float) de.behindtheblocks.cinematic.camera.Easing.EASE_OUT.apply(p);
		int cx = sw / 2, top = (sh - pageH) / 2 + (int) ((1 - open) * 20);
		int w = (int) (pageW * open);
		int paper = a(0xFFE8D9B5, alpha), paper2 = a(0xFFDCCB9F, alpha), cover = a(0xFF4A1A12, alpha);
		g.fill(cx - w - 8, top - 6, cx + w + 8, top + pageH + 6, cover);
		g.fill(cx - w, top, cx - 1, top + pageH, paper);
		g.fill(cx + 1, top, cx + w, top + pageH, paper2);
		g.fill(cx - 2, top, cx + 2, top + pageH, a(0x40000000, alpha));
		if (open < 0.95f) {
			return;
		}
		int ink = a(0xFF3B2414, alpha);
		int x0 = cx - pageW + 12, y = top + 14;
		if (!s.header().isEmpty()) {
			g.text(font, Component.literal(s.header()).withStyle(BOLD), x0, y, a(0xFF6A1A10, alpha), false);
			y += 18;
		}
		String full = String.join(" ", s.lines());
		int chars = reveal == Float.MAX_VALUE ? full.length() : Math.min(full.length(), (int) reveal);
		List<FormattedCharSequence> left = font.split(Component.literal(full.substring(0, chars)).withStyle(style), pageW - 24);
		int maxLines = (pageH - (y - top) - 10) / (font.lineHeight + 2);
		for (int i = 0; i < left.size(); i++) {
			boolean second = i >= maxLines;
			int lx = second ? cx + 12 : x0;
			int ly = second ? top + 14 + (i - maxLines) * (font.lineHeight + 2) : y + i * (font.lineHeight + 2);
			g.text(font, left.get(i), lx, ly, ink, false);
		}
	}

	/** Boss introduction: red slash, the name slams in, title underneath. */
	private static void bossIntro(GuiGraphicsExtractor g, Font font, HudTextSpec s, float alpha, float p, float time) {
		int sw = g.guiWidth(), sh = g.guiHeight();
		float slash = (float) de.behindtheblocks.cinematic.camera.Easing.EASE_OUT.apply(Math.min(1, p * 1.6f));
		// Band grows with the title so the subtitle sits inside it, clear of the red border lines.
		int nameH = (int) Math.ceil(font.lineHeight * s.scale());
		boolean sub = s.lines().size() > 1;
		int band = Math.max(46, 10 + nameH + (sub ? 6 + font.lineHeight : 0) + 10), y = sh / 2 - band / 2 + (int) s.offsetY();
		int len = (int) (sw * slash);
		for (int i = 0; i < band; i++) {
			int skew = (band - i) / 2;
			g.fill(skew, y + i, skew + len, y + i + 1, a(i < 3 || i > band - 4 ? s.accent() : 0xC0100000, alpha));
		}
		if (p < 0.35f) {
			return;
		}
		float in = Math.min(1, (p - 0.35f) / 0.4f);
		Component name = Component.literal(s.lines().get(0)).withStyle(BOLD);
		float scale = s.scale() * (1.25f - 0.25f * in);
		int x = 48 + (int) ((1 - in) * -60);
		float shake = in < 1 ? 0 : (float) Math.sin(time * 1.7) * 0.4f;
		g.pose().pushMatrix();
		g.pose().translate(x + shake, y + 10);
		g.pose().scale(scale, scale);
		g.text(font, name, 0, 0, a(s.color(), alpha * in), true);
		g.pose().popMatrix();
		if (s.lines().size() > 1) {
			g.text(font, s.lines().get(1).toUpperCase(), x + 4, y + 10 + nameH + 6, a(0xFFE0B0A0, alpha * in), true);
		}
	}

	/** Camcorder viewfinder: REC dot, running timecode, corner brackets, battery, camera label. */
	private static void camcorder(GuiGraphicsExtractor g, Font font, HudTextSpec s, float alpha, float time) {
		int sw = g.guiWidth(), sh = g.guiHeight();
		int m = 14, len = 22, th = 2;
		int c = a(0xFFFFFFFF, alpha * 0.9f);
		// corner brackets
		g.fill(m, m, m + len, m + th, c);
		g.fill(m, m, m + th, m + len, c);
		g.fill(sw - m - len, m, sw - m, m + th, c);
		g.fill(sw - m - th, m, sw - m, m + len, c);
		g.fill(m, sh - m - th, m + len, sh - m, c);
		g.fill(m, sh - m - len, m + th, sh - m, c);
		g.fill(sw - m - len, sh - m - th, sw - m, sh - m, c);
		g.fill(sw - m - th, sh - m - len, sw - m, sh - m, c);
		// centre cross
		g.fill(sw / 2 - 6, sh / 2, sw / 2 + 7, sh / 2 + 1, a(0xFFFFFFFF, alpha * 0.5f));
		g.fill(sw / 2, sh / 2 - 6, sw / 2 + 1, sh / 2 + 7, a(0xFFFFFFFF, alpha * 0.5f));
		// REC (blinks once per second)
		if (((int) (time / 10)) % 2 == 0) {
			g.fill(m + 10, m + 10, m + 16, m + 16, a(0xFFFF2020, alpha));
		}
		g.text(font, "REC", m + 20, m + 9, c, true);
		// timecode HH:MM:SS:FF at 20 fps game time
		int frames = (int) time;
		String tc = String.format("%02d:%02d:%02d:%02d", frames / 72000, (frames / 1200) % 60, (frames / 20) % 60, frames % 20);
		g.text(font, tc, sw - m - 10 - font.width(tc), m + 9, c, true);
		// battery
		int bx = sw - m - 34, by = sh - m - 18;
		g.outline(bx, by, 22, 10, c);
		g.fill(bx + 22, by + 3, bx + 24, by + 7, c);
		g.fill(bx + 2, by + 2, bx + 15, by + 8, c);
		String label = s.lines().get(0);
		if (!label.isBlank()) {
			g.text(font, label, m + 10, sh - m - 17, c, true);
		}
	}
}
