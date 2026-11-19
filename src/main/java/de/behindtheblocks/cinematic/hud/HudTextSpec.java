package de.behindtheblocks.cinematic.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.behindtheblocks.cinematic.json.JsonParamException;
import de.behindtheblocks.cinematic.json.JsonParams;
import de.behindtheblocks.cinematic.util.Colors;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A cinematic text overlay (title card, subtitle, caption). Parsed from JSON on the server for
 * validation and again on the client for rendering — the wire format is the JSON itself.
 *
 * <pre>{@code
 * {
 *   "lines": ["IF YOU WANT TO LEAVE THIS PLACE,", "FIND THE HEART OF THE NETHER."],
 *   "font": "cinematic",              // cinematic | cinematic_bold | default | uniform | <namespace:id>
 *   "scale": 2.0, "color": "#FFE0B0", "shadow": true, "glow": "#FF5A00",
 *   "anchor": "center", "offset": [0, -20], "align": "center",
 *   "line_spacing": 4, "letter_spacing": 1.5,
 *   "animation_in": "typewriter", "animation_out": "fade", "effect": "none",
 *   "delay": 0, "in": 30, "hold": 80, "out": 30, "char_ticks": 1.5,
 *   "background": "#80000000", "padding": 6, "above_effects": true
 * }
 * }</pre>
 */
public record HudTextSpec(
		String id,
		List<String> lines,
		String font,
		float scale,
		int color,
		int glowColor,
		boolean shadow,
		Anchor anchor,
		float offsetX,
		float offsetY,
		Align align,
		float lineSpacing,
		float letterSpacing,
		AnimIn animIn,
		AnimOut animOut,
		Effect effect,
		int delay,
		int in,
		int hold,
		int out,
		float charTicks,
		int background,
		float padding,
		boolean aboveEffects,
		Kind style,
		int accent,
		String header,
		List<Entry> entries,
		int entryTicks,
		float rollSpeed) {

	/** One credits line: a heading, or role + name with optional link icon and link text. */
	public record Entry(boolean heading, String role, String name, String icon, String link) {
	}

	/** Icons available for credits links ({@code textures/gui/icons/<name>.png}, replaceable by resource packs). */
	public static final List<String> ICONS = List.of("youtube", "spotify", "twitch", "instagram", "tiktok", "discord", "x",
			"soundcloud", "patreon", "github", "web", "mail");

	/**
	 * Visual template. {@code TEXT} is free typography; the others are ready-made film overlays:
	 * {@code LOWER_THIRD} (name + role, line 1/2), {@code DIALOGUE} (speaker = header, text = lines),
	 * {@code OBJECTIVE} (header + lines, slides in top right), {@code CAMCORDER} (REC overlay with
	 * timecode, line 1 = camera label).
	 */
	public enum Kind { TEXT, LOWER_THIRD, DIALOGUE, OBJECTIVE, CAMCORDER, CREDITS, ROLL, END_CARD, BOOK, BOSS_INTRO }

	public enum Anchor { CENTER, TOP, BOTTOM, LEFT, RIGHT, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

	public enum Align { LEFT, CENTER, RIGHT }

	/** Entrance animation. */
	public enum AnimIn { NONE, FADE, TYPEWRITER, LETTERS, SLIDE_UP, SLIDE_DOWN, ZOOM, TRACKING, GLITCH, BLUR }

	/** Exit animation. */
	public enum AnimOut { NONE, FADE, SLIDE_UP, SLIDE_DOWN, ZOOM, TRACKING, GLITCH }

	/** Continuous effect while visible. */
	/** Continuous effect while visible. {@code ETHEREAL}: wavering, breathing text with a colour fringe. */
	public enum Effect { NONE, WAVE, PULSE, SHAKE, GLITCH, RAINBOW, FLICKER, ETHEREAL }

	public static final int MAX_LINES = 12;
	public static final int MAX_LINE_LENGTH = 200;

	/** Total visible lifetime in ticks, or -1 if it holds until hidden. */
	public int lifetime() {
		return hold < 0 ? -1 : delay + in + hold + out;
	}

	public static HudTextSpec fromJson(String fallbackId, JsonObject json) {
		JsonParams p = new JsonParams(json);
		String id = p.getString("id", fallbackId);
		List<String> lines = new ArrayList<>();
		if (p.has("lines")) {
			if (!json.get("lines").isJsonArray()) {
				throw new JsonParamException("'lines' must be an array of strings");
			}
			for (JsonElement e : json.getAsJsonArray("lines")) {
				if (!e.isJsonPrimitive()) {
					throw new JsonParamException("'lines' must be an array of strings");
				}
				lines.add(e.getAsString());
			}
		} else if (p.has("text")) {
			lines.addAll(List.of(p.requireString("text").split("\n", -1)));
		} else if (json.has("entries")) {
			lines.add(p.getString("title", ""));
		} else {
			throw new JsonParamException("needs 'text', 'lines' or 'entries'");
		}
		if (lines.isEmpty() || lines.size() > MAX_LINES) {
			throw new JsonParamException("1.." + MAX_LINES + " lines allowed");
		}
		for (String l : lines) {
			if (l.length() > MAX_LINE_LENGTH) {
				throw new JsonParamException("line longer than " + MAX_LINE_LENGTH + " characters");
			}
		}
		float scale = p.getFloat("scale", 2f);
		if (scale < 0.25f || scale > 12f) {
			throw new JsonParamException("'scale' must be 0.25..12");
		}
		float[] offset = {0, 0};
		if (p.has("offset")) {
			double[] o = p.requireDoubleArray("offset", 2);
			offset[0] = (float) o[0];
			offset[1] = (float) o[1];
		}
		List<Entry> entries = new ArrayList<>();
		if (json.has("entries")) {
			for (JsonElement e : json.getAsJsonArray("entries")) {
				JsonParams ep = new JsonParams(e.getAsJsonObject());
				String icon = ep.getString("icon", "");
				if (!icon.isEmpty() && !ICONS.contains(icon)) {
					throw new JsonParamException("unknown icon '" + icon + "' (" + String.join(", ", ICONS) + ")");
				}
				entries.add(new Entry(ep.has("heading"), ep.getString("heading", ep.getString("role", "")), ep.getString("name", ""),
						icon, ep.getString("link", "")));
			}
			if (entries.size() > 200) {
				throw new JsonParamException("at most 200 credits entries");
			}
		}
		int entryTicks = Math.max(10, p.getInt("entry_ticks", 70));
		float rollSpeed = Math.max(0.05f, Math.min(10f, p.getFloat("roll_speed", 0.6f)));
		Kind kindForTiming = p.getEnum("style", Kind.class, Kind.TEXT);
		int in = p.getInt("in", 20);
		int defaultHold = switch (kindForTiming) {
			case CREDITS -> Math.max(1, entries.size()) * entryTicks;
			case ROLL -> (int) ((entries.size() * 26 + 320) / rollSpeed);
			default -> 60;
		};
		int hold = p.getInt("hold", defaultHold);
		int out = p.getInt("out", 20);
		int delay = p.getInt("delay", 0);
		if (in < 0 || out < 0 || delay < 0 || hold < -1) {
			throw new JsonParamException("timing values must be >= 0 (hold may be -1 = until hidden)");
		}
		float charTicks = p.getFloat("char_ticks", 1.5f);
		if (charTicks < 0.05f || charTicks > 40) {
			throw new JsonParamException("'char_ticks' must be 0.05..40");
		}
		String font = p.getString("font", "cinematic");
		if (!font.matches("[a-z0-9_.:/-]{1,80}")) {
			throw new JsonParamException("'font' is not a valid font id");
		}
		return new HudTextSpec(id, List.copyOf(lines), font, scale,
				Colors.parse(p.getString("color", "white")),
				p.has("glow") ? Colors.parse(p.getString("glow", "white")) : 0,
				p.getBoolean("shadow", true),
				p.getEnum("anchor", Anchor.class, Anchor.CENTER),
				offset[0], offset[1],
				p.getEnum("align", Align.class, Align.CENTER),
				p.getFloat("line_spacing", 4f),
				p.getFloat("letter_spacing", 0f),
				p.getEnum("animation_in", AnimIn.class, AnimIn.FADE),
				p.getEnum("animation_out", AnimOut.class, AnimOut.FADE),
				p.getEnum("effect", Effect.class, Effect.NONE),
				delay, in, hold, out, charTicks,
				p.has("background") ? Colors.parse(p.getString("background", "#80000000")) : 0,
				p.getFloat("padding", 6f),
				p.getBoolean("above_effects", true),
				p.getEnum("style", Kind.class, Kind.TEXT),
				Colors.parse(p.getString("accent", "#FF7A1A")),
				p.getString("header", ""),
				List.copyOf(entries), entryTicks, rollSpeed);
	}

	public static List<String> enumNames(Class<? extends Enum<?>> type) {
		List<String> out = new ArrayList<>();
		for (Enum<?> e : type.getEnumConstants()) {
			out.add(e.name().toLowerCase(Locale.ROOT));
		}
		return out;
	}
}
