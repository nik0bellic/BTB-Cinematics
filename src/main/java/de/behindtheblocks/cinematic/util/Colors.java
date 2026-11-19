package de.behindtheblocks.cinematic.util;

import de.behindtheblocks.cinematic.json.JsonParamException;

import java.util.Locale;
import java.util.Map;

/** Parses colors given as names ({@code white}, {@code nether_red}) or hex ({@code #RRGGBB}, {@code #AARRGGBB}). */
public final class Colors {
	private static final Map<String, Integer> NAMED = Map.ofEntries(
			Map.entry("white", 0xFFFFFFFF),
			Map.entry("black", 0xFF000000),
			Map.entry("red", 0xFFE02020),
			Map.entry("dark_red", 0xFF6A0000),
			Map.entry("orange", 0xFFFF8A1C),
			Map.entry("gold", 0xFFFFC23A),
			Map.entry("yellow", 0xFFFFE85C),
			Map.entry("green", 0xFF35C24A),
			Map.entry("cyan", 0xFF33D6E0),
			Map.entry("blue", 0xFF2A5BFF),
			Map.entry("purple", 0xFF8A2BE2),
			Map.entry("magenta", 0xFFE040E0),
			Map.entry("gray", 0xFF808080),
			Map.entry("nether_red", 0xFF5A0B0B),
			Map.entry("lava", 0xFFFF5A00),
			Map.entry("soul", 0xFF3FE0F0),
			Map.entry("portal", 0xFFB040FF));

	private Colors() {
	}

	/** @return ARGB color */
	public static int parse(String value) {
		String v = value.trim().toLowerCase(Locale.ROOT);
		Integer named = NAMED.get(v);
		if (named != null) {
			return named;
		}
		if (v.startsWith("#")) {
			v = v.substring(1);
		} else if (v.startsWith("0x")) {
			v = v.substring(2);
		}
		try {
			if (v.length() == 6) {
				return 0xFF000000 | Integer.parseUnsignedInt(v, 16);
			}
			if (v.length() == 8) {
				return Integer.parseUnsignedInt(v, 16);
			}
		} catch (NumberFormatException ignored) {
			// fall through
		}
		throw new JsonParamException("unknown color '" + value + "' (name like white/black, or hex: #RRGGBB in JSON, RRGGBB without # in commands)");
	}

	public static java.util.Set<String> names() {
		return NAMED.keySet();
	}

	public static int withAlpha(int argb, float alpha) {
		int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * ((argb >>> 24) & 0xFF));
		return (a << 24) | (argb & 0x00FFFFFF);
	}

	public static int lerp(int from, int to, float t) {
		t = Math.max(0f, Math.min(1f, t));
		int a = (int) (((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * t);
		int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
		int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
		int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}
}
