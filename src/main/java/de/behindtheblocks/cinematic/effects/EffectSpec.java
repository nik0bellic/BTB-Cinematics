package de.behindtheblocks.cinematic.effects;

import com.google.gson.JsonObject;
import de.behindtheblocks.cinematic.json.JsonParamException;
import de.behindtheblocks.cinematic.json.JsonParams;
import de.behindtheblocks.cinematic.util.Colors;

import java.util.List;
import java.util.Locale;

/**
 * Validated screen-effect instruction, shared by server (validation) and client (rendering).
 *
 * <p>Every effect has an envelope: ramp up over {@code in} ticks, stay for {@code hold} ticks
 * ({@code -1} = until cleared), ramp down over {@code out} ticks. {@code intensity} scales the
 * effect (alpha for color effects, strength for blur/vignette, bar size for letterbox).
 */
public record EffectSpec(Kind kind, int color, int color2, float intensity, int in, int hold, int out, String style) {

	public enum Kind {
		/** Full-screen color fade ("Weißblende", "Schwarzblende"). */
		FADE,
		/** Short bright flash that decays. */
		FLASH,
		/** Darkened (or colored) screen edges. */
		VIGNETTE,
		/** Color overlay; with {@code color2} it cross-fades between two colors ("Farbüberblendung"). */
		TINT,
		/** Gaussian blur of the world (Minecraft's menu blur pass). */
		BLUR,
		/** Cinematic black bars top and bottom. */
		LETTERBOX,
		/** Shaped transitions: wipe_left/right/up/down, iris_close/open, bars_close/open. */
		TRANSITION,
		/** Lens flare on a world position (style = "x,y,z"); hidden when the point is behind a wall. */
		FLARE,
		/** Film grain + soft optical edge falloff (post shader, 3 levels by intensity). */
		GRAIN,
		/** Heat shimmer over the lower part of the frame (post shader, 3 levels by intensity). */
		HAZE,
		/** Muffled hearing after a blast: low-pass on all sounds (intensity = how deaf). */
		MUFFLE,
		/** Depth of field from the depth buffer; style = "auto" (follows what the camera looks at) or a distance in blocks. */
		DOF,
		/** Anamorphic lens streaks on bright lights. */
		ANAMORPH,
		/** Flashback / memory look: warm, washed out, soft edges, gentle drift. */
		FLASHBACK,
		/** Colour grading with a 3D LUT (style = built-in look or .cube file name). */
		LUT,
		/** Fades out the given effect slot (style = slot name) or all ("all"). */
		CLEAR;

		public String id() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	public static final List<String> TRANSITIONS = List.of(
			"wipe_left", "wipe_right", "wipe_up", "wipe_down", "iris_close", "iris_open", "bars_close", "bars_open");

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("effect", kind.id());
		o.addProperty("color", String.format("#%08X", color));
		o.addProperty("color2", String.format("#%08X", color2));
		o.addProperty("intensity", intensity);
		o.addProperty("in", in);
		o.addProperty("hold", hold);
		o.addProperty("out", out);
		o.addProperty("style", style);
		return o;
	}

	/** Parses the wire/JSON form (also used for scene event parameters). */
	public static EffectSpec fromJson(Kind kind, JsonParams p) {
		int color = Colors.parse(p.getString("color", defaultColor(kind)));
		int color2 = Colors.parse(p.getString("color2", p.getString("color", defaultColor(kind))));
		float intensity = p.getFloat("intensity", kind == Kind.LETTERBOX ? 0.12f : kind == Kind.BLUR ? 5f
				: kind == Kind.VIGNETTE ? 0.8f : 1f);
		int in = p.getInt("in", p.getInt("duration", defaultIn(kind)));
		int hold = p.getInt("hold", kind == Kind.FLASH || kind == Kind.CLEAR ? 0 : -1);
		int out = p.getInt("out", kind == Kind.FLASH ? p.getInt("duration", 12) : 0);
		String style = p.getString("style", kind == Kind.TRANSITION ? "wipe_left" : kind == Kind.CLEAR ? "all" : "");
		if (kind == Kind.FLARE && p.has("pos")) {
			double[] pos = p.requireDoubleArray("pos", 3);
			style = pos[0] + "," + pos[1] + "," + pos[2];
		}
		if (kind == Kind.FLARE && style.split(",").length != 3) {
			throw new JsonParamException("flare needs 'pos': [x,y,z]");
		}
		if (kind == Kind.FLASH) {
			in = p.getInt("in", 1);
		}
		if (in < 0 || in > 72000 || out < 0 || out > 72000 || hold < -1 || hold > 720000) {
			throw new JsonParamException("in/out must be 0..72000 ticks, hold -1..720000");
		}
		float max = switch (kind) {
			case BLUR -> 10f;
			case LETTERBOX -> 0.4f;
			default -> 1f;
		};
		if (intensity < 0 || intensity > max) {
			throw new JsonParamException("'intensity' for " + kind.id() + " must be 0.." + max);
		}
		if (kind == Kind.TRANSITION && !TRANSITIONS.contains(style)) {
			throw new JsonParamException("'style' must be one of " + TRANSITIONS);
		}
		if (kind == Kind.CLEAR && !List.of("all", "fade", "vignette", "tint", "blur", "letterbox", "transition", "flare", "grain", "haze", "muffle", "dof", "anamorph", "flashback", "lut").contains(style)) {
			throw new JsonParamException("clear 'style' must be all, fade, vignette, tint, blur, letterbox or transition");
		}
		return new EffectSpec(kind, color, color2, intensity, in, hold, out, style);
	}

	public static EffectSpec fromWire(JsonObject o) {
		JsonParams p = new JsonParams(o);
		Kind kind;
		try {
			kind = Kind.valueOf(p.requireString("effect").toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new JsonParamException("unknown effect");
		}
		return fromJson(kind, p);
	}

	private static String defaultColor(Kind kind) {
		return switch (kind) {
			case FADE, VIGNETTE, LETTERBOX, TRANSITION, CLEAR, BLUR, GRAIN, HAZE, MUFFLE, DOF, ANAMORPH, FLASHBACK, LUT -> "black";
			case FLASH, TINT -> "white";
			case FLARE -> "#FFE0B0";
		};
	}

	private static int defaultIn(Kind kind) {
		return switch (kind) {
			case FLASH -> 1;
			case CLEAR -> 20;
			default -> 20;
		};
	}
}
