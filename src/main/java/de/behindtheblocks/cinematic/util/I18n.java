package de.behindtheblocks.cinematic.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.behindtheblocks.cinematic.BtbCinematic;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * German / English for every text of the mod. All texts live in {@code assets/btb_cinematic/i18n.json}
 * as [source, german, english]; the source is the text exactly as the code builds it, with {@code {0}}
 * (or printf {@code %s}, {@code %.1f} …) for the parts that change. A finished message is matched
 * against these templates, so call sites stay simple: {@code I18n.tr(message, german)}.
 *
 * <p>Language: {@code config/btb_cinematic/settings.json} → {@code "language": "auto" | "de" | "en"}.
 * "auto" follows each player's Minecraft language (German → German, everything else → English).
 */
public final class I18n {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/i18n");
	private static final Pattern DECO = Pattern.compile("(?s)^((?:§.|\\d+\\.\\s+|[^\\p{L}\\p{N}'„“\"(\\[{])*)(.*?\\S)((?:§.|[\\s:·…▸▴▾])*)$");
	private static final Pattern PH = Pattern.compile("\\{\\d+\\}|%[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z]");

	private record Tpl(Pattern re, String literalPrefix, List<String> tokens, String de, String en, int weight) {
	}

	private static final Map<String, String[]> EXACT = new HashMap<>();
	private static final List<Tpl> TEMPLATES = new ArrayList<>();
	private static volatile String language = "auto";
	private static boolean loaded;
	/** Test switch: logs every shown text without translation once (finds forgotten texts). */
	private static final boolean DEBUG = false;
	private static final java.util.Set<String> MISSES = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private I18n() {
	}

	// ------------------------------------------------------------------ setting

	public static Path settingsFile() {
		return FabricLoader.getInstance().getConfigDir().resolve(BtbCinematic.MOD_ID).resolve("settings.json");
	}

	/** "auto", "de" or "en". */
	public static String language() {
		return language;
	}

	public static void setLanguage(String lang, boolean save) {
		language = lang.equals("de") || lang.equals("en") ? lang : "auto";
		if (save) {
			try {
				JsonObject o = Files.exists(settingsFile()) ? JsonParser.parseString(Files.readString(settingsFile())).getAsJsonObject() : new JsonObject();
				o.addProperty("language", language);
				Files.createDirectories(settingsFile().getParent());
				Files.writeString(settingsFile(), o.toString(), StandardCharsets.UTF_8);
			} catch (Exception e) {
				LOG.warn("settings.json not written: {}", e.toString());
			}
		}
	}

	public static void loadSettings() {
		try {
			if (Files.exists(settingsFile())) {
				JsonObject o = JsonParser.parseString(Files.readString(settingsFile())).getAsJsonObject();
				if (o.has("language")) {
					setLanguage(o.get("language").getAsString(), false);
				}
			} else {
				setLanguage("auto", true); // writes the file so server owners find the option
			}
		} catch (Exception e) {
			LOG.warn("settings.json not readable: {}", e.toString());
		}
	}

	/** German for this player? (setting first, then the player's Minecraft language) */
	public static boolean german(ServerPlayer player) {
		if (!language.equals("auto")) {
			return language.equals("de");
		}
		return player != null && player.clientInformation().language().toLowerCase(java.util.Locale.ROOT).startsWith("de");
	}

	/** German for a game language code like "de_de" (client side). */
	public static boolean germanFor(String minecraftLanguage) {
		if (!language.equals("auto")) {
			return language.equals("de");
		}
		return minecraftLanguage != null && minecraftLanguage.toLowerCase(java.util.Locale.ROOT).startsWith("de");
	}

	// ------------------------------------------------------------------ translation

	private static synchronized void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		try (InputStream in = I18n.class.getResourceAsStream("/assets/" + BtbCinematic.MOD_ID + "/i18n.json")) {
			if (in == null) {
				LOG.warn("i18n.json missing – texts stay as written");
				return;
			}
			JsonArray arr = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
			for (JsonElement el : arr) {
				JsonArray e = el.getAsJsonArray();
				String src = e.get(0).getAsString(), de = e.get(1).getAsString(), en = e.get(2).getAsString();
				Matcher m = PH.matcher(src);
				if (!m.find()) {
					EXACT.put(src, new String[] {de, en});
					continue;
				}
				m.reset();
				StringBuilder re = new StringBuilder("^");
				List<String> tokens = new ArrayList<>();
				int last = 0, literal = 0;
				String prefix = null;
				while (m.find()) {
					String lit = src.substring(last, m.start());
					if (prefix == null) {
						prefix = lit;
					}
					literal += lit.length();
					re.append(Pattern.quote(lit)).append("(.*?)");
					tokens.add(m.group());
					last = m.end();
				}
				String tail = src.substring(last);
				literal += tail.length();
				re.append(Pattern.quote(tail)).append("$");
				TEMPLATES.add(new Tpl(Pattern.compile(re.toString(), Pattern.DOTALL), prefix, tokens, de, en, literal));
			}
			// most specific (most fixed text) first
			TEMPLATES.sort((a, b) -> b.weight - a.weight);
			LOG.info("Texts: {} fixed, {} templates", EXACT.size(), TEMPLATES.size());
		} catch (Exception e) {
			LOG.warn("i18n.json not readable: {}", e.toString());
		}
	}

	// menus draw their texts every frame: remember results (also "no translation") per language
	private static final Map<String, String> CACHE_DE = new java.util.concurrent.ConcurrentHashMap<>();
	private static final Map<String, String> CACHE_EN = new java.util.concurrent.ConcurrentHashMap<>();

	/** The text in German or English (unknown texts are returned unchanged). */
	public static String tr(String text, boolean german) {
		if (text == null || text.isEmpty()) {
			return text;
		}
		Map<String, String> cache = german ? CACHE_DE : CACHE_EN;
		String hit = cache.get(text);
		if (hit != null) {
			return hit;
		}
		String result = translate(text, german);
		if (cache.size() > 8192) {
			cache.clear(); // timers, coordinates … make endless variants; start over instead of growing
		}
		cache.put(text, result);
		return result;
	}

	private static String translate(String text, boolean german) {
		load();
		String[] ex = EXACT.get(text);
		if (ex != null) {
			return german ? ex[0] : ex[1];
		}
		for (Tpl t : TEMPLATES) {
			if (!text.startsWith(t.literalPrefix)) {
				continue;
			}
			Matcher m = t.re.matcher(text);
			if (!m.matches()) {
				continue;
			}
			String target = german ? t.de : t.en;
			// values for {n} by number, printf values by order
			Map<String, String> byIndex = new HashMap<>();
			List<String> printf = new ArrayList<>();
			String[] groups = new String[t.tokens.size()];
			for (int i = 0; i < groups.length; i++) {
				groups[i] = m.group(i + 1);
			}
			for (int i = 0; i + 1 < groups.length; i++) {
				if (m.end(i + 1) == m.start(i + 2)) { // adjacent: "{0}{1}" – the regex cannot know where one ends
					String both = groups[i] + groups[i + 1];
					for (int k = 0; k <= both.length(); k++) {
						if (EXACT.containsKey(both.substring(k)) || EXACT.containsKey(both.substring(0, k))) {
							groups[i] = both.substring(0, k);
							groups[i + 1] = both.substring(k);
							break;
						}
					}
				}
			}
			for (int i = 0; i < t.tokens.size(); i++) {
				String value = exactOnly(groups[i], german);
				String tok = t.tokens.get(i);
				if (tok.startsWith("{")) {
					byIndex.put(tok, value);
				} else {
					printf.add(value);
				}
			}
			StringBuilder out = new StringBuilder();
			Matcher pm = PH.matcher(target);
			int last = 0, pi = 0;
			while (pm.find()) {
				out.append(target, last, pm.start());
				String tok = pm.group();
				out.append(tok.startsWith("{") ? byIndex.getOrDefault(tok, tok) : pi < printf.size() ? printf.get(pi++) : tok);
				last = pm.end();
			}
			out.append(target.substring(last));
			return out.toString();
		}
		// decoration around a known text ("▶ Szenen", "§fName", "● Aufnehmen  "): translate the core, keep the decoration
		Matcher deco = DECO.matcher(text);
		if (deco.matches() && !deco.group(2).equals(text)) {
			String core = tr(deco.group(2), german);
			if (!core.equals(deco.group(2))) {
				return deco.group(1) + core + deco.group(3);
			}
		}
		// "name  · state" and similar lists: translate every part on its own
		if (text.contains(" · ")) {
			String[] parts = text.split("(?= · )|(?<= · )");
			StringBuilder sb = new StringBuilder();
			boolean changed = false;
			for (String part : parts) {
				String t = part.equals(" · ") ? part : tr(part.trim().isEmpty() ? part : part, german);
				changed |= !t.equals(part);
				sb.append(t);
			}
			if (changed) {
				return sb.toString();
			}
		}
		if (DEBUG && (text.contains(" ") || text.matches(".*[äöüÄÖÜß].*")) && text.matches("(?s).*[A-Za-zÄÖÜäöü]{3}.*") && MISSES.add(text)) {
			LOG.info("I18N-MISS [{}]", text.replace("\n", "\\n"));
		}
		return text;
	}

	/**
	 * Translates every plain text piece of a chat component (and its hover tooltips) – messages the
	 * server sends to a player arrive in that player's language. Vanilla texts are translation keys
	 * and stay untouched.
	 */
	public static net.minecraft.network.chat.Component translate(net.minecraft.network.chat.Component c, boolean german) {
		if (c == null) {
			return null;
		}
		net.minecraft.network.chat.MutableComponent out;
		if (c.getContents() instanceof net.minecraft.network.chat.contents.PlainTextContents p) {
			out = net.minecraft.network.chat.Component.literal(tr(p.text(), german));
		} else {
			out = net.minecraft.network.chat.MutableComponent.create(c.getContents());
		}
		net.minecraft.network.chat.Style style = c.getStyle();
		if (style.getHoverEvent() instanceof net.minecraft.network.chat.HoverEvent.ShowText(net.minecraft.network.chat.Component hover)) {
			style = style.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(translate(hover, german)));
		}
		out.setStyle(style);
		for (net.minecraft.network.chat.Component sibling : c.getSiblings()) {
			out.append(translate(sibling, german));
		}
		return out;
	}

	/** Inserted values are translated only if they are a known text on their own (e.g. "an"/"aus"). */
	private static String exactOnly(String value, boolean german) {
		String[] ex = EXACT.get(value);
		return ex == null ? value : german ? ex[0] : ex[1];
	}
}
