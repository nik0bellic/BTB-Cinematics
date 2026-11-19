package de.behindtheblocks.cinematic.client.director;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import de.behindtheblocks.cinematic.BtbCinematic;
import de.behindtheblocks.cinematic.client.i18n.ClientI18n;
import de.behindtheblocks.cinematic.client.network.ClientCueDispatcher;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client side of the studio tools:
 * <ul>
 *   <li>menu preferences (beginner / pro view)</li>
 *   <li>the storyboard: shots as cards with picture, note and length, shot one after the other</li>
 *   <li>the prompter: actors see "in 3 … 2 … 1: walk left" (their screens are never the footage)</li>
 * </ul>
 */
public final class StudioClient {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/studio");

	public record Shot(String id, String title, String note, double seconds, String command, boolean done) {
	}

	private static final List<Shot> SHOTS = new ArrayList<>();
	private static int current = -1;
	private static boolean pro;
	private static boolean allTabs;
	private static boolean loaded;
	private static final Map<String, Identifier> THUMBS = new HashMap<>();

	// capture of a storyboard picture: after the menu closed, a few frames later
	private static String captureFor;
	private static int captureDelay;

	// prompter / role note
	private static String promptText;
	private static long promptAt;
	private static double promptLead;
	private static String roleNote;
	private static long roleAt;

	private StudioClient() {
	}

	public static void init() {
		ClientCueDispatcher.register("btb_prompt", (scene, tick, data) -> {
			promptText = data.get("text").getAsString();
			promptLead = data.get("seconds").getAsDouble();
			promptAt = System.currentTimeMillis();
		});
		ClientCueDispatcher.register("btb_role", (scene, tick, data) -> {
			String r = data.get("role").getAsString();
			roleNote = r.isEmpty() ? "Keine Rolle mehr" : "Deine Rolle: " + switch (r) {
				case "regie" -> "Regie";
				case "kamera" -> "Kamera (Kamerafahrten laufen bei dir)";
				case "darsteller" -> "Darsteller (Hinweise erscheinen hier oben)";
				default -> r;
			};
			roleAt = System.currentTimeMillis();
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (captureFor != null && mc.gui.screen() == null && --captureDelay <= 0) {
				String id = captureFor;
				captureFor = null;
				capture(id);
			}
		});
	}

	// ------------------------------------------------------------------ preferences

	private static Path prefsFile() {
		return FabricLoader.getInstance().getConfigDir().resolve(BtbCinematic.MOD_ID).resolve("director_ui.json");
	}

	private static Path boardFile() {
		return FabricLoader.getInstance().getConfigDir().resolve(BtbCinematic.MOD_ID).resolve("storyboard.json");
	}

	private static Path thumbDir() {
		return FabricLoader.getInstance().getConfigDir().resolve(BtbCinematic.MOD_ID).resolve("storyboard");
	}

	private static void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			if (Files.exists(prefsFile())) {
				JsonObject o = JsonParser.parseString(Files.readString(prefsFile())).getAsJsonObject();
				pro = o.has("pro") && o.get("pro").getAsBoolean();
				allTabs = o.has("all_tabs") && o.get("all_tabs").getAsBoolean();
			}
			if (Files.exists(boardFile())) {
				JsonObject o = JsonParser.parseString(Files.readString(boardFile(), StandardCharsets.UTF_8)).getAsJsonObject();
				for (JsonElement el : o.getAsJsonArray("shots")) {
					JsonObject s = el.getAsJsonObject();
					SHOTS.add(new Shot(s.get("id").getAsString(), s.get("title").getAsString(), s.has("note") ? s.get("note").getAsString() : "",
							s.has("seconds") ? s.get("seconds").getAsDouble() : 5, s.has("command") ? s.get("command").getAsString() : "",
							s.has("done") && s.get("done").getAsBoolean()));
				}
				current = o.has("current") ? o.get("current").getAsInt() : -1;
			}
		} catch (Exception e) {
			LOG.warn("Studio settings not readable: {}", e.toString());
		}
	}

	private static void save() {
		try {
			Files.createDirectories(prefsFile().getParent());
			JsonObject p = new JsonObject();
			p.addProperty("pro", pro);
			p.addProperty("all_tabs", allTabs);
			Files.writeString(prefsFile(), p.toString());
			JsonObject b = new JsonObject();
			JsonArray a = new JsonArray();
			for (Shot s : SHOTS) {
				JsonObject o = new JsonObject();
				o.addProperty("id", s.id);
				o.addProperty("title", s.title);
				o.addProperty("note", s.note);
				o.addProperty("seconds", s.seconds);
				o.addProperty("command", s.command);
				o.addProperty("done", s.done);
				a.add(o);
			}
			b.add("shots", a);
			b.addProperty("current", current);
			Files.writeString(boardFile(), new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(b), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOG.warn("Studio settings not saved: {}", e.toString());
		}
	}

	public static boolean pro() {
		load();
		return pro;
	}

	public static void setPro(boolean on) {
		load();
		pro = on;
		save();
	}

	/** true: every page is its own tab on the left (like before); false: 8 tabs with pages. */
	public static boolean allTabs() {
		load();
		return allTabs;
	}

	public static void setAllTabs(boolean on) {
		load();
		allTabs = on;
		save();
	}

	// ------------------------------------------------------------------ storyboard

	public static List<Shot> shots() {
		load();
		return List.copyOf(SHOTS);
	}

	public static int current() {
		load();
		return current;
	}

	public static void add(String title, String note, double seconds, String command) {
		load();
		SHOTS.add(new Shot("s" + Long.toString(System.currentTimeMillis(), 36), title, note, seconds, command, false));
		save();
	}

	public static void update(int i, String title, String note, double seconds, String command) {
		load();
		if (i >= 0 && i < SHOTS.size()) {
			Shot s = SHOTS.get(i);
			SHOTS.set(i, new Shot(s.id, title, note, seconds, command, s.done));
			save();
		}
	}

	public static void remove(int i) {
		load();
		if (i >= 0 && i < SHOTS.size()) {
			SHOTS.remove(i);
			if (current >= SHOTS.size()) {
				current = SHOTS.isEmpty() ? -1 : SHOTS.size() - 1;
			}
			save();
		}
	}

	public static void move(int i, int d) {
		load();
		int j = i + d;
		if (i >= 0 && j >= 0 && i < SHOTS.size() && j < SHOTS.size()) {
			Shot s = SHOTS.remove(i);
			SHOTS.add(j, s);
			save();
		}
	}

	public static void toggleDone(int i) {
		load();
		if (i >= 0 && i < SHOTS.size()) {
			Shot s = SHOTS.get(i);
			SHOTS.set(i, new Shot(s.id, s.title, s.note, s.seconds, s.command, !s.done));
			save();
		}
	}

	/** Start shooting at a shot (-1 = stop). Runs the shot's command, if it has one. */
	public static void shoot(int i) {
		load();
		current = i < SHOTS.size() ? i : -1;
		save();
		if (current >= 0) {
			Shot s = SHOTS.get(current);
			if (!s.command.isBlank() && Minecraft.getInstance().player != null) {
				DirectorHotkeys.muteFeedback();
				String c = s.command.startsWith("/") ? s.command.substring(1) : s.command;
				Minecraft.getInstance().player.connection.sendCommand(c);
			}
		}
	}

	/** The current shot is in the can: tick it and go to the next one that is not done yet. */
	public static void next() {
		load();
		if (current >= 0 && current < SHOTS.size()) {
			Shot s = SHOTS.get(current);
			SHOTS.set(current, new Shot(s.id, s.title, s.note, s.seconds, s.command, true));
		}
		int n = -1;
		for (int k = Math.max(0, current + 1); k < SHOTS.size(); k++) {
			if (!SHOTS.get(k).done) {
				n = k;
				break;
			}
		}
		current = n;
		save();
	}

	public static void reset() {
		load();
		for (int i = 0; i < SHOTS.size(); i++) {
			Shot s = SHOTS.get(i);
			SHOTS.set(i, new Shot(s.id, s.title, s.note, s.seconds, s.command, false));
		}
		current = -1;
		save();
	}

	/** Takes the picture for a shot once the menu is closed (so the menu is not in it). */
	public static void captureLater(int i) {
		load();
		if (i >= 0 && i < SHOTS.size()) {
			captureFor = SHOTS.get(i).id;
			captureDelay = 3;
			Minecraft.getInstance().gui.setScreen(null);
		}
	}

	private static void capture(String id) {
		Minecraft mc = Minecraft.getInstance();
		Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), (NativeImage img) -> {
			try (img) {
				int tw = 160, th = 90;
				NativeImage small = new NativeImage(tw, th, false);
				double sx = img.getWidth() / (double) tw, sy = img.getHeight() / (double) th;
				for (int y = 0; y < th; y++) {
					for (int x = 0; x < tw; x++) {
						small.setPixel(x, y, img.getPixel((int) (x * sx), (int) (y * sy)));
					}
				}
				Files.createDirectories(thumbDir());
				small.writeToFile(thumbDir().resolve(id + ".png"));
				small.close();
				mc.execute(() -> {
					THUMBS.remove(id);
					if (mc.player != null) {
						mc.player.connection.sendCommand("btb director"); // back into the menu
					}
				});
			} catch (IOException e) {
				LOG.warn("Storyboard picture not saved: {}", e.toString());
			}
		});
	}

	/** Texture of a shot's picture, or null if it has none. */
	public static Identifier thumb(String id) {
		if (THUMBS.containsKey(id)) {
			return THUMBS.get(id);
		}
		Identifier tex = null;
		Path f = thumbDir().resolve(id + ".png");
		if (Files.exists(f)) {
			try (InputStream in = Files.newInputStream(f)) {
				NativeImage img = NativeImage.read(in);
				tex = Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, "storyboard/" + id.toLowerCase(java.util.Locale.ROOT));
				Minecraft.getInstance().getTextureManager().register(tex, new DynamicTexture(() -> "btb storyboard " + id, img));
			} catch (IOException e) {
				LOG.warn("Storyboard picture not readable: {}", e.toString());
			}
		}
		THUMBS.put(id, tex);
		return tex;
	}

	public static void drawThumb(GuiGraphicsExtractor g, String id, int x, int y, int w, int h) {
		Identifier tex = thumb(id);
		if (tex == null) {
			g.fill(x, y, x + w, y + h, 0x40FFFFFF);
			g.text(Minecraft.getInstance().font, "?", x + w / 2 - 2, y + h / 2 - 4, 0xFF888888, false);
			return;
		}
		g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, 0, 0, w, h, w, h, w, h, 0xFFFFFFFF);
	}

	// ------------------------------------------------------------------ overlay

	/** Prompter + role note (actors' screens) and the current shot while the director peeks. */
	public static void render(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		var font = mc.font;
		int w = g.guiWidth();
		long now = System.currentTimeMillis();
		if (promptText != null) {
			double el = (now - promptAt) / 1000.0;
			double left = promptLead - el;
			String line;
			int color;
			if (left > 0) {
				line = "In " + (int) Math.ceil(left) + " …  " + promptText;
				color = 0xFFFFD25A;
			} else if (left > -3) {
				line = "▶ " + promptText;
				color = 0xFF7CFF7C;
			} else {
				promptText = null;
				line = null;
				color = 0;
			}
			if (line != null) {
				String t = ClientI18n.t(line);
				int tw = font.width(t);
				g.fill(w / 2 - tw / 2 - 8, 20, w / 2 + tw / 2 + 8, 36, 0xB0000000);
				g.text(font, t, w / 2 - tw / 2, 24, color, false);
			}
		}
		if (roleNote != null && now - roleAt < 5000) {
			String t = ClientI18n.t(roleNote);
			int tw = font.width(t);
			g.fill(w / 2 - tw / 2 - 8, 40, w / 2 + tw / 2 + 8, 54, 0xA0000000);
			g.text(font, t, w / 2 - tw / 2, 43, 0xFF9CE0FF, false);
		}
		// the shot being filmed: only while the peek key (numpad *) is held, like the cue monitor – never in the footage
		if (DirectorHotkeys.isPeeking() && de.behindtheblocks.cinematic.client.obs.ObsBridge.uiMayShow()) {
			load();
			if (current >= 0 && current < SHOTS.size()) {
				Shot s = SHOTS.get(current);
				String t = ClientI18n.t("🎬 Shot " + (current + 1) + "/" + SHOTS.size() + ": " + s.title + " · " + fmt(s.seconds) + " s")
						+ (s.note.isBlank() ? "" : "  – " + s.note);
				int tw = font.width(t);
				g.fill(w - tw - 12, 2, w - 2, 16, 0x90000000);
				g.text(font, t, w - tw - 7, 5, 0xFFFFC080, false);
			}
		}
	}

	static String fmt(double d) {
		return DirectorScreen.fmt(d);
	}
}
