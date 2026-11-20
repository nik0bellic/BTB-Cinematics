package de.behindtheblocks.cinematic.client.replay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.behindtheblocks.cinematic.BtbCinematic;
import de.behindtheblocks.cinematic.client.i18n.ClientI18n;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * "🎬 BTB Replays": every saved take, reachable from the main menu and the pause menu.
 * Edit (opens the take's world if needed, then the replay editor), play, rename, copy, delete.
 */
public final class ReplayLibraryScreen extends Screen {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/replays");
	private static final int ROW_H = 24;

	public record Take(String name, double seconds, String world, String worldName, long recorded, long bytes) {
	}

	private static final Map<String, Object[]> CACHE = new HashMap<>(); // name → {mtime, Take}

	/** Take to open in the editor once the world is loaded (main menu → world → editor). */
	private static String pendingTake;
	private static int pendingDelay;

	private final Screen parent;
	private List<Take> takes = new ArrayList<>();
	private int page;
	private String renaming;
	private String renameTo = "";
	private String deleteArmed;
	private String status = "";
	private boolean statusError;

	public ReplayLibraryScreen(Screen parent) {
		super(ClientI18n.c("BTB Replays"));
		this.parent = parent;
	}

	/** Buttons in the main menu and the pause menu + opening the editor after a world was loaded for it. */
	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof TitleScreen || screen instanceof PauseScreen) {
				String label = ClientI18n.t("🎬 BTB Replays");
				int bw = client.font.width(label) + 16;
				Button b = Button.builder(ClientI18n.c(label), btn -> client.gui.setScreen(new ReplayLibraryScreen(screen)))
						.tooltip(Tooltip.create(ClientI18n.c("Gespeicherte Replays ansehen, bearbeiten, umbenennen, löschen")))
						.bounds(4, 4, bw, 20).build();
				Screens.getWidgets(screen).add(b);
			}
		});
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			if (pendingTake != null) {
				pendingDelay = 60; // let the world settle, then open the editor
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (pendingTake != null && mc.player != null && mc.level != null && pendingDelay > 0 && --pendingDelay == 0) {
				String t = pendingTake;
				pendingTake = null;
				ReplayEditor.open(t);
			}
		});
	}

	public static Path folder() {
		return FabricLoader.getInstance().getConfigDir().resolve(BtbCinematic.MOD_ID).resolve("replays");
	}

	// ------------------------------------------------------------------ data

	static List<Take> scan() {
		List<Take> out = new ArrayList<>();
		if (!Files.isDirectory(folder())) {
			return out;
		}
		try (Stream<Path> s = Files.list(folder())) {
			for (Path p : s.filter(x -> x.getFileName().toString().endsWith(".replay")).sorted().toList()) {
				String n = p.getFileName().toString();
				out.add(take(n.substring(0, n.length() - 7), p));
			}
		} catch (Exception e) {
			LOG.warn("Replay folder not readable: {}", e.toString());
		}
		// newest first
		out.sort((a, b) -> Long.compare(b.recorded, a.recorded));
		return out;
	}

	private static Take take(String name, Path file) {
		try {
			long mtime = Files.getLastModifiedTime(file).toMillis();
			Object[] c = CACHE.get(name);
			if (c != null && (long) c[0] == mtime) {
				return (Take) c[1];
			}
			double seconds = 0;
			String world = "", worldName = "";
			long recorded = mtime;
			Path info = folder().resolve(name + ".info.json");
			if (Files.exists(info)) {
				JsonObject o = JsonParser.parseString(Files.readString(info)).getAsJsonObject();
				seconds = o.get("length").getAsInt() / 20.0;
				world = o.has("world") ? o.get("world").getAsString() : "";
				worldName = o.has("world_name") ? o.get("world_name").getAsString() : world;
				recorded = o.has("recorded") ? o.get("recorded").getAsLong() : mtime;
			} else {
				// older takes: read the length once from the take itself
				try (Reader r = new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8)) {
					JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
					seconds = o.has("length") ? o.get("length").getAsInt() / 20.0 : 0;
				}
			}
			Take t = new Take(name, seconds, world, worldName, recorded, Files.size(file));
			CACHE.put(name, new Object[] {mtime, t});
			return t;
		} catch (Exception e) {
			return new Take(name, 0, "", "", 0, 0);
		}
	}

	private static final List<String> SUFFIXES = List.of(".replay", ".info.json", ".cam.json");

	private boolean inUse(String name) {
		return name.equals(de.behindtheblocks.cinematic.replay.ReplayManager.current()) || name.equals(ReplayEditor.name);
	}

	private void rename(String from, String to) {
		to = to.trim().replaceAll("[^A-Za-z0-9_\\-]", "_");
		if (to.isEmpty() || to.equals(from)) {
			return;
		}
		if (Files.exists(folder().resolve(to + ".replay"))) {
			note("„" + to + "“ gibt es schon", true);
			return;
		}
		if (inUse(from)) {
			note("„" + from + "“ ist gerade offen – erst im Editor schließen", true);
			return;
		}
		try {
			for (String suf : SUFFIXES) {
				Path a = folder().resolve(from + suf);
				if (Files.exists(a)) {
					Files.move(a, folder().resolve(to + suf));
				}
			}
			CACHE.remove(from);
			note("Umbenannt: " + from + " → " + to, false);
		} catch (Exception e) {
			note("Umbenennen fehlgeschlagen: " + e.getMessage(), true);
		}
	}

	private void copy(String from) {
		String to = from + "_kopie";
		int n = 2;
		while (Files.exists(folder().resolve(to + ".replay"))) {
			to = from + "_kopie" + n++;
		}
		try {
			for (String suf : SUFFIXES) {
				Path a = folder().resolve(from + suf);
				if (Files.exists(a)) {
					Files.copy(a, folder().resolve(to + suf), StandardCopyOption.REPLACE_EXISTING);
				}
			}
			note("Kopie gespeichert: " + to, false);
		} catch (Exception e) {
			note("Kopieren fehlgeschlagen: " + e.getMessage(), true);
		}
	}

	private void delete(String name) {
		if (inUse(name)) {
			note("„" + name + "“ ist gerade offen – erst im Editor schließen", true);
			return;
		}
		try {
			for (String suf : SUFFIXES) {
				Files.deleteIfExists(folder().resolve(name + suf));
			}
			CACHE.remove(name);
			note("Gelöscht: " + name, false);
		} catch (Exception e) {
			note("Löschen fehlgeschlagen: " + e.getMessage(), true);
		}
	}

	/** Opens the editor: right away in the world, otherwise first the take's world. */
	private void edit(Take t) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) {
			if (mc.getSingleplayerServer() == null) {
				note("Der Replay-Editor geht nur im Einzelspieler bzw. als Host einer LAN-Welt", true);
				return;
			}
			mc.gui.setScreen(null);
			ReplayEditor.open(t.name);
			return;
		}
		pendingTake = t.name;
		if (!t.world.isEmpty() && mc.getLevelSource().levelExists(t.world)) {
			note("Lade Welt „" + t.worldName + "“ …", false);
			mc.createWorldOpenFlows().openWorld(t.world, () -> {
				pendingTake = null;
				mc.gui.setScreen(this);
			});
		} else {
			// world unknown (older take) or gone: let the player pick it
			mc.gui.setScreen(new net.minecraft.client.gui.screens.worldselection.SelectWorldScreen(this));
		}
	}

	private void play(Take t) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		mc.gui.setScreen(null);
		mc.player.connection.sendCommand("btb replay play " + t.name);
	}

	private void note(String text, boolean error) {
		status = text;
		statusError = error;
		takes = scan();
		rebuildWidgets();
	}

	// ------------------------------------------------------------------ screen

	@Override
	protected void init() {
		if (takes.isEmpty()) {
			takes = scan();
		}
		boolean inWorld = minecraft.level != null;
		int x0 = Math.max(10, width / 2 - 260), w = Math.min(520, width - 20);
		int top = 40, bottom = height - 34;
		int perPage = Math.max(1, (bottom - top) / ROW_H);
		int pages = Math.max(1, (takes.size() + perPage - 1) / perPage);
		page = Math.min(page, pages - 1);
		rows.clear();
		renameBox = null;
		int y = top;
		for (int i = page * perPage; i < Math.min(takes.size(), (page + 1) * perPage); i++) {
			Take t = takes.get(i);
			int bx = x0 + w;
			if (t.name.equals(renaming)) {
				bx = button(bx, y, "Abbrechen", null, () -> {
					renaming = null;
					rebuildWidgets();
				});
				bx = button(bx, y, "✔", "Neuen Namen übernehmen", () -> {
					String from = renaming;
					renaming = null;
					rename(from, renameTo);
				});
				EditBox box = new EditBox(font, x0 + 4, y + 2, Math.max(60, bx - x0 - 10), 16, ClientI18n.c("Name"));
				box.setMaxLength(40);
				box.setValue(renameTo);
				box.setResponder(v -> renameTo = v);
				addRenderableWidget(box);
				renameBox = box;
				focusRename = true; // the click that opened it would take the focus back – focus on the next tick
				y += ROW_H;
				continue;
			}
			boolean armed = t.name.equals(deleteArmed);
			bx = button(bx, y, armed ? "Sicher?" : "✕", armed ? "Nochmal klicken = endgültig löschen" : "Replay löschen", () -> {
				if (t.name.equals(deleteArmed)) {
					deleteArmed = null;
					delete(t.name);
				} else {
					deleteArmed = t.name;
					rebuildWidgets();
				}
			});
			bx = button(bx, y, "⧉", "Kopie speichern (z. B. um verschiedene Kamera-Fassungen zu behalten)", () -> copy(t.name));
			bx = button(bx, y, "✎", "Umbenennen", () -> {
				renaming = t.name;
				renameTo = t.name;
				rebuildWidgets();
			});
			if (inWorld) {
				bx = button(bx, y, "▶", "Abspielen", () -> play(t));
			}
			bx = button(bx, y, "✂ Bearbeiten", inWorld ? "Im Replay-Editor öffnen" : t.world.isEmpty()
					? "Welt auswählen, danach öffnet sich der Replay-Editor" : "Lädt die Welt „" + t.worldName + "“ und öffnet den Replay-Editor", () -> edit(t));
			rows.add(new Object[] {t, x0, y, bx - x0 - 6});
			y += ROW_H;
		}
		if (pages > 1) {
			addRenderableWidget(Button.builder(ClientI18n.c("◀"), b -> {
				page = Math.max(0, page - 1);
				rebuildWidgets();
			}).bounds(x0, height - 28, 20, 20).build());
			addRenderableWidget(Button.builder(ClientI18n.c("▶"), b -> {
				page = Math.min(pages - 1, page + 1);
				rebuildWidgets();
			}).bounds(x0 + 24, height - 28, 20, 20).build());
			pageLabel = ClientI18n.t("Seite " + (page + 1) + "/" + pages);
		} else {
			pageLabel = null;
		}
		addRenderableWidget(Button.builder(ClientI18n.c("↻"), b -> {
			takes = scan();
			rebuildWidgets();
		}).tooltip(Tooltip.create(ClientI18n.c("Liste neu einlesen"))).bounds(x0 + w - 110, height - 28, 20, 20).build());
		addRenderableWidget(Button.builder(ClientI18n.c("Fertig"), b -> onClose()).bounds(x0 + w - 86, height - 28, 86, 20).build());
	}

	private final List<Object[]> rows = new ArrayList<>();
	private EditBox renameBox;
	private boolean focusRename;

	@Override
	public void tick() {
		super.tick();
		if (focusRename && renameBox != null) {
			focusRename = false;
			setFocused(renameBox);
			renameBox.setFocused(true);
			renameBox.moveCursorToEnd(false);
		}
	}
	private String pageLabel;

	private int button(int right, int y, String label, String tip, Runnable r) {
		int bw = Math.max(20, font.width(ClientI18n.t(label)) + 12);
		Button.Builder b = Button.builder(ClientI18n.c(label), btn -> r.run()).bounds(right - bw, y + 1, bw, 18);
		if (tip != null) {
			b.tooltip(Tooltip.create(ClientI18n.c(tip)));
		}
		addRenderableWidget(b.build());
		return right - bw - 3;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.fill(0, 0, width, height, 0xF6101014);
		int x0 = Math.max(10, width / 2 - 260), w = Math.min(520, width - 20);
		g.fill(x0, 30, x0 + w, 31, 0xFFFF7A1A);
		g.text(font, ClientI18n.t("🎬 BTB Replays"), x0, 14, 0xFFFFE0C0, true);
		String sub = ClientI18n.t(takes.size() + " Replay(s) · gespeichert in config/btb_cinematic/replays");
		g.text(font, sub, x0 + w - font.width(sub), 16, 0xFF888888, false);
		SimpleDateFormat df = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT);
		for (Object[] r : rows) {
			Take t = (Take) r[0];
			int x = (int) r[1], y = (int) r[2], lw = (int) r[3];
			g.fill(x - 2, y, x0 + w, y + ROW_H - 2, 0x22FFFFFF);
			String line1 = t.name + "  ·  " + String.format(Locale.ROOT, "%.1f s", t.seconds);
			String line2 = (t.worldName.isEmpty() ? ClientI18n.t("Welt unbekannt") : ClientI18n.t("Welt: " + t.worldName))
					+ "  ·  " + (t.recorded > 0 ? df.format(new Date(t.recorded)) : "") + "  ·  " + (t.bytes / 1024) + " KB";
			g.text(font, clip(line1, lw), x + 4, y + 2, 0xFFFF9070, false);
			g.text(font, clip(line2, lw), x + 4, y + 12, 0xFF999999, false);
		}
		if (takes.isEmpty()) {
			g.text(font, ClientI18n.t("Noch keine Replays. In einer Welt: Regiepult (F7) → Replay → „● Aufnehmen“."), x0, 46, 0xFF888888, false);
		}
		if (pageLabel != null) {
			g.text(font, pageLabel, x0 + 50, height - 22, 0xFFAAAAAA, false);
		}
		if (!status.isEmpty()) {
			String s = clip(ClientI18n.t(status), w - 200);
			g.text(font, s, x0 + (pageLabel != null ? 110 : 0), height - 22, statusError ? 0xFFFF8080 : 0xFF90E090, false);
		}
		super.extractRenderState(g, mouseX, mouseY, a);
	}

	private String clip(String s, int w) {
		return font.width(s) > w ? font.plainSubstrByWidth(s, Math.max(10, w - font.width("…"))) + "…" : s;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// own dark background in extractRenderState
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent e) {
		if (renaming != null && (e.key() == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN
				|| e.key() == com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER)) {
			String from = renaming;
			renaming = null;
			rename(from, renameTo);
			return true;
		}
		if (renaming != null && e.key() == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE) {
			renaming = null;
			rebuildWidgets();
			return true;
		}
		return super.keyPressed(e);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
