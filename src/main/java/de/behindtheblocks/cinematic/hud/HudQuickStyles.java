package de.behindtheblocks.cinematic.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.behindtheblocks.cinematic.json.JsonParamException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ready-made text looks for typing HUD texts straight into chat or the Director, without files:
 * {@code /btb hud text boss 5 DER WÄCHTER | Hüter des Herzens}. A {@code |} starts a new line;
 * for name styles (lower third, dialogue, boss, book, end card) the first part is the name/heading.
 */
public final class HudQuickStyles {
	private HudQuickStyles() {
	}

	/** Style keyword → short German description (shown in suggestions, help and the Director). */
	public static final Map<String, String> STYLES = new LinkedHashMap<>();

	static {
		STYLES.put("titel", "Großer Kinotitel (Cinzel, fährt auseinander)");
		STYLES.put("glut", "Glühender Titel unten (wie HEART OF THE NETHER)");
		STYLES.put("kapitel", "Kapitelkarte, Schreibmaschine – Zeile 1 | Zeile 2");
		STYLES.put("untertitel", "Untertitel unten mit Hintergrund");
		STYLES.put("bauchbinde", "Name | Rolle (Lower Third)");
		STYLES.put("dialog", "Sprecher | Text | Text …");
		STYLES.put("ziel", "Neues Ziel (oben rechts)");
		STYLES.put("buch", "Überschrift | Text … (Buchseite, Text wie Tinte)");
		STYLES.put("boss", "Name | Untertitel (Boss-Intro)");
		STYLES.put("wabernd", "Geisterhafte, wabernde Botschaft");
		STYLES.put("endkarte", "Titel | Untertitel (bleibt stehen)");
		STYLES.put("camcorder", "Kamera-Sucher mit REC und Text");
	}

	/**
	 * @param seconds total time on screen; 0 = stays until {@code /btb hud hide}
	 */
	public static JsonObject build(String style, double seconds, String text) {
		List<String> parts = new ArrayList<>();
		for (String p : text.replace("\\n", "|").split("\\|")) {
			if (!p.isBlank()) {
				parts.add(p.trim());
			}
		}
		if (parts.isEmpty()) {
			throw new JsonParamException("Text fehlt");
		}
		JsonObject o = new JsonObject();
		o.addProperty("id", style);
		int in, out;
		switch (style) {
			case "titel" -> {
				lines(o, parts);
				o.addProperty("font", "cinematic");
				o.addProperty("scale", 3);
				o.addProperty("letter_spacing", 2);
				o.addProperty("line_spacing", 6);
				o.addProperty("animation_in", "tracking");
				o.addProperty("animation_out", "fade");
				in = 30;
				out = 30;
			}
			case "glut" -> {
				lines(o, parts);
				o.addProperty("font", "cinematic_bold");
				o.addProperty("scale", 3.2);
				o.addProperty("color", "#FFD9A0");
				o.addProperty("glow", "#FF3A00");
				o.addProperty("letter_spacing", 2.0);
				o.addProperty("anchor", "bottom");
				o.add("offset", arr(0, -36));
				o.addProperty("animation_in", "tracking");
				o.addProperty("animation_out", "fade");
				o.addProperty("effect", "pulse");
				in = 40;
				out = 30;
			}
			case "kapitel" -> {
				lines(o, parts);
				o.addProperty("font", "cinematic");
				o.addProperty("scale", 2.4);
				o.addProperty("letter_spacing", 1.5);
				o.addProperty("line_spacing", 8);
				o.addProperty("animation_in", "typewriter");
				o.addProperty("char_ticks", 2.0);
				o.addProperty("animation_out", "fade");
				in = 20;
				out = 30;
			}
			case "untertitel" -> {
				o.addProperty("text", String.join("\n", parts));
				o.addProperty("font", "default");
				o.addProperty("scale", 1.5);
				o.addProperty("anchor", "bottom");
				o.add("offset", arr(0, -40));
				o.addProperty("animation_in", "fade");
				o.addProperty("background", "#90000000");
				o.addProperty("padding", 4);
				in = 6;
				out = 6;
			}
			case "bauchbinde" -> {
				o.addProperty("style", "lower_third");
				lines(o, parts);
				o.addProperty("scale", 1.8);
				o.addProperty("accent", "#FF7A1A");
				in = 30;
				out = 20;
			}
			case "dialog" -> {
				o.addProperty("style", "dialogue");
				headerAndRest(o, parts);
				o.addProperty("font", "default");
				o.addProperty("animation_in", "typewriter");
				o.addProperty("char_ticks", 1.0);
				o.addProperty("accent", "#FF7A1A");
				in = 10;
				out = 15;
			}
			case "ziel" -> {
				o.addProperty("style", "objective");
				o.addProperty("header", "NEUES ZIEL");
				o.addProperty("font", "default");
				lines(o, parts);
				o.addProperty("accent", "#FFC23A");
				in = 18;
				out = 18;
			}
			case "buch" -> {
				o.addProperty("style", "book");
				headerAndRest(o, parts);
				o.addProperty("font", "default");
				o.addProperty("animation_in", "typewriter");
				o.addProperty("char_ticks", 1.4);
				in = 20;
				out = 25;
			}
			case "boss" -> {
				o.addProperty("style", "boss_intro");
				lines(o, parts);
				o.addProperty("scale", 3.0);
				o.addProperty("accent", "#FF2A10");
				in = 30;
				out = 20;
			}
			case "wabernd" -> {
				lines(o, parts);
				o.addProperty("font", "cinematic");
				o.addProperty("scale", 2.0);
				o.addProperty("color", "#E8D8FF");
				o.addProperty("glow", "#7A40FF");
				o.addProperty("effect", "ethereal");
				o.addProperty("animation_in", "blur");
				o.addProperty("animation_out", "fade");
				o.addProperty("letter_spacing", 1.2);
				o.addProperty("line_spacing", 6);
				in = 50;
				out = 50;
			}
			case "endkarte" -> {
				o.addProperty("style", "end_card");
				lines(o, parts);
				o.addProperty("scale", 3.2);
				o.addProperty("accent", "#FF7A1A");
				in = 60;
				out = 30;
			}
			case "camcorder" -> {
				o.addProperty("style", "camcorder");
				lines(o, parts);
				in = 10;
				out = 10;
			}
			default -> throw new JsonParamException("Unbekannter Stil '" + style + "'. Stile: " + String.join(", ", STYLES.keySet()));
		}
		int total = (int) Math.round(seconds * 20);
		o.addProperty("in", in);
		o.addProperty("out", out);
		// 0 seconds = stays; otherwise the given time is the whole appearance incl. fade in/out.
		o.addProperty("hold", total <= 0 ? -1 : Math.max(1, total - in - out));
		return o;
	}

	private static void lines(JsonObject o, List<String> parts) {
		JsonArray a = new JsonArray();
		parts.forEach(a::add);
		o.add("lines", a);
	}

	private static void headerAndRest(JsonObject o, List<String> parts) {
		if (parts.size() > 1) {
			o.addProperty("header", parts.get(0));
			lines(o, parts.subList(1, parts.size()));
		} else {
			lines(o, parts);
		}
	}

	private static JsonArray arr(int a, int b) {
		JsonArray x = new JsonArray();
		x.add(a);
		x.add(b);
		return x;
	}
}
