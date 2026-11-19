package de.behindtheblocks.cinematic.client.director;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import de.behindtheblocks.cinematic.BtbCinematic;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Invisible directing for a director who records at the same time.
 *
 * <ul>
 *   <li><b>Cue list</b> ({@code config/btb_cinematic/director_cues.json}): an ordered list of
 *       commands. GO (numpad Enter) fires the next cue, BACK (numpad 0) steps back — like a
 *       theatre lighting desk. No screen opens.</li>
 *   <li><b>Quick slots</b> numpad 1–9: one command each.</li>
 *   <li><b>Clean feed</b> (F8): keeps the vanilla HUD (hotbar, hand, chat, crosshair) hidden for
 *       a clean recording; cinematic overlays stay visible. The cue monitor is hidden too.</li>
 *   <li>F7 opens the Director screen (use it between takes or on a second client).</li>
 * </ul>
 * All keys can be rebound in Options → Controls → "BTB Cinematic".
 */
public final class DirectorHotkeys {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/hotkeys");
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
			Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, "director"));

	public record Cue(String label, String command) {
	}

	private static KeyMapping openDirector, cleanFeed, go, back, peek;
	/** The cue monitor is only shown briefly after a key press or while peeking, never permanently in the footage. */
	private static boolean peeking;
	private static final long MONITOR_MS = 3500;
	private static final KeyMapping[] SLOTS = new KeyMapping[9];
	private static final List<Cue> CUES = new ArrayList<>();
	private static final Map<Integer, Cue> SLOT_COMMANDS = new HashMap<>();
	private static int pointer;
	private static boolean clean;
	private static String lastFired = "";
	private static long lastFiredAt;
	private static volatile long mutedAt;

	private DirectorHotkeys() {
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(BtbCinematic.MOD_ID).resolve("director_cues.json");
	}

	public static void init() {
		openDirector = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.btb_cinematic.director", InputConstants.KEY_F7, CATEGORY));
		cleanFeed = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.btb_cinematic.clean_feed", InputConstants.KEY_F8, CATEGORY));
		go = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.btb_cinematic.cue_go", InputConstants.KEY_NUMPADENTER, CATEGORY));
		back = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.btb_cinematic.cue_back", InputConstants.KEY_NUMPAD0, CATEGORY));
		peek = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.btb_cinematic.cue_peek", InputConstants.KEY_MULTIPLY, CATEGORY)); // numpad * (numpad + is often taken)
		// Cue commands run silently: their success feedback ("[BTB] Scene started" …) never reaches the
		// chat and therefore never the footage. Errors (red) still come through.
		net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) ->
				overlay || System.currentTimeMillis() - Math.max(lastFiredAt, mutedAt) > CUE_FEEDBACK_MS || isError(message));
		int[] keys = {InputConstants.KEY_NUMPAD1, InputConstants.KEY_NUMPAD2, InputConstants.KEY_NUMPAD3, InputConstants.KEY_NUMPAD4,
				InputConstants.KEY_NUMPAD5, InputConstants.KEY_NUMPAD6, InputConstants.KEY_NUMPAD7, InputConstants.KEY_NUMPAD8,
				InputConstants.KEY_NUMPAD9};
		for (int i = 0; i < 9; i++) {
			SLOTS[i] = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.btb_cinematic.slot_" + (i + 1), keys[i], CATEGORY));
		}
		load();
	}

	/** Reads the cue file, writing a commented example on first start. */
	public static void load() {
		Path f = file();
		try {
			if (!Files.exists(f)) {
				Files.createDirectories(f.getParent());
				Files.writeString(f, new GsonBuilder().setPrettyPrinting().create().toJson(example()), StandardCharsets.UTF_8);
			}
			JsonObject json = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
			CUES.clear();
			SLOT_COMMANDS.clear();
			if (json.has("cues")) {
				for (JsonElement e : json.getAsJsonArray("cues")) {
					JsonObject o = e.getAsJsonObject();
					// "command": server command, or "obs": start|stop|pause|resume|scene:<name>
					String cmd = o.has("obs") ? "obs:" + o.get("obs").getAsString() : strip(o.get("command").getAsString());
					CUES.add(new Cue(o.has("label") ? o.get("label").getAsString() : cmd, cmd));
				}
			}
			if (json.has("slots")) {
				for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("slots").entrySet()) {
					int slot = Integer.parseInt(e.getKey());
					if (slot >= 1 && slot <= 9) {
						if (e.getValue().isJsonObject()) {
							JsonObject so = e.getValue().getAsJsonObject();
							String cmd = so.has("obs") ? "obs:" + so.get("obs").getAsString() : strip(so.get("command").getAsString());
							SLOT_COMMANDS.put(slot, new Cue(so.has("label") ? so.get("label").getAsString() : shortLabel(cmd), cmd));
						} else {
							String cmd = strip(e.getValue().getAsString());
							SLOT_COMMANDS.put(slot, new Cue(shortLabel(cmd), cmd));
						}
					}
				}
			}
			pointer = Math.min(pointer, CUES.size());
		} catch (IOException | RuntimeException e) {
			LOG.warn("director_cues.json could not be read: {}", e.getMessage());
		}
	}

	// ------------------------------------------------------------------ editing from the Director (no files)

	public static List<Cue> cues() {
		return java.util.Collections.unmodifiableList(CUES);
	}

	public static int pointer() {
		return pointer;
	}

	public static void setPointer(int p) {
		pointer = Math.max(0, Math.min(CUES.size(), p));
	}

	public static String slot(int n) {
		Cue c = SLOT_COMMANDS.get(n);
		return c == null ? "" : c.command();
	}

	public static void addCue(String label, String command) {
		String cmd = strip(command.trim());
		if (!cmd.isEmpty()) {
			CUES.add(new Cue(label == null || label.isBlank() ? shortLabel(cmd) : label.trim(), cmd));
			save();
		}
	}

	public static void removeCue(int i) {
		if (i >= 0 && i < CUES.size()) {
			CUES.remove(i);
			pointer = Math.min(pointer, CUES.size());
			save();
		}
	}

	public static void moveCue(int i, int delta) {
		int j = i + delta;
		if (i >= 0 && i < CUES.size() && j >= 0 && j < CUES.size()) {
			java.util.Collections.swap(CUES, i, j);
			save();
		}
	}

	public static void clearCues() {
		CUES.clear();
		pointer = 0;
		save();
	}

	public static void setSlot(int n, String label, String command) {
		String cmd = strip(command.trim());
		if (cmd.isEmpty()) {
			SLOT_COMMANDS.remove(n);
		} else {
			SLOT_COMMANDS.put(n, new Cue(label == null || label.isBlank() ? shortLabel(cmd) : label.trim(), cmd));
		}
		save();
	}

	public static String slotLabel(int n) {
		Cue c = SLOT_COMMANDS.get(n);
		return c == null ? "" : c.label();
	}

	/** Runs a slot (also used by the ▶ test button in the Director). */
	public static void fireSlot(int n) {
		Cue c = SLOT_COMMANDS.get(n);
		if (c != null && Minecraft.getInstance().player != null) {
			fire(Minecraft.getInstance(), c);
		}
	}

	static String shortLabel(String cmd) {
		String l = cmd.startsWith("btb ") ? cmd.substring(4) : cmd;
		if (l.startsWith("obs:")) {
			return "OBS: " + l.substring(4);
		}
		return l.length() > 48 ? l.substring(0, 47) + "…" : l;
	}

	private static void save() {
		JsonObject o = new JsonObject();
		o.addProperty("_help", "Edited in the Director (F7 → Aufnahme). GO = numpad Enter, BACK = numpad 0, slots = numpad 1-9.");
		JsonArray arr = new JsonArray();
		for (Cue c : CUES) {
			JsonObject j = new JsonObject();
			j.addProperty("label", c.label());
			if (c.command().startsWith("obs:")) {
				j.addProperty("obs", c.command().substring(4));
			} else {
				j.addProperty("command", c.command());
			}
			arr.add(j);
		}
		o.add("cues", arr);
		JsonObject slots = new JsonObject();
		SLOT_COMMANDS.entrySet().stream().sorted(Map.Entry.comparingByKey())
				.forEach(e -> {
					JsonObject so = new JsonObject();
					so.addProperty("label", e.getValue().label());
					if (e.getValue().command().startsWith("obs:")) {
						so.addProperty("obs", e.getValue().command().substring(4));
					} else {
						so.addProperty("command", e.getValue().command());
					}
					slots.add(String.valueOf(e.getKey()), so);
				});
		o.add("slots", slots);
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(o), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOG.warn("director_cues.json could not be written: {}", e.getMessage());
		}
	}

	private static String strip(String c) {
		return c.startsWith("/") ? c.substring(1) : c;
	}

	private static JsonObject example() {
		JsonObject o = new JsonObject();
		o.addProperty("_help", "GO = numpad Enter, BACK = numpad 0, slots = numpad 1-9, F8 = clean feed. Reload: /btb director cues reload is client-side: press F7 and reopen, or restart.");
		JsonArray cues = new JsonArray();
		cues.add(cue("Set zurücksetzen", "btb resetall"));
		JsonObject rec = new JsonObject();
		rec.addProperty("label", "OBS: Aufnahme starten");
		rec.addProperty("obs", "start");
		cues.add(rec);
		cues.add(cue("Szene: heart_discovery", "btb scene restart heart_discovery"));
		cues.add(cue("Schnitt: Weißblende", "btb effects fade white 0.5 1 1.5"));
		cues.add(cue("Zerstörung: Ruine", "btb destruction trigger ruin_tower"));
		cues.add(cue("Ende: Schwarzblende", "btb effects fade black 2"));
		JsonObject stop = new JsonObject();
		stop.addProperty("label", "OBS: Aufnahme stoppen");
		stop.addProperty("obs", "stop");
		cues.add(stop);
		o.add("cues", cues);
		JsonObject slots = new JsonObject();
		slots.addProperty("1", "btb effects flash white");
		slots.addProperty("2", "btb effects shake 1.2 1.5");
		slots.addProperty("3", "btb effects letterbox 0.12 1");
		slots.addProperty("4", "btb effects letterbox off 1");
		slots.addProperty("5", "btb scene pause");
		slots.addProperty("6", "btb scene resume");
		slots.addProperty("7", "btb hud show camcorder");
		slots.addProperty("8", "btb hud hide");
		slots.addProperty("9", "btb effects clear 1");
		o.add("slots", slots);
		return o;
	}

	private static JsonObject cue(String label, String command) {
		JsonObject c = new JsonObject();
		c.addProperty("label", label);
		c.addProperty("command", command);
		return c;
	}

	/** Client tick: handle key presses and keep the clean feed clean. */
	public static void tick(Minecraft mc) {
		if (mc.player == null) {
			return;
		}
		// Peek at the cue list: pauses the OBS recording while held (like the Director screen).
		boolean down = peek.isDown();
		if (mc.gui.screen() == null && down != peeking) {
			peeking = down;
			if (down) {
				de.behindtheblocks.cinematic.client.obs.ObsBridge.onMenuOpened();
			} else {
				de.behindtheblocks.cinematic.client.obs.ObsBridge.onMenuClosed();
			}
		}
		while (openDirector.consumeClick()) {
			mc.player.connection.sendCommand("btb director");
		}
		while (cleanFeed.consumeClick()) {
			clean = !clean;
			if (mc.gui.hud.isHidden() != clean) {
				mc.gui.hud.toggle();
			}
		}
		while (go.consumeClick()) {
			if (pointer < CUES.size()) {
				fire(mc, CUES.get(pointer++));
			}
		}
		while (back.consumeClick()) {
			pointer = Math.max(0, pointer - 1);
		}
		for (int i = 0; i < 9; i++) {
			while (SLOTS[i].consumeClick()) {
				Cue c = SLOT_COMMANDS.get(i + 1);
				if (c != null) {
							fire(mc, c);
				}
			}
		}
		// Something (F1, a finished camera shot) may have shown the HUD again: hide it while recording.
		if (clean && !mc.gui.hud.isHidden()) {
			mc.gui.hud.toggle();
		}
	}

	private static void fire(Minecraft mc, Cue c) {
		if (c.command().startsWith("obs:")) {
			de.behindtheblocks.cinematic.client.obs.ObsBridge.command(c.command().substring(4));
		} else {
			mc.player.connection.sendCommand(c.command());
		}
		lastFired = c.label();
		lastFiredAt = System.currentTimeMillis();
	}

	public static void setCleanFeed(boolean value) {
		clean = value;
	}

	public static void toggleCleanFeed() {
		clean = !clean;
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.hud.isHidden() != clean) {
			mc.gui.hud.toggle();
		}
	}

	public static boolean cleanFeed() {
		return clean;
	}

	/** Small monitor (top left) for the director; never drawn in clean feed or with the HUD hidden. */
	private static final long CUE_FEEDBACK_MS = 2000;

	private static boolean isError(net.minecraft.network.chat.Component message) {
		net.minecraft.network.chat.TextColor red = net.minecraft.network.chat.TextColor.fromLegacyFormat(net.minecraft.ChatFormatting.RED);
		return message.visit((style, text) -> red.equals(style.getColor()) && !text.isBlank()
				? java.util.Optional.of(Boolean.TRUE) : java.util.Optional.empty(), net.minecraft.network.chat.Style.EMPTY).isPresent();
	}

	/** Director screen actions: their success messages don't go to chat either. */
	public static void muteFeedback() {
		mutedAt = System.currentTimeMillis();
	}

	public static boolean isPeeking() {
		return peeking;
	}

	public static void renderMonitor(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if ((CUES.isEmpty() && SLOT_COMMANDS.isEmpty()) || mc.gui.screen() != null) {
			return;
		}
		long now = System.currentTimeMillis();
		// Only while the director holds the peek key, and only once OBS has paused: never in the footage.
		if (!peeking || !de.behindtheblocks.cinematic.client.obs.ObsBridge.uiMayShow()) {
			return;
		}
		float a = 1f;
		int bg = ((int) (0x90 * a) << 24), fg = ((int) (0xFF * a) << 24);
		String next = pointer < CUES.size() ? "GO ▶ " + (pointer + 1) + "/" + CUES.size() + "  " + CUES.get(pointer).label() : "Cue-Liste beendet";
		int w = mc.font.width(next) + 8;
		g.fill(2, 2, 2 + w, 14, bg);
		g.text(mc.font, next, 6, 4, fg | 0xFFC080, false);
		int y = 15;
		if (now - lastFiredAt < MONITOR_MS && !lastFired.isEmpty()) {
			String s = "✔ " + lastFired;
			g.fill(2, y, 6 + mc.font.width(s) + 4, y + 12, bg);
			g.text(mc.font, s, 6, y + 2, fg | 0x7CFF7C, false);
			y += 13;
		}
		// Key slots, so the director sees what each numpad key does.
		for (int i = 1; i <= 9; i++) {
			Cue c = SLOT_COMMANDS.get(i);
			if (c != null) {
				String s = "Num " + i + "  " + c.label();
				g.fill(2, y, 6 + mc.font.width(s) + 4, y + 11, bg);
				g.text(mc.font, s, 6, y + 2, fg | 0xD0D0D0, false);
				y += 11;
			}
		}
	}
}
