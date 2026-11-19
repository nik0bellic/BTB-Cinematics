package de.behindtheblocks.cinematic.parkour;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import de.behindtheblocks.cinematic.BtbCinematic;
import de.behindtheblocks.cinematic.camera.CameraManager;
import de.behindtheblocks.cinematic.command.CommandContributor;
import de.behindtheblocks.cinematic.command.Feedback;
import de.behindtheblocks.cinematic.core.CinematicEngine;
import de.behindtheblocks.cinematic.core.CinematicModule;
import de.behindtheblocks.cinematic.core.bus.SceneMessages;
import de.behindtheblocks.cinematic.effects.EffectManager;
import de.behindtheblocks.cinematic.effects.EffectSpec;
import de.behindtheblocks.cinematic.event.builtin.BuiltinEvents;
import de.behindtheblocks.cinematic.json.JsonParamException;
import de.behindtheblocks.cinematic.json.JsonParams;
import de.behindtheblocks.cinematic.scene.SceneLoader;
import de.behindtheblocks.cinematic.sound.BtbSounds;
import de.behindtheblocks.cinematic.sound.SoundManager;
import de.behindtheblocks.cinematic.timeline.TimelineManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Jump'n'Run sequences for filming.
 *
 * <p>A course is a list of checkpoints. While a course runs, actors who fall below {@code fall_y}
 * or touch lava are faded out and put back on their last checkpoint (no death, no lost take);
 * they get invisible fire resistance and resistance. Each checkpoint may switch the camera of the
 * camera operators (side view, chase, fixed, top) when the followed actor reaches it — the
 * "multiple perspective changes" of a platformer sequence, without touching a menu.
 *
 * <pre>{@code
 * { "dimension": "minecraft:the_nether", "fall_y": 40, "follow": "ActorName", "camera_players": ["Director"],
 *   "checkpoints": [ {"pos": [x,y,z], "radius": 2.5, "camera": {"mode": "side", "distance": 9, "height": 2, "side": "+z"}}, … ] }
 * }</pre>
 */
public final class ParkourManager implements CinematicModule, CommandContributor {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/parkour");

	private record Checkpoint(Vec3 pos, double radius, JsonObject camera) {
	}

	private record Course(String id, Identifier dimension, List<Checkpoint> checkpoints, double fallY, String follow,
			List<String> cameraPlayers) {
	}

	private static final class Run {
		final Course course;
		final String sceneId;
		final Map<UUID, Integer> reached = new HashMap<>();
		final Map<UUID, Integer> cooldown = new HashMap<>();
		int cameraCheckpoint = -1;
		int ticks;

		Run(Course course, String sceneId) {
			this.course = course;
			this.sceneId = sceneId;
		}
	}

	private final Map<String, JsonObject> definitions = new TreeMap<>();
	private final Map<String, Run> runs = new HashMap<>();
	private CinematicEngine engine;
	private MinecraftServer server;

	@Override
	public String id() {
		return "parkour";
	}

	public static Path directory() {
		return FabricLoader.getInstance().getConfigDir().resolve(BtbCinematic.MOD_ID).resolve("parkour");
	}

	@Override
	public void initialize(CinematicEngine engine) {
		this.engine = engine;
		engine.bus().subscribe(SceneMessages.Reset.class, m -> List.copyOf(runs.keySet()).stream()
				.filter(id -> runs.get(id).sceneId.equals(m.sceneId())).forEach(this::stop));
		engine.events().register(BuiltinEvents.simple("parkour_start", "Starts a jump'n'run course. {id}", p -> {
			String id = p.requireString("id");
			return ctx -> {
				String err = start(id, ctx.sceneId());
				if (err != null) {
					throw new IllegalStateException(err);
				}
			};
		}));
		engine.events().register(BuiltinEvents.simple("parkour_stop", "Stops a jump'n'run course. {id}", p -> {
			String id = p.requireString("id");
			return ctx -> stop(id);
		}));
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		this.server = server;
		reload();
	}

	@Override
	public void onServerStopping(MinecraftServer server) {
		List.copyOf(runs.keySet()).forEach(this::stop);
		this.server = null;
	}

	public int reload() {
		definitions.clear();
		try {
			Files.createDirectories(directory());
			try (Stream<Path> files = Files.list(directory())) {
				for (Path f : files.filter(x -> x.toString().endsWith(".json")).sorted().toList()) {
					String name = f.getFileName().toString();
					String id = name.substring(0, name.length() - 5);
					try {
						JsonReader r = new JsonReader(new StringReader(Files.readString(f, StandardCharsets.UTF_8)));
						r.setStrictness(Strictness.LENIENT);
						JsonObject json = JsonParser.parseReader(r).getAsJsonObject();
						parse(id, json);
						definitions.put(id, json);
					} catch (RuntimeException e) {
						LOG.warn("Course {} invalid: {}", name, e.getMessage());
					}
				}
			}
		} catch (IOException e) {
			LOG.error("Cannot read parkour directory", e);
		}
		return definitions.size();
	}

	private static Course parse(String id, JsonObject json) {
		JsonParams p = new JsonParams(json);
		List<Checkpoint> cps = new ArrayList<>();
		if (json.has("checkpoints")) {
			for (JsonElement e : json.getAsJsonArray("checkpoints")) {
				JsonParams c = new JsonParams(e.getAsJsonObject());
				double[] pos = c.requireDoubleArray("pos", 3);
				cps.add(new Checkpoint(new Vec3(pos[0], pos[1], pos[2]), Math.max(0.5, c.getDouble("radius", 2.5)), c.getObject("camera").orElse(null)));
			}
		}
		return new Course(id, Identifier.tryParse(p.getString("dimension", "minecraft:overworld")), cps, p.getDouble("fall_y", -1000),
				p.getString("follow", ""), p.getStringList("camera_players"));
	}

	private void save(String id) {
		try {
			Files.createDirectories(directory());
			de.behindtheblocks.cinematic.studio.Versions.before(directory().resolve(id + ".json"));
			Files.writeString(directory().resolve(id + ".json"), new GsonBuilder().setPrettyPrinting().create().toJson(definitions.get(id)),
					StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOG.error("Could not save course {}", id, e);
		}
	}

	public java.util.Collection<String> ids() {
		return definitions.keySet();
	}

	public boolean running(String id) {
		return runs.containsKey(id);
	}

	// ------------------------------------------------------------------ run

	public String start(String id, String sceneId) {
		JsonObject json = definitions.get(id);
		if (json == null) {
			return "Unknown course '" + id + "'";
		}
		Course c = parse(id, json);
		if (c.checkpoints().isEmpty()) {
			return "Course '" + id + "' has no checkpoints";
		}
		runs.put(id, new Run(c, sceneId));
		LOG.info("Course '{}' started ({} checkpoints)", id, c.checkpoints().size());
		return null;
	}

	public void stop(String id) {
		Run r = runs.remove(id);
		if (r != null && server != null && !r.course.cameraPlayers().isEmpty()) {
			engine.module(CameraManager.class).ifPresent(cam -> cam.stop(r.sceneId, 0, cameraPlayers(r.course)));
		}
	}

	private List<ServerPlayer> cameraPlayers(Course c) {
		List<ServerPlayer> out = new ArrayList<>();
		for (String n : c.cameraPlayers()) {
			ServerPlayer p = server.getPlayerList().getPlayerByName(n);
			if (p != null) {
				out.add(p);
			}
		}
		return out;
	}

	@Override
	public void tick(MinecraftServer server) {
		if (runs.isEmpty()) {
			return;
		}
		for (Run r : List.copyOf(runs.values())) {
			ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, r.course.dimension()));
			if (level == null) {
				continue;
			}
			r.ticks++;
			r.cooldown.replaceAll((k, v) -> v - 1);
			for (ServerPlayer p : level.players()) {
				String name = p.getName().getString();
				// Camera operators are not runners, unless they film themselves (solo creator: follow == own name).
				if (p.isSpectator() || (r.course.cameraPlayers().contains(name) && !r.course.follow().equals(name))) {
					continue;
				}
				if (r.ticks % 20 == 1) { // invisible protection: falls and lava must never end a take
					p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 60, 0, false, false, false));
					p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 60, 4, false, false, false));
				}
				checkCheckpoints(r, level, p);
				if (r.cooldown.getOrDefault(p.getUUID(), 0) <= 0 && (p.getY() < r.course.fallY() || p.isInLava())) {
					respawn(r, p);
				}
			}
		}
	}

	private void checkCheckpoints(Run r, ServerLevel level, ServerPlayer p) {
		List<Checkpoint> cps = r.course.checkpoints();
		int current = r.reached.getOrDefault(p.getUUID(), -1);
		for (int i = current + 1; i < cps.size(); i++) {
			if (p.position().distanceTo(cps.get(i).pos()) <= cps.get(i).radius()) {
				r.reached.put(p.getUUID(), i);
				Vec3 c = cps.get(i).pos();
				level.sendParticles(ParticleTypes.END_ROD, c.x, c.y + 0.3, c.z, 12, 0.4, 0.2, 0.4, 0.03);
				boolean isFollowed = r.course.follow().isEmpty() || r.course.follow().equals(p.getName().getString());
				if (isFollowed && cps.get(i).camera() != null && i != r.cameraCheckpoint) {
					r.cameraCheckpoint = i;
					switchCamera(r, cps.get(i).camera(), p);
				}
				if (i == cps.size() - 1) {
					LOG.info("{} finished course '{}' in {} s", p.getName().getString(), r.course.id(), r.ticks / 20.0);
				}
				break;
			}
		}
	}

	private void switchCamera(Run r, JsonObject cam, ServerPlayer target) {
		List<ServerPlayer> ops = cameraPlayers(r.course);
		if (ops.isEmpty()) {
			return;
		}
		JsonParams c = new JsonParams(cam);
		String mode = c.getString("mode", "side");
		Vec3 fixed = null;
		if (c.has("pos")) {
			double[] a = c.requireDoubleArray("pos", 3);
			fixed = new Vec3(a[0], a[1], a[2]);
		}
		Vec3 sideVec = CameraManager.parseSide(c.getString("side", "+z"));
		Vec3 fixedPos = fixed;
		engine.module(CameraManager.class).ifPresent(m -> m.playFollow(r.sceneId, 0, target, mode, c.getDouble("distance", 9),
				c.getDouble("height", 2), sideVec, fixedPos, c.getDouble("smoothing", 0.15), -1, ops));
	}

	private void respawn(Run r, ServerPlayer p) {
		int idx = Math.max(0, r.reached.getOrDefault(p.getUUID(), 0));
		Vec3 back = r.course.checkpoints().get(idx).pos();
		r.cooldown.put(p.getUUID(), 30);
		List<ServerPlayer> only = List.of(p);
		engine.module(EffectManager.class).ifPresent(fx -> fx.send(TimelineManager.MANUAL, 0,
				new EffectSpec(EffectSpec.Kind.FADE, 0xFF000000, 0xFF000000, 1, 5, 6, 10, ""), only));
		engine.timeline().schedule(TimelineManager.MANUAL, "parkour respawn", 6, () -> {
			p.clearFire();
			p.resetFallDistance();
			p.setDeltaMovement(Vec3.ZERO);
			p.teleportTo(p.level(), back.x, back.y, back.z, Set.of(), p.getYRot(), p.getXRot(), true);
		});
		engine.module(SoundManager.class).ifPresent(s -> s.play(TimelineManager.MANUAL, 0, BtbSounds.WHOOSH.location(), back, 0.6f, 1.3f));
	}

	// ------------------------------------------------------------------ commands

	@Override
	public void contributeCommands(LiteralArgumentBuilder<CommandSourceStack> root, CommandBuildContext buildContext) {
		SuggestionProvider<CommandSourceStack> ids = (c, b) -> SharedSuggestionProvider.suggest(definitions.keySet(), b);
		LiteralArgumentBuilder<CommandSourceStack> pk = literal("parkour");
		pk.then(literal("list").executes(c -> Feedback.ok(c.getSource(), definitions.size() + " course(s): " + String.join(", ", definitions.keySet()))));
		pk.then(literal("create").then(argument("id", StringArgumentType.word()).executes(c -> {
			String id = StringArgumentType.getString(c, "id");
			if (!SceneLoader.ID_PATTERN.matcher(id).matches()) {
				return Feedback.fail(c.getSource(), "Id must match [a-z0-9_]");
			}
			JsonObject o = new JsonObject();
			o.addProperty("dimension", c.getSource().getLevel().dimension().identifier().toString());
			o.addProperty("fall_y", c.getSource().getPosition().y - 12);
			o.add("checkpoints", new JsonArray());
			definitions.put(id, o);
			save(id);
			return Feedback.ok(c.getSource(), "Course '" + id + "' created. Stand on each checkpoint: /btb parkour checkpoint " + id);
		})));
		pk.then(literal("checkpoint").then(argument("id", StringArgumentType.word()).suggests(ids)
				.executes(c -> addCheckpoint(c, 2.5))
				.then(argument("radius", DoubleArgumentType.doubleArg(0.5, 32)).executes(c -> addCheckpoint(c, DoubleArgumentType.getDouble(c, "radius"))))));
		pk.then(literal("camera").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("index", IntegerArgumentType.integer(1, 999))
						.then(argument("mode", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("side", "behind", "fixed", "top", "front", "shoulder", "low", "crane", "orbit", "auto", "pov", "ego", "none"), b))
								.executes(c -> cameraCmd(c, 9, 2, "+z"))
								.then(argument("distance", DoubleArgumentType.doubleArg(0.5, 128))
										.then(argument("height", DoubleArgumentType.doubleArg(-64, 64))
												.executes(c -> cameraCmd(c, DoubleArgumentType.getDouble(c, "distance"), DoubleArgumentType.getDouble(c, "height"), "+z"))
												.then(argument("side", StringArgumentType.word())
														.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("+x", "-x", "+z", "-z"), b))
														.executes(c -> cameraCmd(c, DoubleArgumentType.getDouble(c, "distance"),
																DoubleArgumentType.getDouble(c, "height"), StringArgumentType.getString(c, "side"))))))))));
		pk.then(literal("follow").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("player", StringArgumentType.word()).executes(c -> edit(c, o -> o.addProperty("follow", StringArgumentType.getString(c, "player")))))));
		pk.then(literal("cameraman").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("players", StringArgumentType.greedyString()).executes(c -> edit(c, o -> {
					JsonArray a = new JsonArray();
					for (String n : StringArgumentType.getString(c, "players").split("[ ,]+")) {
						if (!n.isBlank()) {
							a.add(n);
						}
					}
					o.add("camera_players", a);
				})))));
		pk.then(literal("fally").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("y", DoubleArgumentType.doubleArg(-2048, 2048)).executes(c -> edit(c, o -> o.addProperty("fall_y", DoubleArgumentType.getDouble(c, "y")))))));
		pk.then(literal("start").then(argument("id", StringArgumentType.word()).suggests(ids).executes(c -> {
			String err = start(StringArgumentType.getString(c, "id"), TimelineManager.MANUAL);
			return err == null ? Feedback.ok(c.getSource(), "Course running") : Feedback.fail(c.getSource(), err);
		})));
		pk.then(literal("stop").then(argument("id", StringArgumentType.word()).suggests(ids).executes(c -> {
			stop(StringArgumentType.getString(c, "id"));
			return Feedback.ok(c.getSource(), "Course stopped");
		})));
		pk.then(literal("delete").then(argument("id", StringArgumentType.word()).suggests(ids).executes(c -> {
			String id = StringArgumentType.getString(c, "id");
			stop(id);
			definitions.remove(id);
			try {
				Files.deleteIfExists(directory().resolve(id + ".json"));
			} catch (IOException e) {
				LOG.error("delete failed", e);
			}
			return Feedback.ok(c.getSource(), "Course deleted");
		})));
		root.then(pk);
	}

	private int addCheckpoint(CommandContext<CommandSourceStack> c, double radius) {
		Vec3 p = c.getSource().getPosition();
		return edit(c, o -> {
			JsonArray cps = o.has("checkpoints") ? o.getAsJsonArray("checkpoints") : new JsonArray();
			JsonObject cp = new JsonObject();
			JsonArray pos = new JsonArray();
			pos.add(Math.round(p.x * 10) / 10.0);
			pos.add(Math.round(p.y * 10) / 10.0);
			pos.add(Math.round(p.z * 10) / 10.0);
			cp.add("pos", pos);
			cp.addProperty("radius", radius);
			cps.add(cp);
			o.add("checkpoints", cps);
		});
	}

	private int cameraCmd(CommandContext<CommandSourceStack> c, double distance, double height, String side) {
		int index = IntegerArgumentType.getInteger(c, "index") - 1;
		String mode = StringArgumentType.getString(c, "mode");
		return edit(c, o -> {
			JsonArray cps = o.getAsJsonArray("checkpoints");
			if (cps == null || index >= cps.size()) {
				throw new JsonParamException("checkpoint " + (index + 1) + " does not exist");
			}
			JsonObject cp = cps.get(index).getAsJsonObject();
			if (mode.equals("none")) {
				cp.remove("camera");
				return;
			}
			if (!List.of("side", "behind", "fixed", "top", "front", "shoulder", "low", "crane", "orbit", "auto", "pov", "ego").contains(mode)) {
				throw new JsonParamException("mode: side, behind, fixed, top or none");
			}
			CameraManager.parseSide(side);
			JsonObject cam = new JsonObject();
			cam.addProperty("mode", mode);
			cam.addProperty("distance", distance);
			cam.addProperty("height", height);
			cam.addProperty("side", side);
			if (mode.equals("fixed")) {
				Vec3 p = c.getSource().getPosition().add(0, 1.6, 0);
				JsonArray pos = new JsonArray();
				pos.add(p.x);
				pos.add(p.y);
				pos.add(p.z);
				cam.add("pos", pos);
			}
			cp.add("camera", cam);
		});
	}

	private int edit(CommandContext<CommandSourceStack> c, Consumer<JsonObject> op) {
		String id = StringArgumentType.getString(c, "id");
		JsonObject current = definitions.get(id);
		if (current == null) {
			return Feedback.fail(c.getSource(), "Unknown course '" + id + "'");
		}
		JsonObject copy = current.deepCopy();
		try {
			op.accept(copy);
			parse(id, copy);
		} catch (JsonParamException e) {
			return Feedback.fail(c.getSource(), e.getMessage());
		}
		definitions.put(id, copy);
		save(id);
		return Feedback.ok(c.getSource(), "Course '" + id + "' updated");
	}
}
