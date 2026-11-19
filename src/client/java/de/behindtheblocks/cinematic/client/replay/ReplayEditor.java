package de.behindtheblocks.cinematic.client.replay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import de.behindtheblocks.cinematic.BtbCinematic;
import de.behindtheblocks.cinematic.camera.CameraPose;
import de.behindtheblocks.cinematic.client.export.ExportManager;
import de.behindtheblocks.cinematic.client.network.ClientCueDispatcher;
import de.behindtheblocks.cinematic.replay.ReplayManager;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Replay editor session (ReplayMod-like): a recorded take is loaded paused, scrubbed on a timeline,
 * camera keyframes are set from free flight, and a range is exported frame-exact (1080p–4K, 24–60 FPS).
 *
 * <p>Single player / LAN host only – the take, the pause and the export live in the integrated server.
 * Keyframes and export settings are stored next to the take: {@code config/btb_cinematic/replays/<name>.cam.json}.
 */
public final class ReplayEditor {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** How the camera moves from a keyframe to the next one. */
	public enum Mode {
		PATH("Fahrt", 0xFFFFC040), CUT("Schnitt", 0xFFFF5050), LOOK("Fahrt+Blick", 0xFF60D0FF), FREE("Frei", 0xFF909090);

		public final String label;
		public final int color;

		Mode(String label, int color) {
			this.label = label;
			this.color = color;
		}

		public Mode next() {
			return values()[(ordinal() + 1) % values().length];
		}
	}

	public static final class Keyframe {
		public double t; // seconds in the take
		public double x, y, z;
		public float yaw, pitch, roll, fov;
		public Mode mode = Mode.PATH;
	}

	public static final String[] RESOLUTIONS = {"1080p", "1440p", "4k"};
	public static final int[] FPS = {24, 30, 60};

	// session
	static String name;
	static JsonObject header;
	static final List<Keyframe> KEYS = new ArrayList<>();
	static int selected = -1;
	/** true: the camera flies the keyframe path; false: free flight to look for angles. */
	static boolean cameraOn;
	static double in, out = -1;
	static String exportName = "film";
	static int res = 2, fps = 2;
	private static boolean exporting;

	// last message for the editor (errors in red), shown in the panel and the flight HUD
	static String status;
	static boolean statusError;
	static long statusAt;

	/** Shows a message in the editor (also used by the export). */
	public static void note(String text, boolean error) {
		status = text;
		statusError = error;
		statusAt = System.currentTimeMillis();
	}

	static String currentStatus() {
		return status != null && System.currentTimeMillis() - statusAt < (statusError ? 12_000 : 6_000) ? status : null;
	}

	private static KeyMapping openKey, keyframeKey, playKey, backKey, fwdKey, camKey;

	private ReplayEditor() {
	}

	public static void init() {
		KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, "replay"));
		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.btb_cinematic.replay_editor", InputConstants.KEY_F6, cat));
		keyframeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.btb_cinematic.replay_keyframe", InputConstants.KEY_K, cat));
		playKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.btb_cinematic.replay_play", InputConstants.KEY_UP, cat));
		backKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.btb_cinematic.replay_back", InputConstants.KEY_LEFT, cat));
		fwdKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.btb_cinematic.replay_forward", InputConstants.KEY_RIGHT, cat));
		camKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.btb_cinematic.replay_camera", InputConstants.KEY_J, cat));
		// replay/export feedback from the server (e.g. "Replay nicht gefunden") also shows in the editor
		net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			String t = message.getString();
			if (!overlay && active() && t.startsWith("[BTB")) {
				net.minecraft.network.chat.TextColor red = net.minecraft.network.chat.TextColor.fromLegacyFormat(net.minecraft.ChatFormatting.RED);
				boolean err = message.visit((style, txt) -> red.equals(style.getColor()) && !txt.isBlank()
						? java.util.Optional.of(Boolean.TRUE) : java.util.Optional.empty(), net.minecraft.network.chat.Style.EMPTY).isPresent();
				note(t.replaceFirst("^\\[BTB[^\\]]*\\]\\s*", ""), err);
			}
		});
		ClientCueDispatcher.register("replay_editor", (scene, tick, data) ->
				open(data.has("name") ? data.get("name").getAsString() : null));
	}

	public static boolean active() {
		return name != null;
	}

	// ------------------------------------------------------------------ session

	/** Opens the editor for a take (null: the one playing, the last one, or the first saved one). */
	public static void open(String take) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getSingleplayerServer() == null) {
			say("Der Replay-Editor geht nur im Einzelspieler bzw. als Host einer LAN-Welt.");
			return;
		}
		List<String> all = ReplayManager.list();
		if (take == null) {
			take = ReplayManager.current() != null ? ReplayManager.current() : name != null ? name : all.isEmpty() ? null : all.get(all.size() - 1);
		}
		if (take == null || !all.contains(take)) {
			say(take == null ? "Noch kein Replay – erst aufnehmen: /btb replay record take … /btb replay stop" : "Replay „" + take + "“ nicht gefunden");
			return;
		}
		if (!take.equals(name)) {
			load(take);
		}
		if (!take.equals(ReplayManager.current())) {
			mc.player.connection.sendCommand("btb replay edit " + take);
		}
		mc.gui.setScreen(new ReplayEditorScreen());
	}

	static void switchTake(int dir) {
		List<String> all = ReplayManager.list();
		if (all.isEmpty()) {
			return;
		}
		int i = Math.max(0, all.indexOf(name));
		String n = all.get(Math.floorMod(i + dir, all.size()));
		save();
		load(n);
		Minecraft.getInstance().player.connection.sendCommand("btb replay edit " + n);
	}

	/** Leaves the editor: the take stops, the actors get their game mode back. */
	static void close() {
		save();
		name = null;
		cameraOn = false;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && ReplayManager.current() != null) {
			mc.player.connection.sendCommand("btb replay stop");
		}
		mc.gui.setScreen(null);
	}

	private static Path file(String take) {
		return FabricLoader.getInstance().getConfigDir().resolve(BtbCinematic.MOD_ID).resolve("replays").resolve(take + ".cam.json");
	}

	private static void load(String take) {
		name = take;
		header = ReplayManager.header(take);
		if (header == null) {
			note("Replay „" + take + "“ ist beschädigt oder nicht lesbar (config/btb_cinematic/replays/" + take + ".replay)", true);
		}
		KEYS.clear();
		selected = -1;
		in = 0;
		out = -1;
		exportName = take;
		Path f = file(take);
		if (Files.exists(f)) {
			try {
				JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
				for (var el : o.getAsJsonArray("keyframes")) {
					JsonObject k = el.getAsJsonObject();
					Keyframe kf = new Keyframe();
					kf.t = k.get("t").getAsDouble();
					kf.x = k.get("x").getAsDouble();
					kf.y = k.get("y").getAsDouble();
					kf.z = k.get("z").getAsDouble();
					kf.yaw = k.get("yaw").getAsFloat();
					kf.pitch = k.get("pitch").getAsFloat();
					kf.roll = k.has("roll") ? k.get("roll").getAsFloat() : 0;
					kf.fov = k.has("fov") ? k.get("fov").getAsFloat() : 0;
					kf.mode = Mode.valueOf(k.has("mode") ? k.get("mode").getAsString() : "PATH");
					KEYS.add(kf);
				}
				in = o.has("in") ? o.get("in").getAsDouble() : 0;
				out = o.has("out") ? o.get("out").getAsDouble() : -1;
				if (o.has("export_name")) {
					exportName = o.get("export_name").getAsString();
				}
				res = o.has("resolution") ? Math.max(0, List.of(RESOLUTIONS).indexOf(o.get("resolution").getAsString())) : 2;
				int f2 = o.has("fps") ? o.get("fps").getAsInt() : 60;
				fps = f2 == 24 ? 0 : f2 == 30 ? 1 : 2;
			} catch (Exception e) {
				say("Keyframes von „" + take + "“ nicht lesbar: " + e.getMessage());
			}
		}
		sort();
	}

	static void save() {
		if (name == null) {
			return;
		}
		JsonObject o = new JsonObject();
		JsonArray arr = new JsonArray();
		for (Keyframe k : KEYS) {
			JsonObject j = new JsonObject();
			j.addProperty("t", Math.round(k.t * 100) / 100.0);
			j.addProperty("x", k.x);
			j.addProperty("y", k.y);
			j.addProperty("z", k.z);
			j.addProperty("yaw", k.yaw);
			j.addProperty("pitch", k.pitch);
			j.addProperty("roll", k.roll);
			j.addProperty("fov", k.fov);
			j.addProperty("mode", k.mode.name());
			arr.add(j);
		}
		o.add("keyframes", arr);
		o.addProperty("in", in);
		o.addProperty("out", out);
		o.addProperty("export_name", exportName);
		o.addProperty("resolution", RESOLUTIONS[res]);
		o.addProperty("fps", FPS[fps]);
		try {
			Files.createDirectories(file(name).getParent());
			Files.writeString(file(name), GSON.toJson(o), StandardCharsets.UTF_8);
		} catch (IOException e) {
			say("Speichern fehlgeschlagen: " + e.getMessage());
		}
	}

	static void sort() {
		Keyframe sel = selected >= 0 && selected < KEYS.size() ? KEYS.get(selected) : null;
		KEYS.sort(Comparator.comparingDouble(k -> k.t));
		selected = sel == null ? -1 : KEYS.indexOf(sel);
	}

	// ------------------------------------------------------------------ time / transport

	public static double length() {
		return header == null ? Math.max(1, ReplayManager.currentLength()) / 20.0 : header.get("length").getAsInt() / 20.0;
	}

	/** Moment of the take that is on screen now, in seconds. */
	public static double time(float partial) {
		if (!name.equals(ReplayManager.current())) {
			return 0;
		}
		int tick = ReplayManager.currentTick();
		double t = tick - 1 + (ReplayManager.isPaused() || exporting ? (ExportManager.active() ? ExportManager.partialForFrame() : 0) : partial);
		return Math.max(0, Math.min(length(), t / 20.0));
	}

	static double outPoint() {
		return out < 0 ? length() : out;
	}

	static void cmd(String c) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.connection.sendCommand(c);
		}
	}

	static void togglePlay() {
		if (!name.equals(ReplayManager.current())) {
			cmd("btb replay edit " + name);
			return;
		}
		if (ReplayManager.isPaused() && ReplayManager.currentTick() >= ReplayManager.currentLength()) {
			seek(0); // at the end: start again
		}
		cmd("btb replay pause");
	}

	static void seek(double seconds) {
		seconds = Math.max(0, Math.min(length(), seconds));
		if (!name.equals(ReplayManager.current())) {
			cmd("btb replay edit " + name);
		}
		cmd(String.format(Locale.ROOT, "btb replay seek %.2f", seconds));
	}

	static void setSpeed(float s) {
		cmd("btb replay speed " + s);
	}

	// ------------------------------------------------------------------ keyframes

	/** Adds (or, at the same moment, replaces) a keyframe with the current view. */
	static void addKeyframe() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		double t = time(0);
		Keyframe k = null;
		for (Keyframe e : KEYS) {
			if (Math.abs(e.t - t) < 0.05) {
				k = e;
			}
		}
		if (k == null) {
			k = new Keyframe();
			k.t = t;
			k.mode = KEYS.isEmpty() ? Mode.PATH : KEYS.get(Math.max(0, indexBefore(t))).mode;
			KEYS.add(k);
		}
		takeView(k);
		selected = KEYS.indexOf(k);
		sort();
		save();
		mc.gui.hud.setOverlayMessage(Component.literal(String.format(Locale.ROOT, "◆ Keyframe %d bei %s", selected + 1, clock(t))), false);
	}

	static void takeView(Keyframe k) {
		Minecraft mc = Minecraft.getInstance();
		var cam = mc.gameRenderer.mainCamera();
		Vec3 p = cam.position();
		k.x = p.x;
		k.y = p.y;
		k.z = p.z;
		k.yaw = cam.yRot();
		k.pitch = cam.xRot();
		if (k.fov <= 0) {
			k.fov = mc.options.fov().get().floatValue();
		}
	}

	private static int indexBefore(double t) {
		int i = -1;
		for (int j = 0; j < KEYS.size(); j++) {
			if (KEYS.get(j).t <= t) {
				i = j;
			}
		}
		return i;
	}

	/** Camera for this frame from the keyframes, or null (free flight / nothing set). */
	public static CameraPose pose(float partial) {
		if (name == null || !cameraOn || KEYS.isEmpty() || !name.equals(ReplayManager.current())) {
			return null;
		}
		double t = time(partial);
		int i = indexBefore(t);
		if (i < 0) {
			return toPose(KEYS.get(0));
		}
		Keyframe a = KEYS.get(i);
		if (i == KEYS.size() - 1) {
			return a.mode == Mode.FREE ? null : toPose(a);
		}
		Keyframe b = KEYS.get(i + 1);
		switch (a.mode) {
			case FREE:
				return null;
			case CUT:
				return toPose(a);
			default:
				break;
		}
		Keyframe p0 = i > 0 ? KEYS.get(i - 1) : a, p3 = i + 2 < KEYS.size() ? KEYS.get(i + 2) : b;
		double u = b.t > a.t ? (t - a.t) / (b.t - a.t) : 1;
		u = Math.max(0, Math.min(1, u));
		double x = cr(p0.x, a.x, b.x, p3.x, u), y = cr(p0.y, a.y, b.y, p3.y, u), z = cr(p0.z, a.z, b.z, p3.z, u);
		// angles: unwrap so the camera turns the short way round
		float ya = a.yaw, yb = ya + wrap(b.yaw - ya), y0 = ya + wrap(p0.yaw - ya), y3 = yb + wrap(p3.yaw - yb);
		float yaw = (float) cr(y0, ya, yb, y3, u);
		float pitch = (float) cr(p0.pitch, a.pitch, b.pitch, p3.pitch, u);
		float roll = (float) (a.roll + (b.roll - a.roll) * smooth(u));
		float fov = (float) (a.fov + (b.fov - a.fov) * smooth(u));
		CameraPose pose = new CameraPose(x, y, z, yaw, Math.max(-90, Math.min(90, pitch)), roll, fov);
		if (a.mode == Mode.LOOK) {
			Entity target = lookTarget(x, y, z, partial);
			if (target != null) {
				Vec3 tp = target.getPosition(partial);
				pose = pose.lookingAt(tp.x, tp.y + target.getBbHeight() * 0.85, tp.z);
			}
		}
		return pose;
	}

	private static Entity lookTarget(double x, double y, double z, float partial) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return null;
		}
		Entity best = null;
		double bestD = 48 * 48;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e == mc.player || !(e instanceof LivingEntity) || e.isSpectator()) {
				continue;
			}
			double d = e.getPosition(partial).distanceToSqr(x, y, z);
			// the actors' puppets (mannequins) first
			if (e instanceof net.minecraft.world.entity.decoration.Mannequin) {
				d *= 0.25;
			}
			if (d < bestD) {
				bestD = d;
				best = e;
			}
		}
		return best;
	}

	private static CameraPose toPose(Keyframe k) {
		return new CameraPose(k.x, k.y, k.z, k.yaw, k.pitch, k.roll, k.fov);
	}

	private static double cr(double p0, double p1, double p2, double p3, double u) {
		double u2 = u * u, u3 = u2 * u;
		return 0.5 * (2 * p1 + (-p0 + p2) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u2 + (-p0 + 3 * p1 - 3 * p2 + p3) * u3);
	}

	private static double smooth(double u) {
		return u * u * (3 - 2 * u);
	}

	private static float wrap(float d) {
		d %= 360;
		if (d > 180) {
			d -= 360;
		}
		if (d < -180) {
			d += 360;
		}
		return d;
	}

	// ------------------------------------------------------------------ export

	static void export() {
		Minecraft mc = Minecraft.getInstance();
		double from = Math.max(0, in), to = Math.min(length(), outPoint());
		if (to - from < 0.1) {
			say("In/Out-Bereich ist leer");
			return;
		}
		String n = exportName.replaceAll("[^A-Za-z0-9_\\-]", "_");
		if (n.isEmpty()) {
			n = "film";
		}
		save();
		int[] wh = switch (RESOLUTIONS[res]) {
			case "1080p" -> new int[] {1920, 1080};
			case "1440p" -> new int[] {2560, 1440};
			default -> new int[] {3840, 2160};
		};
		cameraOn = !KEYS.isEmpty() || cameraOn;
		exporting = true;
		mc.gui.setScreen(null);
		ExportManager.start(n, to - from, wh[0], wh[1], FPS[fps], name, from);
	}

	// ------------------------------------------------------------------ hotkeys + HUD

	public static void tick(Minecraft mc) {
		if (exporting && !ExportManager.active()) {
			exporting = false;
			if (name != null && mc.player != null) {
				cmd("btb replay edit " + name); // back into the editor, paused
			}
		}
		while (openKey.consumeClick()) {
			if (mc.gui.screen() == null) {
				open(null);
			}
		}
		boolean on = active() && mc.gui.screen() == null && !ExportManager.active();
		while (keyframeKey.consumeClick()) {
			if (on) {
				addKeyframe();
			}
		}
		while (playKey.consumeClick()) {
			if (on) {
				togglePlay();
			}
		}
		while (backKey.consumeClick()) {
			if (on) {
				seek(time(0) - 1);
			}
		}
		while (fwdKey.consumeClick()) {
			if (on) {
				seek(time(0) + 1);
			}
		}
		while (camKey.consumeClick()) {
			if (on) {
				cameraOn = !cameraOn;
				mc.gui.hud.setOverlayMessage(Component.literal(cameraOn ? "◆ Kamera fährt die Keyframes" : "✈ Freie Kamera – Blickwinkel suchen, K setzt Keyframe"), false);
			}
		}
		// the take was stopped from elsewhere: end the session quietly
		if (active() && ReplayManager.current() == null && !ExportManager.active() && !exporting && mc.gui.screen() == null) {
			pendingStopTicks++;
			if (pendingStopTicks > 40) {
				save();
				name = null;
				cameraOn = false;
			}
		} else {
			pendingStopTicks = 0;
		}
	}

	private static int pendingStopTicks;

	/** Small timeline at the bottom while flying (not in the clean feed, not in exports). */
	public static void renderHud(GuiGraphicsExtractor g, float partial) {
		Minecraft mc = Minecraft.getInstance();
		if (!active() || mc.gui.screen() != null || ExportManager.active() || mc.gui.hud.isHidden()) {
			return;
		}
		Font font = mc.font;
		int w = g.guiWidth(), h = g.guiHeight();
		int x0 = 8, x1 = w - 8, y = h - 52;
		g.fill(x0 - 4, y - 25, x1 + 4, y + 9, 0x90000000);
		double len = length(), t = time(partial);
		g.fill(x0, y, x1, y + 4, 0xFF303030);
		int inX = x0 + (int) ((x1 - x0) * in / len), outX = x0 + (int) ((x1 - x0) * outPoint() / len);
		g.fill(inX, y, outX, y + 4, 0x6060A0FF);
		for (int i = 0; i < KEYS.size(); i++) {
			int kx = x0 + (int) ((x1 - x0) * KEYS.get(i).t / len);
			g.fill(kx - 1, y - 2, kx + 2, y + 6, i == selected ? 0xFFFFFFFF : KEYS.get(i).mode.color);
		}
		int px = x0 + (int) ((x1 - x0) * t / len);
		g.fill(px, y - 4, px + 1, y + 8, 0xFFFFFFFF);
		String state = !name.equals(ReplayManager.current()) ? "■" : ReplayManager.isPaused() ? "⏸" : "▶";
		g.text(font, state + " " + name + "  " + clock(t) + " / " + clock(len) + "  " + (cameraOn ? "◆ Keyframes" : "✈ frei"), x0, y - 12, 0xFFFFE0C0, true);
		String help = "K Keyframe · ↑ Play/Pause · ←/→ 1 s · J Kamera · F6 Editor";
		String st = currentStatus();
		if (st != null) {
			g.text(font, font.plainSubstrByWidth(st, x1 - x0), x0, y - 23, statusError ? 0xFFFF5050 : 0xFFAAFFAA, true);
		} else {
			g.text(font, font.plainSubstrByWidth(help, x1 - x0), x0, y - 23, 0xFFAAAAAA, false);
		}
	}

	static String clock(double s) {
		int m = (int) (s / 60);
		return String.format(Locale.ROOT, "%d:%04.1f", m, s - m * 60);
	}

	static void say(String text) {
		note(text, true);
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(Component.literal("[BTB Replay] " + text));
		}
	}
}
