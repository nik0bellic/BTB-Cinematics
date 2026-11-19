package de.behindtheblocks.cinematic.ambience;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import de.behindtheblocks.cinematic.BtbCinematic;
import de.behindtheblocks.cinematic.camera.CameraManager;
import de.behindtheblocks.cinematic.command.CommandContributor;
import de.behindtheblocks.cinematic.command.Feedback;
import de.behindtheblocks.cinematic.core.CinematicEngine;
import de.behindtheblocks.cinematic.core.CinematicModule;
import de.behindtheblocks.cinematic.event.builtin.BuiltinEvents;
import de.behindtheblocks.cinematic.json.JsonParamException;
import de.behindtheblocks.cinematic.json.JsonParams;
import de.behindtheblocks.cinematic.timeline.TimelineManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Ambience zones: a place that "lives" while players are near — a recurring sound (e.g. a deep
 * heartbeat with echo), rising/falling particles and a faint tremor. Stored in
 * {@code config/btb_cinematic/ambience/<id>.json}:
 *
 * <pre>{@code
 * { "dimension": "minecraft:overworld", "pos": [x,y,z], "radius": 40, "enabled": true,
 *   "sound": "btb_cinematic:heart_pulse", "sound_interval": 32, "sound_volume": 2.5, "sound_pitch": 0.75,
 *   "echo": {"count": 2, "delay": 10, "decay": 0.45},
 *   "particles": [ {"particle": "minecraft:soul", "count": 3, "spread": [6, 1, 6], "speed": 0.02, "y": 0} ],
 *   "shake_intensity": 0.12, "shake_interval": 32 }
 * }</pre>
 */
public final class AmbienceManager implements CinematicModule, CommandContributor {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/ambience");

	private record Emitter(ParticleOptions particle, int count, double sx, double sy, double sz, double speed, double y) {
	}

	private record Zone(String id, Identifier dimension, Vec3 pos, double radius, boolean enabled, Identifier sound, int soundInterval,
			float volume, float pitch, JsonObject echo, List<Emitter> emitters, float shake, int shakeInterval, List<Orbit> orbits,
			int flashColor, int flashInterval, int gradeColor, float gradeAlpha, float haze, boolean beatNear) {
	}

	/** Per player: which zone's colour grade / heat shimmer is currently applied ("" = none). */
	private final Map<java.util.UUID, String> gradeOf = new java.util.HashMap<>();
	private final Map<java.util.UUID, String> hazeOf = new java.util.HashMap<>();
	private final Map<java.util.UUID, Integer> beatTimer = new java.util.HashMap<>();

	/** Levitating blocks circling the zone centre (e.g. rubble around a tower crown). */
	private record Orbit(net.minecraft.world.level.block.state.BlockState block, int count, double radius, double height,
			double speed, float scale) {
	}

	private static final String ORBIT_TAG = "btb_ambience";
	private static final int ORBIT_INTERVAL = 4;
	private final Map<String, List<net.minecraft.world.entity.Display.BlockDisplay>> orbiters = new TreeMap<>();
	private final java.util.Set<java.util.UUID> ownedOrbiters = new java.util.HashSet<>();

	private final Map<String, JsonObject> definitions = new TreeMap<>();
	private final Map<String, Zone> zones = new TreeMap<>();
	private CinematicEngine engine;
	private int clock;

	@Override
	public String id() {
		return "ambience";
	}

	public static Path directory() {
		return FabricLoader.getInstance().getConfigDir().resolve(BtbCinematic.MOD_ID).resolve("ambience");
	}

	@Override
	public void initialize(CinematicEngine engine) {
		this.engine = engine;
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity.entityTags().contains(ORBIT_TAG) && !ownedOrbiters.contains(entity.getUUID())) {
				entity.discard();
			}
		});
		engine.events().register(BuiltinEvents.simple("ambience", "Switches an ambience zone on or off. {id, enabled}", p -> {
			String id = p.requireString("id");
			boolean on = p.getBoolean("enabled", true);
			return ctx -> setEnabled(id, on);
		}));
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		reload();
	}

	public int reload() {
		definitions.clear();
		zones.clear();
		try {
			Files.createDirectories(directory());
			try (Stream<Path> files = Files.list(directory())) {
				for (Path f : files.filter(x -> x.toString().endsWith(".json")).sorted().toList()) {
					String name = f.getFileName().toString();
					String id = name.substring(0, name.length() - 5);
					try {
						JsonReader reader = new JsonReader(new StringReader(Files.readString(f, StandardCharsets.UTF_8)));
						reader.setStrictness(Strictness.LENIENT);
						JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
						zones.put(id, parse(id, json));
						definitions.put(id, json);
					} catch (RuntimeException e) {
						LOG.warn("Ambience {} invalid: {}", name, e.getMessage());
					}
				}
			}
		} catch (IOException e) {
			LOG.error("Cannot read ambience directory", e);
		}
		return zones.size();
	}

	private static Zone parse(String id, JsonObject json) {
		JsonParams p = new JsonParams(json);
		double[] pos = p.requireDoubleArray("pos", 3);
		Identifier sound = null;
		if (p.has("sound")) {
			sound = Identifier.tryParse(p.requireString("sound"));
			if (sound == null || !BuiltInRegistries.SOUND_EVENT.containsKey(sound)) {
				throw new JsonParamException("unknown sound");
			}
		}
		List<Emitter> emitters = new ArrayList<>();
		if (json.has("particles")) {
			for (JsonElement e : json.getAsJsonArray("particles")) {
				JsonParams ep = new JsonParams(e.getAsJsonObject());
				String raw = ep.requireString("particle");
				ParticleOptions options;
				if (raw.startsWith("dust:")) {
					// coloured dust, e.g. "dust:#FF2000" — used for the blood-red sky beam
					options = new net.minecraft.core.particles.DustParticleOptions(
							de.behindtheblocks.cinematic.util.Colors.parse(raw.substring(5)) & 0xFFFFFF, (float) ep.getDouble("size", 2.5));
				} else {
					Identifier pid = Identifier.tryParse(raw);
					ParticleType<?> type = pid == null ? null : BuiltInRegistries.PARTICLE_TYPE.getValue(pid);
					if (!(type instanceof ParticleOptions o2)) {
						throw new JsonParamException("particle must be a simple particle id or dust:#RRGGBB");
					}
					options = o2;
				}
				double[] spread = ep.has("spread") ? ep.requireDoubleArray("spread", 3) : new double[] {1, 1, 1};
				emitters.add(new Emitter(options, Math.min(32, ep.getInt("count", 2)), spread[0], spread[1], spread[2],
						ep.getDouble("speed", 0.01), ep.getDouble("y", 0)));
			}
		}
		List<Orbit> orbits = new ArrayList<>();
		if (json.has("orbit_blocks")) {
			for (JsonElement e : json.getAsJsonArray("orbit_blocks")) {
				JsonParams op = new JsonParams(e.getAsJsonObject());
				Identifier bid = Identifier.tryParse(op.getString("block", "minecraft:blackstone"));
				if (bid == null || !BuiltInRegistries.BLOCK.containsKey(bid)) {
					throw new JsonParamException("unknown block in orbit_blocks");
				}
				orbits.add(new Orbit(BuiltInRegistries.BLOCK.getValue(bid).defaultBlockState(), Math.min(48, op.getInt("count", 8)),
						op.getDouble("radius", 10), op.getDouble("height", 0), op.getDouble("speed", 0.3), op.getFloat("scale", 1.2f)));
			}
		}
		int flashColor = p.has("flash_color") ? de.behindtheblocks.cinematic.util.Colors.parse(p.getString("flash_color", "dark_red")) : 0;
		return new Zone(id, Identifier.tryParse(p.getString("dimension", "minecraft:overworld")), new Vec3(pos[0], pos[1], pos[2]),
				Math.max(1, Math.min(256, p.getDouble("radius", 32))), p.getBoolean("enabled", true), sound,
				Math.max(5, p.getInt("sound_interval", 40)), p.getFloat("sound_volume", 2f), p.getFloat("sound_pitch", 1f),
				p.getObject("echo").orElse(null), emitters, p.getFloat("shake_intensity", 0f), Math.max(5, p.getInt("shake_interval", 40)),
				List.copyOf(orbits), flashColor, Math.max(5, p.getInt("flash_interval", p.getInt("sound_interval", 40))),
				p.has("grade_color") ? de.behindtheblocks.cinematic.util.Colors.parse(p.getString("grade_color", "black")) : 0,
				p.getFloat("grade_alpha", 0.12f), p.getFloat("haze", 0f), p.getBoolean("beat_near", false));
	}

	/** Stores (and saves) a zone. */
	public void putDefinition(String id, JsonObject json) {
		zones.put(id, parse(id, json));
		definitions.put(id, json);
		try {
			Files.createDirectories(directory());
			de.behindtheblocks.cinematic.studio.Versions.before(directory().resolve(id + ".json"));
			Files.writeString(directory().resolve(id + ".json"), new GsonBuilder().setPrettyPrinting().create().toJson(json),
					StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOG.error("Could not save ambience {}", id, e);
		}
	}

	public java.util.Collection<String> ids() {
		return zones.keySet();
	}

	public boolean isEnabled(String id) {
		JsonObject j = definitions.get(id);
		return j != null && (!j.has("enabled") || j.get("enabled").getAsBoolean());
	}

	public void remove(String id) {
		removeOrbiters(id);
		zones.remove(id);
		definitions.remove(id);
		try {
			Files.deleteIfExists(directory().resolve(id + ".json"));
		} catch (IOException e) {
			LOG.error("Could not delete ambience {}", id, e);
		}
	}

	private boolean setEnabled(String id, boolean on) {
		JsonObject json = definitions.get(id);
		if (json == null) {
			return false;
		}
		json.addProperty("enabled", on);
		putDefinition(id, json);
		if (!on) {
			removeOrbiters(id);
		}
		return true;
	}

	@Override
	public void tick(MinecraftServer server) {
		if (zones.isEmpty()) {
			return;
		}
		clock++;
		if (clock % 5 == 0) {
			tickLooks(server);
		}
		for (Zone z : zones.values()) {
			if (!z.enabled) {
				continue;
			}
			ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, z.dimension));
			if (level == null) {
				continue;
			}
			List<ServerPlayer> near = level.players().stream().filter(p -> p.position().distanceTo(z.pos) <= z.radius).toList();
			if (near.isEmpty()) {
				removeOrbiters(z.id); // zero cost when nobody is around
				continue;
			}
			tickOrbiters(z, level);
			if (z.flashColor != 0 && clock % z.flashInterval == 0) {
				engine.module(de.behindtheblocks.cinematic.effects.EffectManager.class).ifPresent(fx -> fx.send(TimelineManager.MANUAL, 0,
						new de.behindtheblocks.cinematic.effects.EffectSpec(de.behindtheblocks.cinematic.effects.EffectSpec.Kind.FLASH,
								z.flashColor, z.flashColor, 0.28f, 3, 0, 14, ""), near));
			}
			if (clock % 2 == 0) {
				for (Emitter e : z.emitters) {
					level.sendParticles(e.particle, z.pos.x, z.pos.y + e.y, z.pos.z, e.count, e.sx, e.sy, e.sz, e.speed);
				}
			}
			if (z.sound != null && z.beatNear) {
				// the closer someone gets, the faster and louder it beats (each player hears their own rhythm)
				for (ServerPlayer pl : near) {
					double k = Math.min(1, pl.position().distanceTo(z.pos) / z.radius);
					int interval = (int) Math.round(16 + 34 * k);
					int left = beatTimer.merge(pl.getUUID(), -1, Integer::sum);
					if (left <= 0) {
						beatTimer.put(pl.getUUID(), interval);
						JsonObject cue = new JsonObject();
						cue.addProperty("sound", z.sound.toString());
						cue.addProperty("volume", z.volume * (float) (0.25 + 0.95 * (1 - k)));
						cue.addProperty("pitch", z.pitch);
						com.google.gson.JsonArray a = new com.google.gson.JsonArray();
						a.add(z.pos.x);
						a.add(z.pos.y);
						a.add(z.pos.z);
						cue.add("pos", a);
						engine.cues().queue(TimelineManager.MANUAL, 0, "sound_play", cue, List.of(pl));
					}
				}
			} else if (z.sound != null && clock % z.soundInterval == 0) {
				JsonObject cue = new JsonObject();
				cue.addProperty("sound", z.sound.toString());
				cue.addProperty("volume", z.volume);
				cue.addProperty("pitch", z.pitch);
				com.google.gson.JsonArray a = new com.google.gson.JsonArray();
				a.add(z.pos.x);
				a.add(z.pos.y);
				a.add(z.pos.z);
				cue.add("pos", a);
				if (z.echo != null) {
					cue.add("echo", z.echo);
				}
				engine.cues().queue(TimelineManager.MANUAL, 0, "sound_play", cue, near);
			}
			if (z.shake > 0 && clock % z.shakeInterval == 0) {
				engine.module(CameraManager.class).ifPresent(c -> c.shake(TimelineManager.MANUAL, 0, z.shake, 12, 1.2f, near));
			}
		}
	}

	/**
	 * Colour grade and heat shimmer follow the actors: entering a zone eases its look in (2 s),
	 * leaving eases it out. Each section of a set gets its own mood without any button press.
	 */
	/** After a reset (all effects cleared) the zone looks are sent again on the next check. */
	public void resetLooks() {
		gradeOf.clear();
		hazeOf.clear();
	}

	private void tickLooks(MinecraftServer server) {
		var fxm = engine.module(de.behindtheblocks.cinematic.effects.EffectManager.class);
		if (fxm.isEmpty()) {
			return;
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			String grade = "", haze = "";
			Zone gz = null, hz = null;
			for (Zone z : zones.values()) {
				if (!z.enabled || !p.level().dimension().identifier().equals(z.dimension) || p.position().distanceTo(z.pos) > z.radius) {
					continue;
				}
				if (z.gradeColor != 0 && gz == null) {
					gz = z;
					grade = z.id;
				}
				if (z.haze > 0 && hz == null) {
					hz = z;
					haze = z.id;
				}
			}
			var one = List.of(p);
			if (!grade.equals(gradeOf.getOrDefault(p.getUUID(), ""))) {
				gradeOf.put(p.getUUID(), grade);
				fxm.get().send(TimelineManager.MANUAL, 0, gz != null
						? new de.behindtheblocks.cinematic.effects.EffectSpec(de.behindtheblocks.cinematic.effects.EffectSpec.Kind.TINT,
								gz.gradeColor, gz.gradeColor, Math.min(1f, gz.gradeAlpha), 40, -1, 0, "")
						: new de.behindtheblocks.cinematic.effects.EffectSpec(de.behindtheblocks.cinematic.effects.EffectSpec.Kind.CLEAR,
								0, 0, 1, 40, 0, 0, "tint"), one);
			}
			if (!haze.equals(hazeOf.getOrDefault(p.getUUID(), ""))) {
				hazeOf.put(p.getUUID(), haze);
				fxm.get().send(TimelineManager.MANUAL, 0, hz != null
						? new de.behindtheblocks.cinematic.effects.EffectSpec(de.behindtheblocks.cinematic.effects.EffectSpec.Kind.HAZE,
								0xFF000000, 0xFF000000, Math.min(1f, hz.haze), 40, -1, 0, "")
						: new de.behindtheblocks.cinematic.effects.EffectSpec(de.behindtheblocks.cinematic.effects.EffectSpec.Kind.CLEAR,
								0, 0, 1, 40, 0, 0, "haze"), one);
			}
		}
	}

	private void tickOrbiters(Zone z, ServerLevel level) {
		if (z.orbits.isEmpty()) {
			return;
		}
		List<net.minecraft.world.entity.Display.BlockDisplay> list = orbiters.get(z.id);
		if (list == null) {
			list = new ArrayList<>();
			for (Orbit o : z.orbits) {
				for (int i = 0; i < o.count(); i++) {
					var d = new net.minecraft.world.entity.Display.BlockDisplay(net.minecraft.world.entity.EntityTypes.BLOCK_DISPLAY, level);
					d.setPos(z.pos.x, z.pos.y, z.pos.z);
					d.setBlockState(o.block());
					d.setViewRange(3f);
					d.addTag(ORBIT_TAG);
					ownedOrbiters.add(d.getUUID());
					level.addFreshEntity(d);
					list.add(d);
				}
			}
			orbiters.put(z.id, list);
		}
		if (clock % ORBIT_INTERVAL != 0) {
			return;
		}
		int idx = 0;
		for (Orbit o : z.orbits) {
			for (int i = 0; i < o.count(); i++, idx++) {
				var d = list.get(idx);
				double seed = i * 2.399963; // golden angle → irregular, natural-looking spacing
				double a = clock * o.speed() * 0.01 + seed;
				double r = o.radius() * (0.85 + 0.3 * ((i * 37) % 10) / 10.0);
				float y = (float) (o.height() + Math.sin(clock * 0.02 + seed) * 1.5 + ((i * 53) % 7) - 3);
				float s = o.scale() * (0.6f + ((i * 29) % 10) / 12f);
				org.joml.Quaternionf q = new org.joml.Quaternionf().rotationXYZ((float) (clock * 0.004 + seed), (float) (clock * 0.006 + seed * 2), 0);
				d.setTransformationInterpolationDuration(ORBIT_INTERVAL);
				d.setTransformation(new com.mojang.math.Transformation(new org.joml.Vector3f((float) (Math.cos(a) * r), y, (float) (Math.sin(a) * r)),
						q, new org.joml.Vector3f(s), null));
				d.setTransformationInterpolationDelay(0);
			}
		}
	}

	private void removeOrbiters(String id) {
		List<net.minecraft.world.entity.Display.BlockDisplay> list = orbiters.remove(id);
		if (list != null) {
			list.forEach(d -> {
				ownedOrbiters.remove(d.getUUID());
				d.discard();
			});
		}
	}

	@Override
	public void onServerStopping(MinecraftServer server) {
		List.copyOf(orbiters.keySet()).forEach(this::removeOrbiters);
	}

	@Override
	public void contributeCommands(LiteralArgumentBuilder<CommandSourceStack> root, CommandBuildContext buildContext) {
		SuggestionProvider<CommandSourceStack> ids = (c, b) -> SharedSuggestionProvider.suggest(zones.keySet(), b);
		root.then(literal("ambience")
				.then(literal("list").executes(c -> {
					Feedback.ok(c.getSource(), zones.size() + " ambience zone(s):");
					zones.values().forEach(z -> Feedback.info(c.getSource(), " • " + z.id + (z.enabled ? " (on)" : " (off)")));
					return zones.size();
				}))
				.then(literal("reload").executes(c -> Feedback.ok(c.getSource(), "Loaded " + reload() + " zone(s)")))
				.then(literal("on").then(argument("id", StringArgumentType.word()).suggests(ids).executes(c ->
						setEnabled(StringArgumentType.getString(c, "id"), true) ? Feedback.ok(c.getSource(), "Ambience on") : Feedback.fail(c.getSource(), "Unknown zone"))))
				.then(literal("off").then(argument("id", StringArgumentType.word()).suggests(ids).executes(c ->
						setEnabled(StringArgumentType.getString(c, "id"), false) ? Feedback.ok(c.getSource(), "Ambience off") : Feedback.fail(c.getSource(), "Unknown zone"))))
				// ---- build zones without files
				.then(literal("create").then(argument("id", StringArgumentType.word())
						.then(argument("radius", DoubleArgumentType.doubleArg(2, 256)).executes(c -> {
							String id = StringArgumentType.getString(c, "id");
							if (!de.behindtheblocks.cinematic.scene.SceneLoader.ID_PATTERN.matcher(id).matches()) {
								return Feedback.fail(c.getSource(), "Id: only a-z, 0-9 and _");
							}
							JsonObject o = new JsonObject();
							o.addProperty("dimension", c.getSource().getLevel().dimension().identifier().toString());
							var p = c.getSource().getPosition();
							com.google.gson.JsonArray pos = new com.google.gson.JsonArray();
							pos.add(Math.round(p.x * 10) / 10.0);
							pos.add(Math.round(p.y * 10) / 10.0);
							pos.add(Math.round(p.z * 10) / 10.0);
							o.add("pos", pos);
							o.addProperty("radius", DoubleArgumentType.getDouble(c, "radius"));
							o.addProperty("enabled", true);
							putDefinition(id, o);
							return Feedback.ok(c.getSource(), "Zone '" + id + "' created here. Add: sound / particles / shake / flash / orbit");
						}))))
				.then(literal("sound").then(argument("id", StringArgumentType.word()).suggests(ids)
						.then(literal("off").executes(c -> editZone(c, o -> o.remove("sound"))))
						.then(argument("sound", net.minecraft.commands.arguments.IdentifierArgument.id())
								.suggests((c, b) -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.SOUND_EVENT.keySet(), b))
								.then(argument("every_seconds", DoubleArgumentType.doubleArg(0.25, 600))
										.executes(c -> editZone(c, o -> sound(c, o, 2f)))
										.then(argument("volume", FloatArgumentType.floatArg(0, 10))
												.executes(c -> editZone(c, o -> sound(c, o, FloatArgumentType.getFloat(c, "volume")))))))))
				.then(literal("particles").then(argument("id", StringArgumentType.word()).suggests(ids)
						.then(literal("clear").executes(c -> editZone(c, o -> o.add("particles", new com.google.gson.JsonArray()))))
						// e.g. minecraft:soul  or  dust:ff2a00 (coloured dust)
						.then(argument("particle", net.minecraft.commands.arguments.IdentifierArgument.id())
								.suggests((c, b) -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.PARTICLE_TYPE.keySet(), b))
								.then(argument("count", IntegerArgumentType.integer(1, 32))
										.executes(c -> editZone(c, o -> particles(c, o, 6)))
										.then(argument("spread", DoubleArgumentType.doubleArg(0, 64))
												.executes(c -> editZone(c, o -> particles(c, o, DoubleArgumentType.getDouble(c, "spread")))))))))
				.then(literal("shake").then(argument("id", StringArgumentType.word()).suggests(ids)
						.then(argument("intensity", FloatArgumentType.floatArg(0, 3))
								.then(argument("every_seconds", DoubleArgumentType.doubleArg(0.25, 600)).executes(c -> editZone(c, o -> {
									o.addProperty("shake_intensity", FloatArgumentType.getFloat(c, "intensity"));
									o.addProperty("shake_interval", (int) Math.round(DoubleArgumentType.getDouble(c, "every_seconds") * 20));
								}))))))
				.then(literal("flash").then(argument("id", StringArgumentType.word()).suggests(ids)
						.then(literal("off").executes(c -> editZone(c, o -> o.remove("flash_color"))))
						.then(argument("color", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(de.behindtheblocks.cinematic.util.Colors.names(), b))
								.then(argument("every_seconds", DoubleArgumentType.doubleArg(0.25, 600)).executes(c -> editZone(c, o -> {
									String col = StringArgumentType.getString(c, "color");
									de.behindtheblocks.cinematic.util.Colors.parse(col);
									o.addProperty("flash_color", col);
									o.addProperty("flash_interval", (int) Math.round(DoubleArgumentType.getDouble(c, "every_seconds") * 20));
								}))))))
				.then(literal("grade").then(argument("id", StringArgumentType.word()).suggests(ids)
						.then(literal("off").executes(c -> editZone(c, o -> o.remove("grade_color"))))
						.then(argument("color", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(de.behindtheblocks.cinematic.util.Colors.names(), b))
								.then(argument("alpha", FloatArgumentType.floatArg(0.01f, 0.6f)).executes(c -> editZone(c, o -> {
									String col = StringArgumentType.getString(c, "color");
									de.behindtheblocks.cinematic.util.Colors.parse(col);
									o.addProperty("grade_color", col);
									o.addProperty("grade_alpha", FloatArgumentType.getFloat(c, "alpha"));
								}))))))
				.then(literal("beatnear").then(argument("id", StringArgumentType.word()).suggests(ids)
						.then(literal("on").executes(c -> editZone(c, o -> o.addProperty("beat_near", true))))
						.then(literal("off").executes(c -> editZone(c, o -> o.addProperty("beat_near", false))))))
				.then(literal("haze").then(argument("id", StringArgumentType.word()).suggests(ids)
						.then(literal("off").executes(c -> editZone(c, o -> o.remove("haze"))))
						.then(argument("strength", FloatArgumentType.floatArg(0.05f, 1f)).executes(c -> editZone(c, o ->
								o.addProperty("haze", FloatArgumentType.getFloat(c, "strength")))))))
				.then(literal("orbit").then(argument("id", StringArgumentType.word()).suggests(ids)
						.then(literal("clear").executes(c -> editZone(c, o -> o.add("orbit_blocks", new com.google.gson.JsonArray()))))
						.then(argument("block", net.minecraft.commands.arguments.IdentifierArgument.id())
								.suggests((c, b) -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.BLOCK.keySet(), b))
								.then(argument("count", IntegerArgumentType.integer(1, 64))
										.then(argument("radius", DoubleArgumentType.doubleArg(1, 128))
												.then(argument("height", DoubleArgumentType.doubleArg(-64, 256)).executes(c -> editZone(c, o -> {
													if (!o.has("orbit_blocks")) {
														o.add("orbit_blocks", new com.google.gson.JsonArray());
													}
													JsonObject b = new JsonObject();
													b.addProperty("block", net.minecraft.commands.arguments.IdentifierArgument.getId(c, "block").toString());
													b.addProperty("count", IntegerArgumentType.getInteger(c, "count"));
													b.addProperty("radius", DoubleArgumentType.getDouble(c, "radius"));
													b.addProperty("height", DoubleArgumentType.getDouble(c, "height"));
													o.getAsJsonArray("orbit_blocks").add(b);
												}))))))))
				.then(literal("delete").then(argument("id", StringArgumentType.word()).suggests(ids).executes(c -> {
					String id = StringArgumentType.getString(c, "id");
					if (!definitions.containsKey(id)) {
						return Feedback.fail(c.getSource(), "Unknown zone");
					}
					remove(id);
					return Feedback.ok(c.getSource(), "Zone '" + id + "' deleted");
				}))));
	}

	private static void sound(com.mojang.brigadier.context.CommandContext<CommandSourceStack> c, JsonObject o, float volume) {
		o.addProperty("sound", net.minecraft.commands.arguments.IdentifierArgument.getId(c, "sound").toString());
		o.addProperty("sound_interval", (int) Math.round(DoubleArgumentType.getDouble(c, "every_seconds") * 20));
		o.addProperty("sound_volume", volume);
	}

	private static void particles(com.mojang.brigadier.context.CommandContext<CommandSourceStack> c, JsonObject o, double spread) {
		Identifier id = net.minecraft.commands.arguments.IdentifierArgument.getId(c, "particle");
		if (!o.has("particles")) {
			o.add("particles", new com.google.gson.JsonArray());
		}
		JsonObject e = new JsonObject();
		e.addProperty("particle", id.getNamespace().equals("dust") ? "dust:" + id.getPath() : id.toString());
		e.addProperty("count", IntegerArgumentType.getInteger(c, "count"));
		com.google.gson.JsonArray sp = new com.google.gson.JsonArray();
		sp.add(spread);
		sp.add(Math.max(1, spread / 3));
		sp.add(spread);
		e.add("spread", sp);
		e.addProperty("speed", 0.02);
		o.getAsJsonArray("particles").add(e);
	}

	private int editZone(com.mojang.brigadier.context.CommandContext<CommandSourceStack> c, java.util.function.Consumer<JsonObject> edit) {
		String id = StringArgumentType.getString(c, "id");
		JsonObject o = definitions.get(id);
		if (o == null) {
			return Feedback.fail(c.getSource(), "Unknown zone '" + id + "'. Create: /btb ambience create " + id + " <radius>");
		}
		JsonObject copy = o.deepCopy();
		try {
			edit.accept(copy);
			parse(id, copy);
		} catch (RuntimeException e) {
			return Feedback.fail(c.getSource(), e.getMessage());
		}
		removeOrbiters(id);
		putDefinition(id, copy);
		return Feedback.ok(c.getSource(), "Zone '" + id + "' updated");
	}
}
