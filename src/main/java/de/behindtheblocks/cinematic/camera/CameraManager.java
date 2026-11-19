package de.behindtheblocks.cinematic.camera;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import de.behindtheblocks.cinematic.BtbCinematic;
import de.behindtheblocks.cinematic.command.CommandContributor;
import de.behindtheblocks.cinematic.command.CommandUtil;
import de.behindtheblocks.cinematic.command.Feedback;
import de.behindtheblocks.cinematic.core.CinematicEngine;
import de.behindtheblocks.cinematic.core.CinematicModule;
import de.behindtheblocks.cinematic.core.bus.SceneMessages;
import de.behindtheblocks.cinematic.event.CinematicAction;
import de.behindtheblocks.cinematic.event.builtin.BuiltinEvents;
import de.behindtheblocks.cinematic.event.builtin.PlayerTargets;
import de.behindtheblocks.cinematic.json.JsonParamException;
import de.behindtheblocks.cinematic.json.JsonParams;
import de.behindtheblocks.cinematic.scene.SceneLoader;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Cinematic camera, server side.
 *
 * <p>Stores camera presets (paths) in {@code config/btb_cinematic/cameras/<id>.json}, builds
 * camera "shots" and sends them to clients as {@code camera_play} cues. The client then drives
 * the view every frame (see {@code CinematicCameraController}); the player's body stays where it
 * is, so the camera is fully independent of the player's own view.
 *
 * <p>The server evaluates the same path math to know when a shot ends and, with {@code carry},
 * to move spectator-mode camera operators along so the client keeps receiving nearby chunks.
 */
public final class CameraManager implements CinematicModule, CommandContributor {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/camera");

	private final Map<String, CameraPath> presets = new TreeMap<>();
	private final Map<UUID, ActiveShot> active = new HashMap<>();
	private final Set<String> pausedScenes = new HashSet<>();
	/** Running path recordings: the operator flies the move, we sample it. */
	private final Map<UUID, Recording> recordings = new HashMap<>();

	private static final class Recording {
		final String id;
		final int interval;
		final List<CameraPoint> points = new ArrayList<>();
		int t;

		Recording(String id, int interval) {
			this.id = id;
			this.interval = interval;
		}
	}
	private CinematicEngine engine;
	private MinecraftServer server;

	/** A shot currently playing for one player (server-side bookkeeping). */
	private static final class ActiveShot {
		final String sceneId;
		final CameraPath path;
		final OrbitShot orbit;
		final int orbitEntity;
		final int duration;
		final boolean carry;
		int elapsed;

		ActiveShot(String sceneId, CameraPath path, OrbitShot orbit, int orbitEntity, int duration, boolean carry) {
			this.sceneId = sceneId;
			this.path = path;
			this.orbit = orbit;
			this.orbitEntity = orbitEntity;
			this.duration = duration;
			this.carry = carry;
		}
	}

	/** Options shared by path and orbit shots. */
	public record ShotOptions(String lookAtPlayer, double[] lookAtPos, boolean carry, boolean hideHud) {
		public static final ShotOptions DEFAULT = new ShotOptions(null, null, false, true);
	}

	@Override
	public String id() {
		return "camera";
	}

	public static Path presetDirectory() {
		return FabricLoader.getInstance().getConfigDir().resolve(BtbCinematic.MOD_ID).resolve("cameras");
	}

	@Override
	public void initialize(CinematicEngine engine) {
		this.engine = engine;
		engine.bus().subscribe(SceneMessages.Paused.class, m -> pausedScenes.add(m.sceneId()));
		engine.bus().subscribe(SceneMessages.Resumed.class, m -> pausedScenes.remove(m.sceneId()));
		engine.bus().subscribe(SceneMessages.Stopped.class, m -> {
			pausedScenes.remove(m.sceneId());
			if (!m.finished()) {
				stopScene(m.sceneId());
			}
		});
		engine.bus().subscribe(SceneMessages.Reset.class, m -> stopScene(m.sceneId()));
		registerEvents();
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		this.server = server;
		installBundled();
		loadPresets();
	}

	@Override
	public void onServerStopping(MinecraftServer server) {
		active.clear();
		this.server = null;
	}

	// ------------------------------------------------------------------ presets

	private void installBundled() {
		Path dir = presetDirectory();
		try {
			Files.createDirectories(dir);
			var bundled = FabricLoader.getInstance().getModContainer(BtbCinematic.MOD_ID)
					.flatMap(m -> m.findPath("btb_cinematic/default_cameras"));
			if (bundled.isPresent()) {
				try (Stream<Path> files = Files.list(bundled.get())) {
					for (Path f : files.toList()) {
						Path dest = dir.resolve(f.getFileName().toString());
						if (!Files.exists(dest)) {
							Files.copy(f, dest);
						}
					}
				}
			}
		} catch (IOException e) {
			LOG.error("Cannot prepare camera preset directory", e);
		}
	}

	public int loadPresets() {
		presets.clear();
		try (Stream<Path> files = Files.list(presetDirectory())) {
			for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
				String name = f.getFileName().toString();
				String id = name.substring(0, name.length() - 5);
				try {
					JsonObject json = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
					CameraPath path = CameraPath.fromJson(id, json);
					presets.put(path.id(), path);
				} catch (RuntimeException | IOException e) {
					LOG.warn("Camera preset {} could not be loaded: {}", name, e.getMessage());
				}
			}
		} catch (IOException e) {
			LOG.error("Cannot list camera presets", e);
		}
		LOG.info("Loaded {} camera preset(s)", presets.size());
		return presets.size();
	}

	private void save(CameraPath path) {
		try {
			Files.createDirectories(presetDirectory());
			Files.writeString(presetDirectory().resolve(path.id() + ".json"),
					new GsonBuilder().setPrettyPrinting().create().toJson(path.toJson()), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOG.error("Could not save camera preset {}", path.id(), e);
		}
	}

	/** Stores (and saves) a preset, replacing one with the same id. */
	public void putPreset(CameraPath path) {
		presets.put(path.id(), path);
		save(path);
	}

	public Collection<String> presetIds() {
		return presets.keySet();
	}

	public java.util.Optional<CameraPath> preset(String id) {
		return java.util.Optional.ofNullable(presets.get(id));
	}

	// ------------------------------------------------------------------ playback

	/** Starts a path shot for the given players. */
	public void playPath(String sceneId, int tick, CameraPath path, Collection<ServerPlayer> players, ShotOptions opt) {
		JsonObject shot = new JsonObject();
		shot.addProperty("kind", "path");
		shot.add("path", path.toJson());
		send(sceneId, tick, shot, players, opt, new ActiveShot(sceneId, path, null, -1, path.durationTicks(), opt.carry()));
	}

	/**
	 * Starts an orbit shot.
	 *
	 * @param centerEntity entity whose position is used as moving center, or null for a fixed center
	 */
	public void playOrbit(String sceneId, int tick, OrbitShot orbit, Entity centerEntity, Collection<ServerPlayer> players,
			ShotOptions opt) {
		JsonObject shot = new JsonObject();
		shot.addProperty("kind", "orbit");
		shot.add("orbit", orbit.toJson());
		int entityId = centerEntity == null ? -1 : centerEntity.getId();
		if (centerEntity != null) {
			shot.addProperty("orbit_entity", entityId);
		}
		send(sceneId, tick, shot, players, opt, new ActiveShot(sceneId, null, orbit, entityId, orbit.ticks(), opt.carry()));
	}

	/**
	 * Follow camera on an entity. Modes: side (2.5D side view; {@code side} = direction from the
	 * target to the camera), behind (chase), fixed (camera at {@code fixedPos} panning with the target), top.
	 *
	 * @param ticks duration, -1 = until stopped
	 */
	public void playFollow(String sceneId, int tick, Entity target, String mode, double distance, double height, Vec3 side,
			Vec3 fixedPos, double smoothing, int ticks, Collection<ServerPlayer> players) {
		JsonObject shot = new JsonObject();
		shot.addProperty("kind", "follow");
		JsonObject f = new JsonObject();
		f.addProperty("entity", target.getId());
		f.addProperty("mode", mode);
		f.addProperty("distance", distance);
		f.addProperty("height", height);
		JsonArray sd = new JsonArray();
		sd.add(side.x);
		sd.add(side.y);
		sd.add(side.z);
		f.add("side", sd);
		if (fixedPos != null) {
			JsonArray fp = new JsonArray();
			fp.add(fixedPos.x);
			fp.add(fixedPos.y);
			fp.add(fixedPos.z);
			f.add("pos", fp);
		}
		f.addProperty("smoothing", smoothing);
		f.addProperty("ticks", ticks);
		shot.add("follow", f);
		send(sceneId, tick, shot, players, ShotOptions.DEFAULT,
				new ActiveShot(sceneId, null, null, -1, ticks < 0 ? Integer.MAX_VALUE : ticks, false));
	}

	/** Parses a side direction like "+z", "-x", "south", or "x,y,z". */
	public static Vec3 parseSide(String side) {
		return switch (side) {
			case "+x", "east" -> new Vec3(1, 0, 0);
			case "-x", "west" -> new Vec3(-1, 0, 0);
			case "+z", "south" -> new Vec3(0, 0, 1);
			case "-z", "north" -> new Vec3(0, 0, -1);
			default -> {
				String[] p = side.split(",");
				if (p.length != 3) {
					throw new JsonParamException("'side' must be +x, -x, +z, -z, north/south/east/west or \"x,y,z\"");
				}
				yield new Vec3(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]));
			}
		};
	}

	private void send(String sceneId, int tick, JsonObject shot, Collection<ServerPlayer> players, ShotOptions opt,
			ActiveShot bookkeeping) {
		shot.addProperty("hide_hud", opt.hideHud());
		if (opt.lookAtPos() != null) {
			JsonArray a = new JsonArray();
			for (double d : opt.lookAtPos()) {
				a.add(d);
			}
			shot.add("look_at_pos", a);
		} else if (opt.lookAtPlayer() != null && server != null) {
			ServerPlayer target = server.getPlayerList().getPlayerByName(opt.lookAtPlayer());
			if (target != null) {
				shot.addProperty("look_at_entity", target.getId());
			} else {
				LOG.warn("Camera look-at target '{}' is not online", opt.lookAtPlayer());
			}
		}
		engine.cues().queue(sceneId, tick, "camera_play", shot, players);
		for (ServerPlayer p : players) {
			active.put(p.getUUID(), bookkeeping);
		}
	}

	public void stop(String sceneId, int tick, Collection<ServerPlayer> players) {
		engine.cues().queue(sceneId, tick, "camera_stop", new JsonObject(), players);
		players.forEach(p -> active.remove(p.getUUID()));
	}

	private void stopScene(String sceneId) {
		if (server == null) {
			return;
		}
		List<ServerPlayer> affected = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			ActiveShot s = active.get(p.getUUID());
			if (s != null && s.sceneId.equals(sceneId)) {
				affected.add(p);
			}
		}
		if (!affected.isEmpty()) {
			stop(sceneId, 0, affected);
		}
	}

	/** Sends a camera shake to players. */
	public void shake(String sceneId, int tick, float intensity, int ticks, float frequency, Collection<ServerPlayer> players) {
		JsonObject o = new JsonObject();
		o.addProperty("intensity", intensity);
		o.addProperty("ticks", ticks);
		o.addProperty("frequency", frequency);
		engine.cues().queue(sceneId, tick, "camera_shake", o, players);
	}

	/** Handheld camera feel (0 = off, 1 = documentary, 2 = running). */
	public void handheld(String sceneId, int tick, float intensity, Collection<ServerPlayer> players) {
		JsonObject o = new JsonObject();
		o.addProperty("intensity", intensity);
		engine.cues().queue(sceneId, tick, "camera_handheld", o, players);
	}

	/**
	 * Dolly zoom ("Vertigo"): the camera travels along the view line towards/away from the subject while
	 * the field of view changes so the subject keeps its size – the background stretches or collapses.
	 */
	public CameraPath dollyZoom(Vec3 subject, float yaw, double fromDist, double toDist, int ticks, float startFov) {
		Vec3 dir = Vec3.directionFromRotation(4, yaw);
		double half = Math.tan(Math.toRadians(startFov) / 2) * fromDist;
		int n = 16;
		List<CameraPoint> pts = new ArrayList<>();
		for (int i = 0; i <= n; i++) {
			double t = i / (double) n;
			double e = t * t * (3 - 2 * t);
			double d = fromDist + (toDist - fromDist) * e;
			Vec3 pos = subject.subtract(dir.scale(d));
			float fov = (float) Math.toDegrees(2 * Math.atan(half / d));
			CameraPose look = new CameraPose(pos.x, pos.y, pos.z, 0, 0, 0, 0).lookingAt(subject.x, subject.y, subject.z);
			pts.add(new CameraPoint(pos.x, pos.y, pos.z, look.yaw(), look.pitch(), 0, Math.max(10, Math.min(130, fov)),
					i == n ? 1 : Math.max(1, ticks / n), Easing.LINEAR, null, null));
		}
		return new CameraPath("_dolly_zoom", pts, CameraPath.Mode.SMOOTH, Easing.LINEAR, 1.0);
	}

	/** Animates the FOV to {@code fov} (0 = back to normal) over {@code ticks}. */
	public void zoom(String sceneId, int tick, float fov, int ticks, Easing easing, Collection<ServerPlayer> players) {
		JsonObject o = new JsonObject();
		o.addProperty("fov", fov);
		o.addProperty("ticks", ticks);
		o.addProperty("easing", easing.id());
		engine.cues().queue(sceneId, tick, "camera_zoom", o, players);
	}

	public void perspective(String sceneId, int tick, String mode, Collection<ServerPlayer> players) {
		JsonObject o = new JsonObject();
		o.addProperty("mode", mode);
		engine.cues().queue(sceneId, tick, "camera_perspective", o, players);
	}

	/** Real-time deadline (ms) at which slow motion ends; 0 = none running. */
	private long slowmoUntil;
	private MinecraftServer slowmoServer;

	/** Slows the whole world down (mobs, debris, particles, scene clocks) and returns to normal after real seconds. */
	public void slowmo(MinecraftServer server, float factor, double seconds) {
		slowmoServer = server;
		server.tickRateManager().setTickRate(Math.max(1f, 20f * factor));
		slowmoUntil = seconds <= 0 ? 0 : System.currentTimeMillis() + (long) (seconds * 1000);
		if (seconds <= 0) {
			server.tickRateManager().setTickRate(20f);
		}
	}

	@Override
	public void tick(MinecraftServer server) {
		tickPreviews();
		if (slowmoUntil > 0 && System.currentTimeMillis() >= slowmoUntil) {
			slowmoUntil = 0;
			server.tickRateManager().setTickRate(20f);
		}
		if (!recordings.isEmpty()) {
			for (Map.Entry<UUID, Recording> e : recordings.entrySet()) {
				ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
				Recording r = e.getValue();
				if (p != null && r.t++ % r.interval == 0) {
					r.points.add(pointFrom(p).withTicks(r.interval));
					if (r.points.size() % 20 == 0) {
						p.sendSystemMessage(net.minecraft.network.chat.Component.literal("● REC " + r.id + "  " + r.points.size() + " Punkte")
								.withStyle(net.minecraft.ChatFormatting.RED), true);
					}
				}
			}
		}
		if (active.isEmpty()) {
			return;
		}
		var it = active.entrySet().iterator();
		while (it.hasNext()) {
			var e = it.next();
			ActiveShot s = e.getValue();
			if (pausedScenes.contains(s.sceneId)) {
				continue;
			}
			s.elapsed++;
			ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
			if (player == null || s.elapsed > s.duration) {
				it.remove();
				continue;
			}
			if (s.carry && player.isSpectator() && s.elapsed % 2 == 0) {
				CameraPose pose = s.path != null ? s.path.evaluate(s.elapsed, 70f) : orbitPose(s, player);
				if (pose != null) {
					player.teleportTo(player.level(), pose.x(), pose.y() - player.getEyeHeight(), pose.z(), Set.of(),
							pose.yaw(), pose.pitch(), false);
				}
			}
		}
	}

	private CameraPose orbitPose(ActiveShot s, ServerPlayer player) {
		if (s.orbitEntity >= 0) {
			Entity center = player.level().getEntity(s.orbitEntity);
			if (center == null) {
				return null;
			}
			return s.orbit.evaluate(s.elapsed, 70f, center.getX(), center.getY(), center.getZ());
		}
		return s.orbit.evaluate(s.elapsed, 70f);
	}

	public boolean isPlaying(ServerPlayer player) {
		return active.containsKey(player.getUUID());
	}

	// ------------------------------------------------------------------ timeline events

	private void registerEvents() {
		engine.events().register(BuiltinEvents.simple("camera_play",
				"Plays a camera preset. {path, players?, look_at? (player name or [x,y,z]), speed?, carry?, hide_hud?}",
				this::prepPlay));
		engine.events().register(BuiltinEvents.simple("camera_orbit",
				"Orbits around a point or player. {center:[x,y,z] | target:player, radius, height, seconds|ticks, degrees?, start_angle?, radius_end?, height_end?, easing?, fov?, players?}",
				this::prepOrbit));
		engine.events().register(BuiltinEvents.simple("camera_follow",
				"Follows a player. {target, mode: side|behind|fixed|top, distance?, height?, side? (+z…), pos? (fixed), smoothing?, ticks? (-1 = until stop), players?}",
				p -> {
					String target = p.requireString("target");
					String mode = p.getString("mode", "side");
					if (!List.of("side", "behind", "fixed", "top", "front", "shoulder", "low", "crane", "orbit", "auto", "pov", "ego").contains(mode)) {
						throw new JsonParamException("'mode' must be side, behind, fixed, top, front, shoulder, low, crane, orbit or auto");
					}
					Vec3 side = parseSide(p.getString("side", "+z"));
					double[] fixed = p.has("pos") ? p.requireDoubleArray("pos", 3) : null;
					double distance = p.getDouble("distance", 8), height = p.getDouble("height", 2), smoothing = p.getDouble("smoothing", 0.15);
					int ticks = p.getInt("ticks", -1);
					PlayerTargets t = PlayerTargets.parse(p);
					return ctx -> {
						ServerPlayer tp = ctx.server().getPlayerList().getPlayerByName(target);
						if (tp == null) {
							throw new IllegalStateException("follow target '" + target + "' is not online");
						}
						playFollow(ctx.sceneId(), ctx.tick(), tp, mode, distance, height, side,
								fixed == null ? null : new Vec3(fixed[0], fixed[1], fixed[2]), smoothing, ticks, t.resolve(ctx.server()));
					};
				}));
		engine.events().register(BuiltinEvents.simple("camera_stop",
				"Returns the view to the player. {players?}",
				p -> {
					PlayerTargets t = PlayerTargets.parse(p);
					return ctx -> stop(ctx.sceneId(), ctx.tick(), t.resolve(ctx.server()));
				}));
		engine.events().register(BuiltinEvents.simple("camera_shake",
				"Shakes the camera. {intensity (0..5), duration ticks (default 20), frequency? (default 1), players?}",
				p -> {
					float intensity = p.getFloat("intensity", 0.5f);
					int ticks = p.getInt("duration", 20);
					float freq = p.getFloat("frequency", 1f);
					if (intensity < 0 || intensity > 5 || ticks < 1 || freq <= 0 || freq > 10) {
						throw new JsonParamException("intensity 0..5, duration >= 1, frequency 0..10");
					}
					PlayerTargets t = PlayerTargets.parse(p);
					return ctx -> shake(ctx.sceneId(), ctx.tick(), intensity, ticks, freq, t.resolve(ctx.server()));
				}));
		engine.events().register(BuiltinEvents.simple("camera_zoom",
				"Animates the field of view. {fov (0 = normal), duration ticks, easing?, players?}",
				p -> {
					float fov = p.getFloat("fov", 0);
					if (fov != 0 && (fov < 1 || fov > 170)) {
						throw new JsonParamException("'fov' must be 1..170 or 0");
					}
					int ticks = p.getInt("duration", 20);
					Easing easing = easing(p, "ease_in_out");
					PlayerTargets t = PlayerTargets.parse(p);
					return ctx -> zoom(ctx.sceneId(), ctx.tick(), fov, Math.max(1, ticks), easing, t.resolve(ctx.server()));
				}));
		engine.events().register(BuiltinEvents.simple("camera_perspective",
				"Switches perspective. {mode: first | third | front, players?}",
				p -> {
					String mode = p.requireString("mode");
					if (!List.of("first", "third", "front").contains(mode)) {
						throw new JsonParamException("'mode' must be first, third or front");
					}
					PlayerTargets t = PlayerTargets.parse(p);
					return ctx -> perspective(ctx.sceneId(), ctx.tick(), mode, t.resolve(ctx.server()));
				}));
	}

	private static Easing easing(JsonParams p, String def) {
		try {
			return Easing.byId(p.getString("easing", def));
		} catch (IllegalArgumentException e) {
			throw new JsonParamException(e.getMessage());
		}
	}

	private ShotOptions options(JsonParams p) {
		String lookPlayer = null;
		double[] lookPos = null;
		if (p.has("look_at")) {
			if (p.raw().get("look_at").isJsonArray()) {
				lookPos = p.requireDoubleArray("look_at", 3);
			} else {
				lookPlayer = p.requireString("look_at");
			}
		}
		return new ShotOptions(lookPlayer, lookPos, p.getBoolean("carry", false), p.getBoolean("hide_hud", true));
	}

	private CinematicAction prepPlay(JsonParams p) {
		String pathId = p.requireString("path");
		if (!SceneLoader.ID_PATTERN.matcher(pathId).matches()) {
			throw new JsonParamException("'path' is not a valid preset id");
		}
		double speed = p.getDouble("speed", 0);
		if (speed < 0 || speed > 100) {
			throw new JsonParamException("'speed' must be 0..100 (0 = preset speed)");
		}
		ShotOptions opt = options(p);
		PlayerTargets t = PlayerTargets.parse(p);
		return ctx -> {
			CameraPath path = presets.get(pathId);
			if (path == null) {
				throw new IllegalStateException("camera preset '" + pathId + "' does not exist");
			}
			if (speed > 0) {
				path = path.withSpeed(speed);
			}
			playPath(ctx.sceneId(), ctx.tick(), path, t.resolve(ctx.server()), opt);
		};
	}

	private CinematicAction prepOrbit(JsonParams p) {
		String targetName = p.has("target") ? p.requireString("target") : null;
		JsonObject copy = p.raw().deepCopy();
		if (targetName != null && !copy.has("center")) {
			JsonArray zero = new JsonArray();
			zero.add(0);
			zero.add(0);
			zero.add(0);
			copy.add("center", zero);
		}
		OrbitShot orbit = OrbitShot.fromJson(new JsonParams(copy));
		ShotOptions opt = options(p);
		PlayerTargets t = PlayerTargets.parse(p);
		return ctx -> {
			Entity center = null;
			if (targetName != null) {
				center = ctx.server().getPlayerList().getPlayerByName(targetName);
				if (center == null) {
					throw new IllegalStateException("orbit target '" + targetName + "' is not online");
				}
			}
			playOrbit(ctx.sceneId(), ctx.tick(), orbit, center, t.resolve(ctx.server()), opt);
		};
	}

	// ------------------------------------------------------------------ commands

	@Override
	public void contributeCommands(LiteralArgumentBuilder<CommandSourceStack> root, CommandBuildContext buildContext) {
		SuggestionProvider<CommandSourceStack> ids = (c, b) -> SharedSuggestionProvider.suggest(presets.keySet(), b);
		SuggestionProvider<CommandSourceStack> easings = (c, b) -> SharedSuggestionProvider.suggest(
				Stream.of(Easing.values()).map(Easing::id), b);

		LiteralArgumentBuilder<CommandSourceStack> cam = literal("camera");
		cam.then(literal("list").executes(c -> {
			if (presets.isEmpty()) {
				return Feedback.fail(c.getSource(), "No camera presets. Create one: /btb camera create <id>");
			}
			Feedback.ok(c.getSource(), presets.size() + " camera preset(s):");
			presets.values().forEach(p -> Feedback.info(c.getSource(), " • " + p.id() + " — " + p.points().size()
					+ " points, " + p.mode().id() + ", " + String.format(Locale.ROOT, "%.1f s", p.durationTicks() / 20.0)));
			return presets.size();
		}));
		cam.then(literal("record")
				.then(literal("stop").executes(c -> {
					Recording r = recordings.remove(c.getSource().getPlayerOrException().getUUID());
					if (r == null) {
						return Feedback.fail(c.getSource(), "No recording running");
					}
					List<CameraPoint> pts = simplify(r.points);
					if (pts.size() < 2) {
						return Feedback.fail(c.getSource(), "Too short — move further while recording");
					}
					CameraPath path = new CameraPath(r.id, pts, CameraPath.Mode.SMOOTH, Easing.LINEAR, 1.0);
					presets.put(path.id(), path);
					save(path);
					return Feedback.ok(c.getSource(), String.format(Locale.ROOT, "Recorded camera '%s': %d points, %.1f s. Play: /btb camera play %s",
							r.id, pts.size(), path.durationTicks() / 20.0, r.id));
				}))
				.then(argument("id", StringArgumentType.word())
						.executes(c -> startRecording(c, 10))
						.then(argument("interval", DoubleArgumentType.doubleArg(0.1, 5))
								.executes(c -> startRecording(c, CommandUtil.ticks(DoubleArgumentType.getDouble(c, "interval")))))));
		cam.then(literal("reload").executes(c -> Feedback.ok(c.getSource(), "Loaded " + loadPresets() + " camera preset(s)")));
		cam.then(literal("create").then(argument("id", StringArgumentType.word()).executes(c -> {
			String id = StringArgumentType.getString(c, "id");
			if (!SceneLoader.ID_PATTERN.matcher(id).matches()) {
				return Feedback.fail(c.getSource(), "Id must match [a-z0-9_]");
			}
			if (presets.containsKey(id)) {
				return Feedback.fail(c.getSource(), "Preset '" + id + "' already exists");
			}
			ServerPlayer player = c.getSource().getPlayerOrException();
			CameraPath path = new CameraPath(id, List.of(pointFrom(player)), CameraPath.Mode.SMOOTH, Easing.LINEAR, 1.0);
			presets.put(id, path);
			save(path);
			return Feedback.ok(c.getSource(), "Created camera '" + id + "' with your current view as point 1. Add more: /btb camera point " + id);
		})));
		cam.then(literal("point").then(argument("id", StringArgumentType.word()).suggests(ids)
				.executes(c -> setPoint(c, -1))
				.then(argument("index", IntegerArgumentType.integer(1, 999))
						.executes(c -> setPoint(c, IntegerArgumentType.getInteger(c, "index"))))));
		cam.then(literal("remove").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("index", IntegerArgumentType.integer(1, 999)).executes(c -> edit(c, (path, i) -> {
					List<CameraPoint> pts = new ArrayList<>(path.points());
					if (pts.size() <= 1) {
						throw new IllegalArgumentException("a path needs at least one point; use delete instead");
					}
					pts.remove(i);
					return path.withPoints(pts);
				})))));
		cam.then(literal("duration").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("index", IntegerArgumentType.integer(1, 999))
						.then(argument("seconds", DoubleArgumentType.doubleArg(0.05, 3600)).executes(c -> edit(c, (path, i) -> {
							int t = CommandUtil.ticks(DoubleArgumentType.getDouble(c, "seconds"));
							return replace(path, i, path.points().get(i).withTicks(t));
						}))))));
		cam.then(literal("fov").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("index", IntegerArgumentType.integer(1, 999))
						.then(argument("fov", FloatArgumentType.floatArg(0, 170)).executes(c -> edit(c, (path, i) ->
								replace(path, i, path.points().get(i).withFov(FloatArgumentType.getFloat(c, "fov")))))))));
		// focus <path> <point> <blocks|0>: the depth of field pulls to this distance at that point (0 = automatic)
		cam.then(literal("focus").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("index", IntegerArgumentType.integer(1, 999))
						.then(argument("blocks", FloatArgumentType.floatArg(0, 80)).executes(c -> edit(c, (path, i) ->
								replace(path, i, path.points().get(i).withFocus(FloatArgumentType.getFloat(c, "blocks")))))))));
		// preview <path> [seconds]: the move as a glowing line in the world, points as bright markers
		cam.then(literal("preview").then(argument("id", StringArgumentType.word()).suggests(ids)
				.executes(c -> preview(c, 20))
				.then(argument("seconds", DoubleArgumentType.doubleArg(1, 300)).executes(c -> preview(c, DoubleArgumentType.getDouble(c, "seconds"))))));
		// smooth <path> [strength 1..5]: stabiliser for hand-flown moves (removes mouse jitter)
		cam.then(literal("smooth").then(argument("id", StringArgumentType.word()).suggests(ids)
				.executes(c -> smoothCmd(c, 2))
				.then(argument("strength", IntegerArgumentType.integer(1, 5)).executes(c -> smoothCmd(c, IntegerArgumentType.getInteger(c, "strength"))))));
		cam.then(literal("roll").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("index", IntegerArgumentType.integer(1, 999))
						.then(argument("degrees", FloatArgumentType.floatArg(-180, 180)).executes(c -> edit(c, (path, i) ->
								replace(path, i, path.points().get(i).withRoll(FloatArgumentType.getFloat(c, "degrees")))))))));
		cam.then(literal("easing").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("easing", StringArgumentType.word()).suggests(easings)
						.executes(c -> editPath(c, path -> path.withEasing(Easing.byId(StringArgumentType.getString(c, "easing")))))
						.then(argument("index", IntegerArgumentType.integer(1, 999)).executes(c -> edit(c, (path, i) ->
								replace(path, i, path.points().get(i).withEasing(Easing.byId(StringArgumentType.getString(c, "easing"))))))))));
		cam.then(literal("mode").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("mode", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("linear", "smooth", "bezier"), b))
						.executes(c -> editPath(c, path -> path.withMode(CameraPath.Mode.byId(StringArgumentType.getString(c, "mode"))))))));
		cam.then(literal("speed").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("factor", DoubleArgumentType.doubleArg(0.01, 100))
						.executes(c -> editPath(c, path -> path.withSpeed(DoubleArgumentType.getDouble(c, "factor")))))));
		cam.then(literal("info").then(argument("id", StringArgumentType.word()).suggests(ids).executes(c -> {
			CameraPath path = presets.get(StringArgumentType.getString(c, "id"));
			if (path == null) {
				return Feedback.fail(c.getSource(), "Unknown camera preset");
			}
			Feedback.ok(c.getSource(), path.id() + ": " + path.mode().id() + ", easing " + path.easing().id() + ", speed "
					+ path.speed() + ", " + String.format(Locale.ROOT, "%.1f s", path.durationTicks() / 20.0));
			for (int i = 0; i < path.points().size(); i++) {
				CameraPoint p = path.points().get(i);
				Feedback.info(c.getSource(), String.format(Locale.ROOT, "  %d: %.1f %.1f %.1f  yaw %.0f pitch %.0f%s%s  → %.2f s %s",
						i + 1, p.x(), p.y(), p.z(), p.yaw(), p.pitch(), p.roll() != 0 ? " roll " + p.roll() : "",
						p.fov() != 0 ? " fov " + p.fov() : "", p.ticks() / 20.0, p.easing().id()));
			}
			return 1;
		})));
		cam.then(literal("goto").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("index", IntegerArgumentType.integer(1, 999)).executes(c -> {
					CameraPath path = presets.get(StringArgumentType.getString(c, "id"));
					int i = IntegerArgumentType.getInteger(c, "index") - 1;
					if (path == null || i >= path.points().size()) {
						return Feedback.fail(c.getSource(), "Unknown preset or point");
					}
					ServerPlayer player = c.getSource().getPlayerOrException();
					CameraPoint p = path.points().get(i);
					player.teleportTo(player.level(), p.x(), p.y() - player.getEyeHeight(), p.z(), Set.of(), p.yaw(), p.pitch(), false);
					return Feedback.ok(c.getSource(), "Moved to point " + (i + 1));
				}))));
		cam.then(literal("delete").then(argument("id", StringArgumentType.word()).suggests(ids).executes(c -> {
			String id = StringArgumentType.getString(c, "id");
			if (presets.remove(id) == null) {
				return Feedback.fail(c.getSource(), "Unknown camera preset");
			}
			try {
				Files.deleteIfExists(presetDirectory().resolve(id + ".json"));
			} catch (IOException e) {
				LOG.error("Could not delete preset file", e);
			}
			return Feedback.ok(c.getSource(), "Deleted camera '" + id + "'");
		})));

		// play <id> [targets] [lookat <entity>]
		cam.then(literal("play").then(argument("id", StringArgumentType.word()).suggests(ids)
				.executes(c -> play(c, false, null))
				.then(argument("targets", EntityArgument.players())
						.executes(c -> play(c, true, null))
						.then(literal("lookat").then(argument("lookat", EntityArgument.entity())
								.executes(c -> play(c, true, EntityArgument.getEntity(c, "lookat"))))))));
		cam.then(literal("stop")
				.executes(c -> {
					stop("", 0, CommandUtil.targets(c, false));
					return Feedback.ok(c.getSource(), "Camera stopped");
				})
				.then(argument("targets", EntityArgument.players()).executes(c -> {
					stop("", 0, CommandUtil.targets(c, true));
					return Feedback.ok(c.getSource(), "Camera stopped");
				})));
		// orbit <center> <radius> <height> <seconds> [degrees] [targets]
		var orbitAtPos = argument("center", Vec3Argument.vec3())
				.then(argument("radius", DoubleArgumentType.doubleArg(0.5, 512))
						.then(argument("height", DoubleArgumentType.doubleArg(-256, 256))
								.then(orbitTail(argument("seconds", DoubleArgumentType.doubleArg(0.05, 3600)), false))));
		var orbitAtEntity = literal("entity").then(argument("entity", EntityArgument.entity())
				.then(argument("radius", DoubleArgumentType.doubleArg(0.5, 512))
						.then(argument("height", DoubleArgumentType.doubleArg(-256, 256))
								.then(orbitTail(argument("seconds", DoubleArgumentType.doubleArg(0.05, 3600)), true)))));
		cam.then(literal("orbit").then(orbitAtPos).then(orbitAtEntity));
		cam.then(literal("follow").then(argument("target", EntityArgument.entity())
				.then(argument("mode", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("side", "behind", "fixed", "top", "front", "shoulder", "low", "crane", "orbit", "auto", "pov", "ego"), b))
						.executes(c -> followCmd(c, 8, 2, "+z", false))
						.then(argument("distance", DoubleArgumentType.doubleArg(0.5, 128))
								.executes(c -> followCmd(c, DoubleArgumentType.getDouble(c, "distance"), 2, "+z", false))
								.then(argument("height", DoubleArgumentType.doubleArg(-64, 64))
										.executes(c -> followCmd(c, DoubleArgumentType.getDouble(c, "distance"), DoubleArgumentType.getDouble(c, "height"), "+z", false))
										.then(argument("side", StringArgumentType.word())
												.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("+x", "-x", "+z", "-z"), b))
												.executes(c -> followCmd(c, DoubleArgumentType.getDouble(c, "distance"),
														DoubleArgumentType.getDouble(c, "height"), StringArgumentType.getString(c, "side"), false))
												.then(argument("targets", EntityArgument.players()).executes(c -> followCmd(c,
														DoubleArgumentType.getDouble(c, "distance"), DoubleArgumentType.getDouble(c, "height"),
														StringArgumentType.getString(c, "side"), true)))))))));
		// Auto director: cinematic angles that change on their own (shoulder, side, front, crane, low, chase, orbit).
		// slow motion for the whole world: slowmo <factor 0.1..1> <real seconds> · slowmo off
		root.then(literal("slowmo")
				.then(literal("off").executes(c -> {
					slowmo(c.getSource().getServer(), 1f, 0);
					return Feedback.ok(c.getSource(), "Zeitlupe aus");
				}))
				.then(argument("factor", FloatArgumentType.floatArg(0.1f, 1f))
						.then(argument("seconds", DoubleArgumentType.doubleArg(0.2, 60)).executes(c -> {
							slowmo(c.getSource().getServer(), FloatArgumentType.getFloat(c, "factor"), DoubleArgumentType.getDouble(c, "seconds"));
							return Feedback.ok(c.getSource(), "Zeitlupe x" + FloatArgumentType.getFloat(c, "factor"));
						}))));
		cam.then(literal("handheld")
				.then(literal("off").executes(c -> {
					handheld("", 0, 0, CommandUtil.targets(c, false));
					return Feedback.ok(c.getSource(), "Handkamera aus");
				}).then(argument("targets", EntityArgument.players()).executes(c -> {
					handheld("", 0, 0, CommandUtil.targets(c, true));
					return Feedback.ok(c.getSource(), "Handkamera aus");
				})))
				.then(argument("intensity", FloatArgumentType.floatArg(0, 2))
						.executes(c -> {
							handheld("", 0, FloatArgumentType.getFloat(c, "intensity"), CommandUtil.targets(c, false));
							return Feedback.ok(c.getSource(), "Handkamera " + FloatArgumentType.getFloat(c, "intensity"));
						})
						.then(argument("targets", EntityArgument.players()).executes(c -> {
							handheld("", 0, FloatArgumentType.getFloat(c, "intensity"), CommandUtil.targets(c, true));
							return Feedback.ok(c.getSource(), "Handkamera " + FloatArgumentType.getFloat(c, "intensity"));
						}))));
		// dollyzoom <subject x y z> <from distance> <to distance> <seconds> [targets]; direction = where you look
		cam.then(literal("dollyzoom").then(argument("subject", net.minecraft.commands.arguments.coordinates.Vec3Argument.vec3())
				.then(argument("from", DoubleArgumentType.doubleArg(1, 200))
						.then(argument("to", DoubleArgumentType.doubleArg(1, 200))
								.then(argument("seconds", DoubleArgumentType.doubleArg(0.5, 60))
										.executes(c -> dollyCmd(c, false))
										.then(argument("targets", EntityArgument.players()).executes(c -> dollyCmd(c, true))))))));
		// autobeat <target> <bpm> <beats per shot> [targets]: auto director that cuts on the music
		// pov <actor> [seconds] [targets]: the recording camera looks through the actor's eyes (cut-in), then back
		// ego <actor> [seconds] [targets]: body cam through the actor's eyes with head bob, lag and breathing (-1 s = until stop)
		cam.then(literal("ego").then(argument("target", EntityArgument.entity())
				.executes(c -> egoCmd(c, -1, false))
				.then(argument("seconds", DoubleArgumentType.doubleArg(-1, 600))
						.executes(c -> egoCmd(c, DoubleArgumentType.getDouble(c, "seconds"), false))
						.then(argument("targets", EntityArgument.players()).executes(c -> egoCmd(c, DoubleArgumentType.getDouble(c, "seconds"), true))))));
		cam.then(literal("pov").then(argument("target", EntityArgument.entity())
				.executes(c -> povCmd(c, 4, false))
				.then(argument("seconds", DoubleArgumentType.doubleArg(0.5, 120))
						.executes(c -> povCmd(c, DoubleArgumentType.getDouble(c, "seconds"), false))
						.then(argument("targets", EntityArgument.players()).executes(c -> povCmd(c, DoubleArgumentType.getDouble(c, "seconds"), true))))));
		cam.then(literal("autobeat").then(argument("target", EntityArgument.entity())
				.then(argument("bpm", DoubleArgumentType.doubleArg(40, 240))
						.then(argument("beats", IntegerArgumentType.integer(1, 64))
								.executes(c -> autoBeatCmd(c, false))
								.then(argument("targets", EntityArgument.players()).executes(c -> autoBeatCmd(c, true)))))));
		cam.then(literal("auto").then(argument("target", EntityArgument.entity())
				.executes(c -> autoCmd(c, 6, false))
				.then(argument("seconds", DoubleArgumentType.doubleArg(2, 60))
						.executes(c -> autoCmd(c, DoubleArgumentType.getDouble(c, "seconds"), false))
						.then(argument("targets", EntityArgument.players()).executes(c -> autoCmd(c, DoubleArgumentType.getDouble(c, "seconds"), true))))));
		cam.then(literal("shake").then(argument("intensity", FloatArgumentType.floatArg(0, 5))
				.then(argument("seconds", DoubleArgumentType.doubleArg(0.05, 600))
						.executes(c -> shakeCmd(c, false))
						.then(argument("targets", EntityArgument.players()).executes(c -> shakeCmd(c, true))))));
		cam.then(literal("zoom")
				.then(literal("reset").executes(c -> {
					zoom("", 0, 0, 20, Easing.EASE_IN_OUT, CommandUtil.targets(c, false));
					return Feedback.ok(c.getSource(), "Zoom reset");
				}))
				.then(argument("fov", FloatArgumentType.floatArg(1, 170))
						.then(argument("seconds", DoubleArgumentType.doubleArg(0, 600)).executes(c -> {
							zoom("", 0, FloatArgumentType.getFloat(c, "fov"),
									Math.max(1, CommandUtil.ticks(DoubleArgumentType.getDouble(c, "seconds"))),
									Easing.EASE_IN_OUT, CommandUtil.targets(c, false));
							return Feedback.ok(c.getSource(), "Zooming");
						}))));
		cam.then(literal("perspective").then(argument("mode", StringArgumentType.word())
				.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("first", "third", "front"), b))
				.executes(c -> {
					String mode = StringArgumentType.getString(c, "mode");
					if (!List.of("first", "third", "front").contains(mode)) {
						return Feedback.fail(c.getSource(), "Use first, third or front");
					}
					perspective("", 0, mode, CommandUtil.targets(c, false));
					return Feedback.ok(c.getSource(), "Perspective: " + mode);
				})));
		root.then(cam);
	}

	private ArgumentBuilder<CommandSourceStack, ?> orbitTail(ArgumentBuilder<CommandSourceStack, ?> seconds, boolean entity) {
		return seconds
				.executes(c -> orbitCmd(c, entity, false, false))
				.then(argument("degrees", DoubleArgumentType.doubleArg(-3600, 3600))
						.executes(c -> orbitCmd(c, entity, true, false))
						.then(argument("targets", EntityArgument.players()).executes(c -> orbitCmd(c, entity, true, true))));
	}

	private int orbitCmd(CommandContext<CommandSourceStack> c, boolean entity, boolean hasDegrees, boolean hasTargets)
			throws CommandSyntaxException {
		Entity center = entity ? EntityArgument.getEntity(c, "entity") : null;
		Vec3 pos = entity ? center.position() : Vec3Argument.getVec3(c, "center");
		double radius = DoubleArgumentType.getDouble(c, "radius");
		double height = DoubleArgumentType.getDouble(c, "height");
		int ticks = CommandUtil.ticks(DoubleArgumentType.getDouble(c, "seconds"));
		double degrees = hasDegrees ? DoubleArgumentType.getDouble(c, "degrees") : 360;
		double lookOffset = entity ? center.getBbHeight() * 0.6 : 0.5;
		OrbitShot orbit = new OrbitShot(pos.x, pos.y, pos.z, radius, radius, height, height, 0, degrees, ticks,
				Easing.EASE_IN_OUT, 0, lookOffset);
		playOrbit("", 0, orbit, center, CommandUtil.targets(c, hasTargets), ShotOptions.DEFAULT);
		return Feedback.ok(c.getSource(), String.format(Locale.ROOT, "Orbit started (%.1f s)", ticks / 20.0));
	}

	private int followCmd(CommandContext<CommandSourceStack> c, double distance, double height, String side, boolean hasTargets)
			throws CommandSyntaxException {
		String mode = StringArgumentType.getString(c, "mode");
		if (!List.of("side", "behind", "fixed", "top", "front", "shoulder", "low", "crane", "orbit", "auto", "pov", "ego").contains(mode)) {
			return Feedback.fail(c.getSource(), "Mode: side, behind, fixed, top, front, shoulder, low, crane, orbit, auto");
		}
		Vec3 sideVec;
		try {
			sideVec = parseSide(side);
		} catch (RuntimeException e) {
			return Feedback.fail(c.getSource(), e.getMessage());
		}
		Entity target = EntityArgument.getEntity(c, "target");
		Vec3 fixed = mode.equals("fixed") ? c.getSource().getPosition().add(0, 1.6, 0) : null;
		playFollow("", 0, target, mode, distance, height, sideVec, fixed, 0.15, -1, CommandUtil.targets(c, hasTargets));
		return Feedback.ok(c.getSource(), "Following " + target.getName().getString() + " (" + mode + ") — /btb camera stop to end");
	}

	private int autoCmd(CommandContext<CommandSourceStack> c, double seconds, boolean hasTargets) throws CommandSyntaxException {
		Entity target = EntityArgument.getEntity(c, "target");
		// for "auto" the height field carries the shot length (seconds per angle)
		playFollow("", 0, target, "auto", 7, seconds, parseSide("+z"), null, 0.12, -1, CommandUtil.targets(c, hasTargets));
		return Feedback.ok(c.getSource(), "Auto-Regie auf " + target.getName().getString() + " – Perspektive wechselt alle "
				+ (int) seconds + " s · /btb camera stop");
	}

	private int dollyCmd(CommandContext<CommandSourceStack> c, boolean hasTargets) throws CommandSyntaxException {
		Vec3 subject = net.minecraft.commands.arguments.coordinates.Vec3Argument.getVec3(c, "subject");
		double from = DoubleArgumentType.getDouble(c, "from"), to = DoubleArgumentType.getDouble(c, "to");
		int ticks = (int) Math.round(DoubleArgumentType.getDouble(c, "seconds") * 20);
		// direction: from the executor towards the subject (or its look direction when standing on it)
		Vec3 from3 = c.getSource().getPosition();
		float yaw = subject.distanceTo(from3) > 1.5
				? (float) Math.toDegrees(Math.atan2(-(subject.x - from3.x), subject.z - from3.z)) : c.getSource().getRotation().y;
		float startFov = (float) Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(70) / 2) * Math.min(from, to) / from));
		CameraPath path = dollyZoom(subject, yaw, from, to, ticks, Math.max(25, startFov));
		playPath("", 0, path, CommandUtil.targets(c, hasTargets), ShotOptions.DEFAULT);
		return Feedback.ok(c.getSource(), "Dolly-Zoom " + (int) from + " → " + (int) to + " Blöcke");
	}

	private final List<Object[]> previews = new ArrayList<>(); // {player, path, ticksLeft}

	private int preview(CommandContext<CommandSourceStack> c, double seconds) throws CommandSyntaxException {
		CameraPath path = presets.get(StringArgumentType.getString(c, "id"));
		if (path == null) {
			return Feedback.fail(c.getSource(), "Unknown camera preset");
		}
		previews.add(new Object[] {c.getSource().getPlayerOrException(), path, (int) Math.round(seconds * 20)});
		return Feedback.ok(c.getSource(), "Vorschau " + path.id() + ": Linie = Fahrt, helle Punkte = Kamerapunkte. Punkt verschieben: "
				+ "dorthin stellen, in die gewünschte Richtung schauen, /btb camera point " + path.id() + " <nr>");
	}

	private void tickPreviews() {
		java.util.Iterator<Object[]> it = previews.iterator();
		while (it.hasNext()) {
			Object[] pv = it.next();
			ServerPlayer p = (ServerPlayer) pv[0];
			CameraPath path = (CameraPath) pv[1];
			int left = (int) pv[2] - 1;
			pv[2] = left;
			if (left <= 0 || p.isRemoved()) {
				it.remove();
				continue;
			}
			if (left % 10 != 0) {
				continue;
			}
			var level = p.level();
			int dur = path.durationTicks();
			for (double t = 0; t <= dur; t += Math.max(1, dur / 160.0)) {
				CameraPose pose = path.evaluate(t, 70);
				level.sendParticles(p, net.minecraft.core.particles.ParticleTypes.END_ROD, true, true, pose.x(), pose.y(), pose.z(), 1, 0, 0, 0, 0);
			}
			for (int i = 0; i < path.points().size(); i++) {
				CameraPoint pt = path.points().get(i);
				level.sendParticles(p, new net.minecraft.core.particles.DustParticleOptions(i == 0 ? 0x40FF40 : 0xFFC030, 2.2f), true, true,
						pt.x(), pt.y(), pt.z(), 6, 0.08, 0.08, 0.08, 0);
				// short line showing where this point looks
				Vec3 dir = Vec3.directionFromRotation(pt.pitch(), pt.yaw());
				for (int k = 1; k <= 4; k++) {
					level.sendParticles(p, new net.minecraft.core.particles.DustParticleOptions(0x60A0FF, 0.8f), true, true,
							pt.x() + dir.x * k * 0.4, pt.y() + dir.y * k * 0.4, pt.z() + dir.z * k * 0.4, 1, 0, 0, 0, 0);
				}
				if (p.position().add(0, 1.6, 0).distanceTo(new Vec3(pt.x(), pt.y(), pt.z())) < 1.5) {
					p.sendSystemMessage(net.minecraft.network.chat.Component.literal("Kamerapunkt " + (i + 1) + " von " + path.points().size()), true);
				}
			}
		}
	}

	private int smoothCmd(CommandContext<CommandSourceStack> c, int strength) {
		return editPath(c, path -> {
			List<CameraPoint> pts = path.points();
			if (pts.size() < 3) {
				return path;
			}
			List<CameraPoint> cur = new ArrayList<>(pts);
			for (int pass = 0; pass < strength; pass++) {
				List<CameraPoint> next = new ArrayList<>(cur);
				for (int i = 1; i < cur.size() - 1; i++) {
					CameraPoint a = cur.get(i - 1), m = cur.get(i), b = cur.get(i + 1);
					float yaw = (float) (m.yaw() + 0.5 * (net.minecraft.util.Mth.wrapDegrees(a.yaw() - m.yaw()) + net.minecraft.util.Mth.wrapDegrees(b.yaw() - m.yaw())) / 2);
					next.set(i, new CameraPoint((a.x() + 2 * m.x() + b.x()) / 4, (a.y() + 2 * m.y() + b.y()) / 4, (a.z() + 2 * m.z() + b.z()) / 4,
							yaw, (a.pitch() + 2 * m.pitch() + b.pitch()) / 4, (a.roll() + 2 * m.roll() + b.roll()) / 4, m.fov(), m.ticks(), m.easing(),
							m.handleIn(), m.handleOut(), m.focus()));
				}
				cur = next;
			}
			return path.withPoints(cur);
		});
	}

	private int egoCmd(CommandContext<CommandSourceStack> c, double seconds, boolean hasTargets) throws CommandSyntaxException {
		Entity target = EntityArgument.getEntity(c, "target");
		playFollow("", 0, target, "ego", 0, 0, parseSide("+z"), null, 0.5, seconds < 0 ? -1 : (int) Math.round(seconds * 20),
				CommandUtil.targets(c, hasTargets));
		return Feedback.ok(c.getSource(), "Ego-Kamera: " + target.getName().getString() + " · /btb camera stop");
	}

	private int povCmd(CommandContext<CommandSourceStack> c, double seconds, boolean hasTargets) throws CommandSyntaxException {
		Entity target = EntityArgument.getEntity(c, "target");
		playFollow("", 0, target, "pov", 0, 0, parseSide("+z"), null, 0.5, (int) Math.round(seconds * 20), CommandUtil.targets(c, hasTargets));
		return Feedback.ok(c.getSource(), "Sicht von " + target.getName().getString() + " für " + seconds + " s");
	}

	private int autoBeatCmd(CommandContext<CommandSourceStack> c, boolean hasTargets) throws CommandSyntaxException {
		Entity target = EntityArgument.getEntity(c, "target");
		double seconds = 60.0 / DoubleArgumentType.getDouble(c, "bpm") * IntegerArgumentType.getInteger(c, "beats");
		playFollow("", 0, target, "auto", 7, Math.max(0.5, seconds), parseSide("+z"), null, 0.12, -1, CommandUtil.targets(c, hasTargets));
		return Feedback.ok(c.getSource(), String.format(Locale.ROOT, "Auto-Regie im Takt: Schnitt alle %.2f s", seconds));
	}

	private int startRecording(CommandContext<CommandSourceStack> c, int interval) throws CommandSyntaxException {
		String id = StringArgumentType.getString(c, "id");
		if (!SceneLoader.ID_PATTERN.matcher(id).matches()) {
			return Feedback.fail(c.getSource(), "Id must match [a-z0-9_]");
		}
		ServerPlayer p = c.getSource().getPlayerOrException();
		recordings.put(p.getUUID(), new Recording(id, interval));
		return Feedback.ok(c.getSource(), "Recording camera '" + id + "' — fly the move (spectator works best), then /btb camera record stop");
	}

	/** Merges samples where the camera barely moved, so standing still becomes one longer hold. */
	private static List<CameraPoint> simplify(List<CameraPoint> raw) {
		List<CameraPoint> out = new ArrayList<>();
		for (CameraPoint p : raw) {
			if (!out.isEmpty()) {
				CameraPoint last = out.get(out.size() - 1);
				double d = Math.sqrt(Math.pow(p.x() - last.x(), 2) + Math.pow(p.y() - last.y(), 2) + Math.pow(p.z() - last.z(), 2));
				if (d < 0.05 && Math.abs(p.yaw() - last.yaw()) < 1 && Math.abs(p.pitch() - last.pitch()) < 1) {
					out.set(out.size() - 1, last.withTicks(last.ticks() + p.ticks()));
					continue;
				}
			}
			out.add(p);
		}
		return out;
	}

	private int shakeCmd(CommandContext<CommandSourceStack> c, boolean hasTargets) throws CommandSyntaxException {
		shake("", 0, FloatArgumentType.getFloat(c, "intensity"), CommandUtil.ticks(DoubleArgumentType.getDouble(c, "seconds")),
				1f, CommandUtil.targets(c, hasTargets));
		return Feedback.ok(c.getSource(), "Camera shake");
	}

	private int play(CommandContext<CommandSourceStack> c, boolean hasTargets, Entity lookAt) throws CommandSyntaxException {
		CameraPath path = presets.get(StringArgumentType.getString(c, "id"));
		if (path == null) {
			return Feedback.fail(c.getSource(), "Unknown camera preset");
		}
		if (path.points().size() < 2) {
			return Feedback.fail(c.getSource(), "Preset needs at least 2 points");
		}
		ShotOptions opt = ShotOptions.DEFAULT;
		if (lookAt != null) {
			opt = new ShotOptions(null, null, false, true);
		}
		JsonObject shot = new JsonObject();
		Collection<ServerPlayer> targets = CommandUtil.targets(c, hasTargets);
		playPath("", 0, path, targets, opt);
		if (lookAt != null) {
			// Entity look-at from a command: send a follow-up cue with the entity id.
			shot.addProperty("entity", lookAt.getId());
			engine.cues().queue("", 0, "camera_look_at", shot, targets);
		}
		return Feedback.ok(c.getSource(), String.format(Locale.ROOT, "Playing camera '%s' (%.1f s)", path.id(),
				path.durationTicks() / 20.0));
	}

	@FunctionalInterface
	private interface PointEdit {
		CameraPath apply(CameraPath path, int index);
	}

	private int edit(CommandContext<CommandSourceStack> c, PointEdit edit) {
		CameraPath path = presets.get(StringArgumentType.getString(c, "id"));
		if (path == null) {
			return Feedback.fail(c.getSource(), "Unknown camera preset");
		}
		int index = IntegerArgumentType.getInteger(c, "index") - 1;
		if (index >= path.points().size()) {
			return Feedback.fail(c.getSource(), "Point " + (index + 1) + " does not exist (preset has " + path.points().size() + ")");
		}
		try {
			CameraPath updated = edit.apply(path, index);
			presets.put(updated.id(), updated);
			save(updated);
			return Feedback.ok(c.getSource(), "Camera '" + path.id() + "' updated");
		} catch (IllegalArgumentException e) {
			return Feedback.fail(c.getSource(), e.getMessage());
		}
	}

	private int editPath(CommandContext<CommandSourceStack> c, java.util.function.UnaryOperator<CameraPath> op) {
		CameraPath path = presets.get(StringArgumentType.getString(c, "id"));
		if (path == null) {
			return Feedback.fail(c.getSource(), "Unknown camera preset");
		}
		try {
			CameraPath updated = op.apply(path);
			presets.put(updated.id(), updated);
			save(updated);
			return Feedback.ok(c.getSource(), "Camera '" + path.id() + "' updated");
		} catch (IllegalArgumentException e) {
			return Feedback.fail(c.getSource(), e.getMessage());
		}
	}

	private int setPoint(CommandContext<CommandSourceStack> c, int index) throws CommandSyntaxException {
		String id = StringArgumentType.getString(c, "id");
		CameraPath path = presets.get(id);
		if (path == null) {
			return Feedback.fail(c.getSource(), "Unknown camera preset '" + id + "' (create it first)");
		}
		ServerPlayer player = c.getSource().getPlayerOrException();
		List<CameraPoint> pts = new ArrayList<>(path.points());
		int i = index < 0 ? pts.size() : index - 1;
		if (i > pts.size()) {
			return Feedback.fail(c.getSource(), "Point index can be at most " + (pts.size() + 1));
		}
		CameraPoint point = pointFrom(player);
		if (i < pts.size()) {
			CameraPoint old = pts.get(i);
			pts.set(i, new CameraPoint(point.x(), point.y(), point.z(), point.yaw(), point.pitch(), old.roll(), old.fov(),
					old.ticks(), old.easing(), old.handleIn(), old.handleOut()));
		} else {
			pts.add(point);
		}
		CameraPath updated = path.withPoints(pts);
		presets.put(id, updated);
		save(updated);
		return Feedback.ok(c.getSource(), "Point " + (i + 1) + " of '" + id + "' set to your current view ("
				+ pts.size() + " points, " + String.format(Locale.ROOT, "%.1f s", updated.durationTicks() / 20.0) + ")");
	}

	private static CameraPath replace(CameraPath path, int i, CameraPoint point) {
		List<CameraPoint> pts = new ArrayList<>(path.points());
		pts.set(i, point);
		return path.withPoints(pts);
	}

	private static CameraPoint pointFrom(ServerPlayer player) {
		return new CameraPoint(round(player.getX()), round(player.getEyeY()), round(player.getZ()),
				Math.round(player.getYRot() * 10f) / 10f, Math.round(player.getXRot() * 10f) / 10f, 0, 0,
				CameraPoint.DEFAULT_TICKS, Easing.LINEAR, null, null);
	}

	private static double round(double v) {
		return Math.round(v * 100.0) / 100.0;
	}
}
