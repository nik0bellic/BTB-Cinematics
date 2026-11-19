package de.behindtheblocks.cinematic.showcase;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import de.behindtheblocks.cinematic.BtbCinematic;
import de.behindtheblocks.cinematic.camera.CameraManager;
import de.behindtheblocks.cinematic.camera.Easing;
import de.behindtheblocks.cinematic.camera.OrbitShot;
import de.behindtheblocks.cinematic.command.CommandContributor;
import de.behindtheblocks.cinematic.command.Feedback;
import de.behindtheblocks.cinematic.core.CinematicEngine;
import de.behindtheblocks.cinematic.core.CinematicModule;
import de.behindtheblocks.cinematic.core.bus.SceneMessages;
import de.behindtheblocks.cinematic.effects.EffectManager;
import de.behindtheblocks.cinematic.effects.EffectSpec;
import de.behindtheblocks.cinematic.event.builtin.BuiltinEvents;
import de.behindtheblocks.cinematic.event.builtin.PlayerTargets;
import de.behindtheblocks.cinematic.hud.HudManager;
import de.behindtheblocks.cinematic.json.JsonParamException;
import de.behindtheblocks.cinematic.json.JsonParams;
import de.behindtheblocks.cinematic.scene.SceneLoader;
import de.behindtheblocks.cinematic.sound.BtbSounds;
import de.behindtheblocks.cinematic.sound.SoundManager;
import de.behindtheblocks.cinematic.util.Colors;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * BTB Showcase System: presents items and custom (GeckoLib) models for trailer shots.
 * Definitions are JSON in {@code config/btb_cinematic/showcases/}; commands edit and save them.
 */
public final class ShowcaseManager implements CinematicModule, CommandContributor {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/showcase");

	private final Map<String, JsonObject> definitions = new TreeMap<>();
	private final Map<String, ShowcaseInstance> running = new LinkedHashMap<>();
	private final Set<UUID> ownedEntities = new HashSet<>();
	private final Set<String> pausedScenes = new HashSet<>();
	private CinematicEngine engine;
	private MinecraftServer server;

	@Override
	public String id() {
		return "showcase";
	}

	public static Path directory() {
		return FabricLoader.getInstance().getConfigDir().resolve(BtbCinematic.MOD_ID).resolve("showcases");
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
		// Leftovers from a crash (entities saved with the world) are removed when they load.
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof Display && entity.entityTags().contains(ShowcaseInstance.ENTITY_TAG)
					&& !ownedEntities.contains(entity.getUUID())) {
				entity.discard();
			}
		});
		engine.events().register(BuiltinEvents.simple("showcase_play",
				"Starts a showcase. {id, animation? (float|rotate|reveal|pulse|ascend|explode|showcase)}",
				p -> {
					String id = p.requireString("id");
					ShowcasePreset preset = p.has("animation") ? preset(p.requireString("animation")) : null;
					return ctx -> {
						String err = play(id, preset, ctx.sceneId());
						if (err != null) {
							throw new IllegalStateException(err);
						}
					};
				}));
		engine.events().register(BuiltinEvents.simple("showcase_animation",
				"Switches the animation of a running showcase. {id, animation}",
				p -> {
					String id = p.requireString("id");
					ShowcasePreset preset = preset(p.requireString("animation"));
					return ctx -> {
						ShowcaseInstance s = running.get(id);
						if (s == null) {
							throw new IllegalStateException("showcase '" + id + "' is not running");
						}
						s.switchPreset(preset);
					};
				}));
		engine.events().register(BuiltinEvents.simple("showcase_stop",
				"Stops a showcase (omit id = all).  {id?}",
				p -> {
					String id = p.has("id") ? p.requireString("id") : null;
					return ctx -> {
						if (id == null) {
							stopAll();
						} else {
							stop(id);
						}
					};
				}));
	}

	private static ShowcasePreset preset(String id) {
		try {
			return ShowcasePreset.byId(id);
		} catch (IllegalArgumentException e) {
			throw new JsonParamException("unknown animation '" + id + "'");
		}
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		this.server = server;
		installBundled();
		reload();
	}

	@Override
	public void onServerStopping(MinecraftServer server) {
		stopAll();
		this.server = null;
	}

	private void installBundled() {
		try {
			Files.createDirectories(directory());
			var bundled = FabricLoader.getInstance().getModContainer(BtbCinematic.MOD_ID)
					.flatMap(m -> m.findPath("btb_cinematic/default_showcases"));
			if (bundled.isPresent()) {
				try (Stream<Path> files = Files.list(bundled.get())) {
					for (Path f : files.toList()) {
						Path dest = directory().resolve(f.getFileName().toString());
						if (!Files.exists(dest)) {
							Files.copy(f, dest);
						}
					}
				}
			}
		} catch (IOException e) {
			LOG.error("Cannot prepare showcase directory", e);
		}
	}

	public int reload() {
		definitions.clear();
		try (Stream<Path> files = Files.list(directory())) {
			for (Path f : files.filter(x -> x.toString().endsWith(".json")).sorted().toList()) {
				String name = f.getFileName().toString();
				String id = name.substring(0, name.length() - 5);
				try {
					JsonReader reader = new JsonReader(new StringReader(Files.readString(f, StandardCharsets.UTF_8)));
					reader.setStrictness(Strictness.LENIENT);
					JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
					ShowcaseDefinition.parse(id, json);
					definitions.put(id, json);
				} catch (RuntimeException | IOException e) {
					LOG.warn("Showcase {} invalid: {}", name, e.getMessage());
				}
			}
		} catch (IOException e) {
			LOG.error("Cannot list showcases", e);
		}
		LOG.info("Loaded {} showcase(s)", definitions.size());
		return definitions.size();
	}

	private void save(String id) {
		try {
			Files.createDirectories(directory());
			Files.writeString(directory().resolve(id + ".json"),
					new GsonBuilder().setPrettyPrinting().create().toJson(definitions.get(id)), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOG.error("Could not save showcase {}", id, e);
		}
	}

	/** Stores (and saves) a definition after validating it. */
	public void putDefinition(String id, JsonObject json) {
		ShowcaseDefinition.parse(id, json);
		definitions.put(id, json);
		save(id);
	}

	public Collection<String> ids() {
		return definitions.keySet();
	}

	public java.util.Optional<JsonObject> definition(String id) {
		return java.util.Optional.ofNullable(definitions.get(id));
	}

	public Collection<String> runningIds() {
		return running.keySet();
	}

	// ------------------------------------------------------------------ playback

	/** @return null on success, otherwise an error message */
	public String play(String id, ShowcasePreset presetOverride, String sceneId) {
		JsonObject json = definitions.get(id);
		if (json == null) {
			return "Unknown showcase '" + id + "'";
		}
		ShowcaseDefinition def;
		try {
			def = ShowcaseDefinition.parse(id, json);
		} catch (JsonParamException e) {
			return "Showcase '" + id + "' is invalid: " + e.getMessage();
		}
		ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, def.dimension()));
		if (level == null) {
			return "Dimension " + def.dimension() + " not found";
		}
		stop(id);
		ShowcasePreset preset = presetOverride != null ? presetOverride : def.animation();
		ShowcaseInstance inst = new ShowcaseInstance(def, level, sceneId, preset, new ShowcaseInstance.Effects() {
			@Override
			public void own(UUID entity) {
				ownedEntities.add(entity);
			}

			@Override
			public void beat(ShowcaseInstance s) {
				Vec3 c = new Vec3(def.x(), def.y(), def.z());
				sound().play(sceneId, 0, BtbSounds.HEART_PULSE.location(), c, 1.2f, 1f);
				ring(level, def, 16);
			}

			@Override
			public void burst(ShowcaseInstance s, boolean big) {
				level.sendParticles(net.minecraft.core.particles.ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFC080),
						def.x(), def.y(), def.z(), 1, 0, 0, 0, 0);
				level.sendParticles(ParticleTypes.LAVA, def.x(), def.y(), def.z(), big ? 14 : 6, 0.3, 0.3, 0.3, 0.1);
				level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, def.x(), def.y(), def.z(), big ? 40 : 15, 0.2, 0.2, 0.2, 0.12);
				ring(level, def, 24);
				if (s.preset == ShowcasePreset.EXPLODE) {
					sound().play(sceneId, 0, BtbSounds.IMPACT.location(), new Vec3(def.x(), def.y(), def.z()), 2f, 1f);
					flash(sceneId, 0xFFFFB060);
				} else {
					flash(sceneId, 0xFFFFE0B0);
				}
			}
		});
		inst.start();
		running.put(id, inst);

		Vec3 center = new Vec3(def.x(), def.y(), def.z());
		Identifier startSound = def.sound() != null ? def.sound()
				: preset == ShowcasePreset.REVEAL || preset == ShowcasePreset.SHOWCASE ? BtbSounds.HEART_REVEAL.location() : null;
		if (startSound != null) {
			sound().play(sceneId, 0, startSound, center, 1.5f, 1f);
		}
		if (preset == ShowcasePreset.SHOWCASE) {
			if (def.camera() != null) {
				startCamera(def, sceneId);
			}
			if (!def.nameCard().isEmpty()) {
				engine.timeline().schedule(sceneId, "showcase name card", 45, () -> showNameCard(def, sceneId));
			}
		}
		return null;
	}

	private void ring(ServerLevel level, ShowcaseDefinition def, int points) {
		var p = def.particle() != null ? def.particle() : ParticleTypes.FLAME;
		for (int i = 0; i < points; i++) {
			double a = i * Math.PI * 2 / points;
			level.sendParticles(p, def.x() + Math.cos(a) * 0.6, def.y(), def.z() + Math.sin(a) * 0.6, 0,
					Math.cos(a), 0.05, Math.sin(a), 0.18);
		}
	}

	private void flash(String sceneId, int color) {
		engine.module(EffectManager.class).ifPresent(fx ->
				fx.send(sceneId, 0, new EffectSpec(EffectSpec.Kind.FLASH, color, color, 0.55f, 1, 0, 10, ""), null));
	}

	private SoundManager sound() {
		return engine.module(SoundManager.class).orElseThrow();
	}

	private void startCamera(ShowcaseDefinition def, String sceneId) {
		JsonParams c = new JsonParams(def.camera());
		int ticks = (int) Math.round(c.getDouble("seconds", 12) * 20);
		OrbitShot orbit = new OrbitShot(def.x(), def.y(), def.z(), c.getDouble("radius", 4), c.getDouble("radius_end", c.getDouble("radius", 4) * 0.8),
				c.getDouble("height", 1), c.getDouble("height_end", 0.6), c.getDouble("start_angle", 0), c.getDouble("degrees", 300),
				Math.max(1, ticks), Easing.EASE_IN_OUT, c.getFloat("fov", 0), 0);
		PlayerTargets targets = PlayerTargets.parse(c);
		List<ServerPlayer> players = targets.resolve(server);
		engine.module(CameraManager.class).ifPresent(cam ->
				cam.playOrbit(sceneId, 0, orbit, null, players, CameraManager.ShotOptions.DEFAULT));
	}

	private void showNameCard(ShowcaseDefinition def, String sceneId) {
		engine.module(HudManager.class).ifPresent(hud -> hud.showById(sceneId, def.nameCard()));
	}

	public boolean stop(String id) {
		ShowcaseInstance s = running.remove(id);
		if (s == null) {
			return false;
		}
		s.entityIds().forEach(ownedEntities::remove);
		s.remove();
		return true;
	}

	public int stopAll() {
		List<String> ids = List.copyOf(running.keySet());
		ids.forEach(this::stop);
		return ids.size();
	}

	private void stopScene(String sceneId) {
		running.entrySet().stream().filter(e -> e.getValue().sceneId.equals(sceneId)).map(Map.Entry::getKey).toList()
				.forEach(this::stop);
	}

	@Override
	public void tick(MinecraftServer server) {
		if (running.isEmpty()) {
			return;
		}
		for (Map.Entry<String, ShowcaseInstance> e : List.copyOf(running.entrySet())) {
			ShowcaseInstance s = e.getValue();
			if (pausedScenes.contains(s.sceneId)) {
				continue;
			}
			boolean alive = s.tick();
			if (!alive) {
				stop(e.getKey());
			}
		}
	}

	// ------------------------------------------------------------------ commands

	@Override
	public void contributeCommands(LiteralArgumentBuilder<CommandSourceStack> root, CommandBuildContext buildContext) {
		SuggestionProvider<CommandSourceStack> ids = (c, b) -> SharedSuggestionProvider.suggest(definitions.keySet(), b);
		SuggestionProvider<CommandSourceStack> presets = (c, b) -> SharedSuggestionProvider.suggest(
				Stream.of(ShowcasePreset.values()).map(ShowcasePreset::id), b);
		SuggestionProvider<CommandSourceStack> items = (c, b) -> SharedSuggestionProvider.suggest(
				BuiltInRegistries.ITEM.keySet().stream().map(Identifier::toString), b);

		LiteralArgumentBuilder<CommandSourceStack> sc = literal("showcase");
		sc.then(literal("list").executes(c -> {
			Feedback.ok(c.getSource(), definitions.size() + " showcase(s): " + String.join(", ", definitions.keySet())
					+ (running.isEmpty() ? "" : " — running: " + String.join(", ", running.keySet())));
			return definitions.size();
		}));
		sc.then(literal("reload").executes(c -> Feedback.ok(c.getSource(), "Loaded " + reload() + " showcase(s)")));
		sc.then(literal("create").then(argument("id", StringArgumentType.word())
				.executes(c -> create(c, null))
				.then(argument("pos", Vec3Argument.vec3()).executes(c -> create(c, Vec3Argument.getVec3(c, "pos"))))));
		sc.then(literal("item").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("item", StringArgumentType.greedyString()).suggests(items)
						.executes(c -> set(c, o -> o.addProperty("item", StringArgumentType.getString(c, "item").trim()))))));
		sc.then(literal("animation").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("animation", StringArgumentType.word()).suggests(presets).executes(c -> {
					String a = StringArgumentType.getString(c, "animation");
					int r = set(c, o -> o.addProperty("animation", a));
					ShowcaseInstance s = running.get(StringArgumentType.getString(c, "id"));
					if (s != null && r > 0) {
						s.switchPreset(ShowcasePreset.byId(a));
					}
					return r;
				}))));
		sc.then(literal("scale").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("scale", FloatArgumentType.floatArg(0.05f, 32))
						.executes(c -> set(c, o -> o.addProperty("scale", FloatArgumentType.getFloat(c, "scale")))))));
		sc.then(literal("pos").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("pos", Vec3Argument.vec3()).executes(c -> {
					Vec3 v = Vec3Argument.getVec3(c, "pos");
					return set(c, o -> o.add("pos", vec(v)));
				}))));
		sc.then(literal("duration").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("seconds", DoubleArgumentType.doubleArg(-1, 3600))
						.executes(c -> set(c, o -> {
							double s = DoubleArgumentType.getDouble(c, "seconds");
							o.addProperty("duration", s < 0 ? -1 : (int) Math.round(s * 20));
						})))));
		sc.then(literal("particles").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("particle", StringArgumentType.greedyString())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(Stream.concat(Stream.of("none", "dust"),
								BuiltInRegistries.PARTICLE_TYPE.keySet().stream().map(Identifier::toString)), b))
						.executes(c -> set(c, o -> o.addProperty("particles", StringArgumentType.getString(c, "particle").trim()))))));
		sc.then(literal("background").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("style", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(ShowcaseDefinition.BACKGROUNDS, b))
						.executes(c -> set(c, o -> o.addProperty("background", StringArgumentType.getString(c, "style")))))));
		sc.then(literal("glow").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("color", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(Stream.concat(Stream.of("none"), Colors.names().stream()), b))
						.executes(c -> set(c, o -> o.addProperty("glow", StringArgumentType.getString(c, "color")))))));
		sc.then(literal("light").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(literal("on").executes(c -> set(c, o -> o.addProperty("light", true))))
				.then(literal("off").executes(c -> set(c, o -> o.addProperty("light", false))))));
		sc.then(literal("namecard").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("hud", StringArgumentType.word())
						.executes(c -> set(c, o -> o.addProperty("name_card", StringArgumentType.getString(c, "hud")))))));
		sc.then(literal("camera").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(literal("off").executes(c -> set(c, o -> o.remove("camera"))))
				.then(argument("radius", DoubleArgumentType.doubleArg(0.5, 64))
						.then(argument("height", DoubleArgumentType.doubleArg(-32, 32))
								.then(argument("degrees", DoubleArgumentType.doubleArg(-1440, 1440))
										.then(argument("seconds", DoubleArgumentType.doubleArg(0.5, 600)).executes(c -> set(c, o -> {
											JsonObject cam = new JsonObject();
											cam.addProperty("radius", DoubleArgumentType.getDouble(c, "radius"));
											cam.addProperty("height", DoubleArgumentType.getDouble(c, "height"));
											cam.addProperty("degrees", DoubleArgumentType.getDouble(c, "degrees"));
											cam.addProperty("seconds", DoubleArgumentType.getDouble(c, "seconds"));
											o.add("camera", cam);
										}))))))));
		sc.then(literal("satellite").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(literal("clear").executes(c -> set(c, o -> o.remove("satellites"))))
				.then(literal("add").then(argument("count", IntegerArgumentType.integer(1, 16))
						.then(argument("radius", FloatArgumentType.floatArg(0.1f, 32))
								.then(argument("item", StringArgumentType.greedyString()).suggests(items).executes(c -> set(c, o -> {
									JsonArray arr = o.has("satellites") ? o.getAsJsonArray("satellites") : new JsonArray();
									JsonObject s = new JsonObject();
									s.addProperty("item", StringArgumentType.getString(c, "item").trim());
									s.addProperty("count", IntegerArgumentType.getInteger(c, "count"));
									s.addProperty("radius", FloatArgumentType.getFloat(c, "radius"));
									s.addProperty("scale", 0.5);
									s.addProperty("speed", 1.5);
									arr.add(s);
									o.add("satellites", arr);
								}))))))));
		sc.then(literal("play").then(argument("id", StringArgumentType.word()).suggests(ids)
				.executes(c -> playCmd(c, null))
				.then(argument("animation", StringArgumentType.word()).suggests(presets)
						.executes(c -> playCmd(c, StringArgumentType.getString(c, "animation"))))));
		sc.then(literal("stop")
				.executes(c -> Feedback.ok(c.getSource(), "Stopped " + stopAll() + " showcase(s)"))
				.then(argument("id", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(running.keySet(), b))
						.executes(c -> stop(StringArgumentType.getString(c, "id"))
								? Feedback.ok(c.getSource(), "Showcase stopped") : Feedback.fail(c.getSource(), "Not running"))));
		sc.then(literal("info").then(argument("id", StringArgumentType.word()).suggests(ids).executes(c -> {
			JsonObject o = definitions.get(StringArgumentType.getString(c, "id"));
			if (o == null) {
				return Feedback.fail(c.getSource(), "Unknown showcase");
			}
			Feedback.ok(c.getSource(), StringArgumentType.getString(c, "id") + ": " + o);
			return 1;
		})));
		sc.then(literal("delete").then(argument("id", StringArgumentType.word()).suggests(ids).executes(c -> {
			String id = StringArgumentType.getString(c, "id");
			stop(id);
			if (definitions.remove(id) == null) {
				return Feedback.fail(c.getSource(), "Unknown showcase");
			}
			try {
				Files.deleteIfExists(directory().resolve(id + ".json"));
			} catch (IOException e) {
				LOG.error("Could not delete showcase file", e);
			}
			return Feedback.ok(c.getSource(), "Deleted showcase '" + id + "'");
		})));
		root.then(sc);
	}

	private static JsonArray vec(Vec3 v) {
		JsonArray a = new JsonArray();
		a.add(Math.round(v.x * 100) / 100.0);
		a.add(Math.round(v.y * 100) / 100.0);
		a.add(Math.round(v.z * 100) / 100.0);
		return a;
	}

	private int create(CommandContext<CommandSourceStack> c, Vec3 pos) {
		String id = StringArgumentType.getString(c, "id");
		if (!SceneLoader.ID_PATTERN.matcher(id).matches()) {
			return Feedback.fail(c.getSource(), "Id must match [a-z0-9_]");
		}
		if (definitions.containsKey(id)) {
			return Feedback.fail(c.getSource(), "Showcase '" + id + "' already exists");
		}
		Vec3 p = pos;
		if (p == null) {
			// Default: 2.5 blocks in front of the executor at eye height.
			Vec3 look = Vec3.directionFromRotation(c.getSource().getRotation());
			p = c.getSource().getPosition().add(0, 1.4, 0).add(look.x * 2.5, 0, look.z * 2.5);
		}
		String dim = c.getSource().getLevel().dimension().identifier().toString();
		definitions.put(id, ShowcaseDefinition.template("btb_cinematic:heart_of_the_nether", Math.round(p.x * 100) / 100.0,
				Math.round(p.y * 100) / 100.0, Math.round(p.z * 100) / 100.0, dim));
		save(id);
		return Feedback.ok(c.getSource(), String.format(Locale.ROOT, "Showcase '%s' created at %.1f %.1f %.1f", id, p.x, p.y, p.z));
	}

	private int set(CommandContext<CommandSourceStack> c, Consumer<JsonObject> edit) {
		String id = StringArgumentType.getString(c, "id");
		JsonObject current = definitions.get(id);
		if (current == null) {
			return Feedback.fail(c.getSource(), "Unknown showcase '" + id + "' (create it first)");
		}
		JsonObject copy = current.deepCopy();
		edit.accept(copy);
		try {
			ShowcaseDefinition.parse(id, copy);
		} catch (JsonParamException e) {
			return Feedback.fail(c.getSource(), e.getMessage());
		}
		definitions.put(id, copy);
		save(id);
		return Feedback.ok(c.getSource(), "Showcase '" + id + "' updated");
	}

	private int playCmd(CommandContext<CommandSourceStack> c, String animation) {
		ShowcasePreset preset = null;
		if (animation != null) {
			try {
				preset = ShowcasePreset.byId(animation);
			} catch (IllegalArgumentException e) {
				return Feedback.fail(c.getSource(), "Unknown animation '" + animation + "'");
			}
		}
		String err = play(StringArgumentType.getString(c, "id"), preset, "");
		return err == null ? Feedback.ok(c.getSource(), "Showcase playing") : Feedback.fail(c.getSource(), err);
	}
}
