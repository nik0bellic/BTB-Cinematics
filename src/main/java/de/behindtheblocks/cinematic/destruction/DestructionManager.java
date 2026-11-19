package de.behindtheblocks.cinematic.destruction;

import com.google.gson.GsonBuilder;
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
import com.mojang.math.Transformation;
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
import de.behindtheblocks.cinematic.scene.SceneLoader;
import de.behindtheblocks.cinematic.sound.BtbSounds;
import de.behindtheblocks.cinematic.sound.SoundManager;
import de.behindtheblocks.cinematic.timeline.TimelineManager;
import de.behindtheblocks.cinematic.world.BlockSnapshot;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Nether Destruction: controlled, reproducible environment destruction with a full backup.
 *
 * <p>Before an area is touched for the first time, an exact snapshot (blocks + block entities,
 * plus a margin for lava/fire spread) is written to {@code <world>/btb_cinematic/destruction/<id>.nbt}.
 * {@code /btb destruction restore} or a scene reset puts everything back, even after a restart.
 * All randomness comes from the area's seed, so every take plays out identically. Work is spread
 * over ticks with a per-tick budget, and falling debris is rendered with interpolated block
 * displays (capped by {@code debris}) instead of physics entities.
 */
public final class DestructionManager implements CinematicModule, CommandContributor {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/destruction");
	private static final String DEBRIS_TAG = "btb_debris";

	/** Camera shake for this run, unless the area has its screen effects switched off. */
	private java.util.Optional<de.behindtheblocks.cinematic.camera.CameraManager> cam(Run run) {
		return run.def.screenFx() ? camera() : java.util.Optional.empty();
	}

	private java.util.Optional<EffectManager> screen(Run run) {
		return run.def.screenFx() ? engine.module(EffectManager.class) : java.util.Optional.empty();
	}
	private static final float GRAVITY = 0.045f;
	private static final int UPDATE_INTERVAL = 2;

	private final Map<String, JsonObject> definitions = new TreeMap<>();
	private final Map<String, BlockSnapshot> backups = new HashMap<>();
	private final Map<String, Run> runs = new HashMap<>();
	private final Map<String, String> triggeredBy = new HashMap<>();
	private final List<Debris> debris = new ArrayList<>();
	private final Set<UUID> ownedDebris = new HashSet<>();
	/** Raised gates stay up (as displays) until the area is restored. */
	private final Map<String, List<Display.BlockDisplay>> raisedGates = new HashMap<>();
	/** Last run per area (also after it finished): restore cancels its scheduled after-effects. */
	private final Map<String, Run> recentRuns = new HashMap<>();
	private final Set<String> pausedScenes = new HashSet<>();
	/** Areas whose proximity trigger already fired in this take (cleared on restore). */
	private final Set<String> proximityFired = new HashSet<>();
	private final List<DestructionDefinition> proximityAreas = new ArrayList<>();
	private CinematicEngine engine;
	private MinecraftServer server;

	private static final class Run {
		final DestructionDefinition def;
		final DestructionDefinition.Effect effect;
		final ServerLevel level;
		final String sceneId;
		final Random rnd;
		final List<BlockPos> order;
		final Vec3 center;
		Vec3 outward = Vec3.ZERO;
		int cursor;
		int t;
		int debrisSpawned;
		/** Warning phase before the destruction starts (dust trickles, creaking). */
		int wait;
		/** Collapse: how many blocks came down per column, and what they were made of (for the rubble heap). */
		final Map<Long, Integer> fallen = new HashMap<>();
		final List<BlockState> palette = new ArrayList<>();
		/** Set by restore: late work of this run (aftershocks, smouldering, settling debris) is dropped. */
		volatile boolean cancelled;
		/** Blocks that belong to the structure (lazy) and rubble blocks placed by landed debris. */
		Set<Long> structure;
		final Set<Long> rubble = new HashSet<>();

		boolean isStructure(BlockPos p) {
			if (structure == null) {
				structure = new HashSet<>();
				order.forEach(b -> structure.add(b.asLong()));
			}
			return structure.contains(p.asLong());
		}

		Run(DestructionDefinition def, DestructionDefinition.Effect effect, ServerLevel level, String sceneId, List<BlockPos> order, Vec3 center) {
			this.def = def;
			this.effect = effect;
			this.level = level;
			this.sceneId = sceneId;
			this.rnd = new Random(def.seed());
			this.order = order;
			this.center = center;
		}
	}

	private static final class Debris {
		final String areaId;
		final Display.BlockDisplay entity;
		final Vector3f velocity;
		final Vector3f spin;
		final int life;
		final BlockState state;
		int age;
		/** Collapse/crumble: lands as rubble inside these bounds (restore removes it with the area). */
		BlockPos rubbleMin, rubbleMax;
		/** > 1 for big chunks in large collapses. */
		float size = 1f;
		Run run;
		boolean landed;
		int landedAt;
		BlockPos restCell;
		boolean inFluid;

		Debris(String areaId, Display.BlockDisplay entity, Vector3f velocity, Vector3f spin, int life, BlockState state) {
			this.areaId = areaId;
			this.entity = entity;
			this.velocity = velocity;
			this.spin = spin;
			this.life = life;
			this.state = state;
		}
	}

	@Override
	public String id() {
		return "destruction";
	}

	public static Path configDirectory() {
		return FabricLoader.getInstance().getConfigDir().resolve(BtbCinematic.MOD_ID).resolve("destruction");
	}

	private Path backupFile(String id) {
		return server.getWorldPath(LevelResource.ROOT).resolve(BtbCinematic.MOD_ID).resolve("destruction").resolve(id + ".nbt");
	}

	@Override
	public void initialize(CinematicEngine engine) {
		this.engine = engine;
		engine.bus().subscribe(SceneMessages.Paused.class, m -> pausedScenes.add(m.sceneId()));
		engine.bus().subscribe(SceneMessages.Resumed.class, m -> pausedScenes.remove(m.sceneId()));
		engine.bus().subscribe(SceneMessages.Stopped.class, m -> pausedScenes.remove(m.sceneId()));
		engine.bus().subscribe(SceneMessages.Reset.class, m -> triggeredBy.entrySet().stream()
				.filter(e -> e.getValue().equals(m.sceneId())).map(Map.Entry::getKey).toList().forEach(this::restore));
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof Display && entity.entityTags().contains(DEBRIS_TAG) && !ownedDebris.contains(entity.getUUID())) {
				entity.discard();
			}
		});
		engine.events().register(BuiltinEvents.simple("destruction_trigger",
				"Destroys a predefined area. {id, effect? (collapse|explode|crumble|lava|fire|wall_burst)}",
				p -> {
					String id = p.requireString("id");
					DestructionDefinition.Effect effect = null;
					if (p.has("effect")) {
						try {
							effect = DestructionDefinition.Effect.byId(p.requireString("effect"));
						} catch (IllegalArgumentException e) {
							throw new JsonParamException("unknown effect");
						}
					}
					DestructionDefinition.Effect fx = effect;
					return ctx -> {
						String err = trigger(id, fx, ctx.sceneId());
						if (err != null) {
							throw new IllegalStateException(err);
						}
					};
				}));
		engine.events().register(BuiltinEvents.simple("destruction_restore",
				"Restores a destroyed area from its backup. {id? (omit = all)}",
				p -> {
					String id = p.has("id") ? p.requireString("id") : null;
					return ctx -> {
						if (id == null) {
							List.copyOf(definitions.keySet()).forEach(this::restore);
						} else {
							restore(id);
						}
					};
				}));
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		this.server = server;
		reload();
	}

	@Override
	public void onServerStopping(MinecraftServer server) {
		runs.clear();
		debris.forEach(d -> d.entity.discard());
		debris.clear();
		backups.clear();
		this.server = null;
	}

	public int reload() {
		definitions.clear();
		try {
			Files.createDirectories(configDirectory());
			try (Stream<Path> files = Files.list(configDirectory())) {
				for (Path f : files.filter(x -> x.toString().endsWith(".json")).sorted().toList()) {
					String name = f.getFileName().toString();
					String id = name.substring(0, name.length() - 5);
					try {
						JsonReader reader = new JsonReader(new StringReader(Files.readString(f, StandardCharsets.UTF_8)));
						reader.setStrictness(Strictness.LENIENT);
						JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
						DestructionDefinition.parse(id, json);
						definitions.put(id, json);
					} catch (RuntimeException e) {
						LOG.warn("Destruction area {} invalid: {}", name, e.getMessage());
					}
				}
			}
		} catch (IOException e) {
			LOG.error("Cannot read destruction directory", e);
		}
		rebuildProximity();
		LOG.info("Loaded {} destruction area(s)", definitions.size());
		return definitions.size();
	}

	private void rebuildProximity() {
		proximityAreas.clear();
		definitions.forEach((id, json) -> {
			try {
				DestructionDefinition d = DestructionDefinition.parse(id, json);
				if (d.proximity() > 0) {
					proximityAreas.add(d);
				}
			} catch (JsonParamException ignored) {
				// reported on reload
			}
		});
	}

	/** Fires areas with a proximity trigger when a player walks by (checked every 4 ticks). */
	private void checkProximity(MinecraftServer server) {
		if (proximityAreas.isEmpty() || server.getTickCount() % 4 != 0) {
			return;
		}
		for (DestructionDefinition d : proximityAreas) {
			if (runs.containsKey(d.id()) || (d.proximityOnce() && proximityFired.contains(d.id()))) {
				continue;
			}
			ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, d.dimension()));
			if (level == null) {
				continue;
			}
			// Distance to the area's bounding box, so long walls trigger along their whole length.
			for (ServerPlayer p : level.players()) {
				if (p.isSpectator()) {
					continue;
				}
				double dx = Math.max(Math.max(d.min().getX() - p.getX(), 0), p.getX() - (d.max().getX() + 1));
				double dy = Math.max(Math.max(d.min().getY() - p.getY(), 0), p.getY() - (d.max().getY() + 1));
				double dz = Math.max(Math.max(d.min().getZ() - p.getZ(), 0), p.getZ() - (d.max().getZ() + 1));
				if (dx * dx + dy * dy + dz * dz <= d.proximity() * d.proximity()) {
					proximityFired.add(d.id());
					String err = trigger(d.id(), null, TimelineManager.MANUAL);
					LOG.info("Proximity trigger '{}' by {}{}", d.id(), p.getName().getString(), err == null ? "" : " failed: " + err);
					break;
				}
			}
		}
	}

	private void save(String id) {
		try {
			Files.createDirectories(configDirectory());
			de.behindtheblocks.cinematic.studio.Versions.before(configDirectory().resolve(id + ".json"));
			Files.writeString(configDirectory().resolve(id + ".json"),
					new GsonBuilder().setPrettyPrinting().create().toJson(definitions.get(id)), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOG.error("Could not save destruction area {}", id, e);
		}
	}

	// ------------------------------------------------------------------ backup

	private BlockSnapshot backup(DestructionDefinition def, ServerLevel level, boolean refresh) throws IOException {
		BlockSnapshot snap = backups.get(def.id());
		Path file = backupFile(def.id());
		if (!refresh) {
			if (snap != null) {
				return snap;
			}
			if (Files.exists(file)) {
				snap = BlockSnapshot.load(level, file);
				backups.put(def.id(), snap);
				return snap;
			}
		}
		snap = BlockSnapshot.capture(level, def.backupMin(), def.backupMax());
		snap.save(file);
		backups.put(def.id(), snap);
		LOG.info("Backup of '{}' saved ({} blocks) to {}", def.id(), snap.volume(), file);
		return snap;
	}

	// ------------------------------------------------------------------ trigger

	/** @return null on success, otherwise an error message */
	public String trigger(String id, DestructionDefinition.Effect override, String sceneId) {
		JsonObject json = definitions.get(id);
		if (json == null) {
			return "Unknown destruction area '" + id + "'";
		}
		if (de.behindtheblocks.cinematic.director.WorldFxManager.rehearsal) {
			return null; // rehearsal: nothing is destroyed
		}
		if (sceneId == null || sceneId.isEmpty() || sceneId.equals(de.behindtheblocks.cinematic.timeline.TimelineManager.MANUAL)) {
			de.behindtheblocks.cinematic.replay.ReplayManager.log("destruction", id);
		}
		// chain reaction: the next areas follow after their delay
		if (json.has("next")) {
			int delay = json.has("next_delay") ? json.get("next_delay").getAsInt() : 20;
			for (String next : json.get("next").getAsString().split(",")) {
				if (!next.isBlank() && !next.equals(id)) {
					engine.timeline().schedule(sceneId, "chain " + next, delay, () -> trigger(next.trim(), null, sceneId));
				}
			}
		}
		if (runs.containsKey(id)) {
			return "Area '" + id + "' is already being destroyed";
		}
		DestructionDefinition def;
		try {
			def = DestructionDefinition.parse(id, json);
		} catch (JsonParamException e) {
			return "Area '" + id + "' invalid: " + e.getMessage();
		}
		ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, def.dimension()));
		if (level == null) {
			return "Dimension not found";
		}
		try {
			backup(def, level, false);
		} catch (IOException e) {
			LOG.error("Backup failed — refusing to destroy '{}'", id, e);
			return "Backup failed (" + e.getMessage() + "); nothing was destroyed";
		}
		DestructionDefinition.Effect effect = override != null ? override : def.effect();
		Vec3 center = def.center() != null ? new Vec3(def.center()[0], def.center()[1], def.center()[2])
				: new Vec3((def.min().getX() + def.max().getX() + 1) / 2.0,
						effect == DestructionDefinition.Effect.LAVA ? def.min().getY() : (def.min().getY() + def.max().getY() + 1) / 2.0,
						(def.min().getZ() + def.max().getZ() + 1) / 2.0);
		Run run = new Run(def, effect, level, sceneId, order(def, effect, level, center), center);
		if (effect == DestructionDefinition.Effect.WALL_BURST) {
			run.outward = outward(def, level, center);
		}
		Run previous = recentRuns.put(id, run);
		if (previous != null) {
			previous.cancelled = true;
		}
		runs.put(id, run);
		triggeredBy.put(id, sceneId);
		if (def.warn() > 0) {
			run.wait = def.warn();
		} else {
			start(run);
		}
		return null;
	}

	/** Side the wall bursts out of: configured, else towards the nearest player, else +Z. */
	private static Vec3 outward(DestructionDefinition def, ServerLevel level, Vec3 c) {
		if (def.direction() != null) {
			return new Vec3(def.direction().getStepX(), 0, def.direction().getStepZ());
		}
		ServerPlayer nearest = null;
		double best = Double.MAX_VALUE;
		for (ServerPlayer p : level.players()) {
			double d = p.position().distanceToSqr(c);
			if (d < best) {
				best = d;
				nearest = p;
			}
		}
		if (nearest == null) {
			return new Vec3(0, 0, 1);
		}
		Vec3 to = nearest.position().subtract(c);
		// Snap to the wall's thin axis if the area is a wall, otherwise to the dominant axis.
		int sx = def.max().getX() - def.min().getX() + 1, sz = def.max().getZ() - def.min().getZ() + 1;
		boolean alongX = sx <= sz; // a wall is thinnest along its outward axis
		if (sx == sz) {
			alongX = Math.abs(to.x) >= Math.abs(to.z);
		}
		return alongX ? new Vec3(Math.signum(to.x) == 0 ? 1 : Math.signum(to.x), 0, 0)
				: new Vec3(0, 0, Math.signum(to.z) == 0 ? 1 : Math.signum(to.z));
	}

	/** Deterministic processing order of the cells, depending on the effect. */
	private static List<BlockPos> order(DestructionDefinition def, DestructionDefinition.Effect effect, ServerLevel level, Vec3 c) {
		List<BlockPos> cells = new ArrayList<>();
		for (BlockPos p : BlockPos.betweenClosed(def.min(), def.max())) {
			BlockState s = level.getBlockState(p);
			boolean wanted = switch (effect) {
				case FIRE -> s.isAir() && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP);
				case LAVA -> s.isAir() && !level.getBlockState(p.below()).isAir();
				default -> !s.isAir() && s.getDestroySpeed(level, p) >= 0;
			};
			if (wanted) {
				cells.add(p.immutable());
			}
		}
		Random r = new Random(def.seed() ^ effect.ordinal());
		Map<BlockPos, Double> jitter = new HashMap<>();
		cells.forEach(p -> jitter.put(p, r.nextDouble()));
		switch (effect) {
			case COLLAPSE -> cells.sort(Comparator.<BlockPos>comparingInt(p -> -p.getY()).thenComparingDouble(jitter::get));
			case EXPLODE, LAVA, WALL_BURST -> cells.sort(Comparator.comparingDouble(p -> Vec3.atCenterOf(p).distanceToSqr(c) + jitter.get(p) * 2));
			case CRUMBLE, FIRE -> cells.sort(Comparator.comparingDouble(jitter::get));
		}
		return cells;
	}

	/** The warning before a ceiling (or wall) comes down: dust trickles from it, it creaks and trembles. */
	private void warn(Run run) {
		BlockPos lo = run.def.min(), hi = run.def.max();
		Random r = run.rnd;
		for (int i = 0; i < 4; i++) {
			int x = lo.getX() + r.nextInt(hi.getX() - lo.getX() + 1), z = lo.getZ() + r.nextInt(hi.getZ() - lo.getZ() + 1);
			BlockState st = run.level.getBlockState(new BlockPos(x, lo.getY(), z));
			run.level.sendParticles(new BlockParticleOption(ParticleTypes.FALLING_DUST, st.isAir() ? Blocks.BLACKSTONE.defaultBlockState() : st),
					true, true, x + r.nextDouble(), lo.getY() - 0.05, z + r.nextDouble(), 2, 0.2, 0, 0.2, 0);
		}
		if (run.wait % 12 == 0) {
			sound().play(run.sceneId, 0, BtbSounds.RUMBLE.location(), run.center, 1.2f, 1.4f);
			run.level.playSound(null, BlockPos.containing(run.center), net.minecraft.sounds.SoundEvents.DRIPSTONE_BLOCK_BREAK,
					net.minecraft.sounds.SoundSource.BLOCKS, 1.5f, 0.5f);
			cam(run).ifPresent(c -> c.shake(run.sceneId, 0, 0.25f, 10, 1.5f, nearby(run, 24)));
		}
	}

	private void start(Run run) {
		List<ServerPlayer> near = nearby(run, 64);
		switch (run.effect) {
			case COLLAPSE, CRUMBLE -> {
				sound().play(run.sceneId, 0, BtbSounds.RUMBLE.location(), run.center, 3f, 0.9f);
				sound().play(run.sceneId, 0, BtbSounds.COLLAPSE.location(), run.center, 3f, 1f);
				cam(run).ifPresent(c -> c.shake(run.sceneId, 0, run.effect == DestructionDefinition.Effect.COLLAPSE ? 1.1f : 0.5f,
						run.def.duration(), 0.8f, near));
			}
			case EXPLODE -> {
				sound().play(run.sceneId, 0, BtbSounds.IMPACT.location(), run.center, 4f, 0.9f);
				run.level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, run.center.x, run.center.y, run.center.z, 2, 1, 1, 1, 0);
				cam(run).ifPresent(c -> c.shake(run.sceneId, 0, 2.2f, 40, 1.4f, near));
				screen(run).ifPresent(fx -> fx.send(run.sceneId, 0,
						new EffectSpec(EffectSpec.Kind.FLASH, 0xFFFFD0A0, 0xFFFFD0A0, 0.7f, 1, 0, 14, ""), near));
			}
			case LAVA -> {
				sound().play(run.sceneId, 0, BtbSounds.RUMBLE.location(), run.center, 3f, 0.7f);
				sound().play(run.sceneId, 0, BtbSounds.IMPACT.location(), run.center, 3f, 0.7f);
				cam(run).ifPresent(c -> c.shake(run.sceneId, 0, 1.4f, run.def.duration(), 0.9f, near));
				// Small crater at the vent.
				BlockPos vent = BlockPos.containing(run.center);
				for (BlockPos p : BlockPos.betweenClosed(vent.offset(-1, -1, -1), vent.offset(1, 0, 1))) {
					if (run.level.getBlockState(p).getDestroySpeed(run.level, p) >= 0) {
						removeBlock(run, p.immutable(), true);
					}
				}
			}
			case FIRE -> sound().play(run.sceneId, 0, BtbSounds.WHOOSH.location(), run.center, 2f, 0.8f);
			case GATE -> {
				// A double gate: two wings swing open on their hinges, away from whoever opens it. Nothing
				// floats, nothing disappears – the open wings stay standing until restore closes the gate.
				BlockPos lo = run.def.min(), hi = run.def.max();
				boolean thinX = hi.getX() - lo.getX() <= hi.getZ() - lo.getZ(); // the gate stands across x (wings along z)
				int latMin = thinX ? lo.getZ() : lo.getX(), latMax = thinX ? hi.getZ() : hi.getX();
				double face = thinX ? lo.getX() : lo.getZ(); // front of the gate plane
				// open away from the nearest player (they push it open)
				ServerPlayer opener = run.level.getNearestPlayer(run.center.x, run.center.y, run.center.z, 64, false) instanceof ServerPlayer sp ? sp : null;
				int dir = opener == null ? 1 : ((thinX ? opener.getX() : opener.getZ()) > face + 0.5 ? -1 : 1);
				double hingePlane = dir > 0 ? face + 1 : face; // hinges on the side it opens to
				int width = latMax - latMin + 1, splitAt = latMin + (width + 1) / 2; // left wing: [latMin, splitAt)
				List<Display.BlockDisplay> parts = new ArrayList<>();
				List<Vec3> hinges = new ArrayList<>();
				List<Float> angles = new ArrayList<>();
				for (BlockPos p : run.order) {
					BlockState st = run.level.getBlockState(p);
					if (st.isAir()) {
						continue;
					}
					int lat = thinX ? p.getZ() : p.getX();
					boolean left = lat < splitAt;
					double hingeLat = left ? latMin : latMax + 1;
					Vec3 hinge = thinX ? new Vec3(hingePlane, p.getY(), hingeLat) : new Vec3(hingeLat, p.getY(), hingePlane);
					// wing direction (hinge → wing) must turn into the opening direction
					Vector3f wing = thinX ? new Vector3f(0, 0, left ? 1 : -1) : new Vector3f(left ? 1 : -1, 0, 0);
					Vector3f open = thinX ? new Vector3f(dir, 0, 0) : new Vector3f(0, 0, dir);
					float angle = new Quaternionf().rotationY((float) Math.toRadians(90)).transform(new Vector3f(wing)).distance(open) < 0.01f ? 90f : -90f;
					Display.BlockDisplay d = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, run.level);
					d.setPos(p.getX(), p.getY(), p.getZ());
					d.setBlockState(st);
					d.setViewRange(2f);
					d.addTag(DEBRIS_TAG);
					ownedDebris.add(d.getUUID());
					run.level.addFreshEntity(d);
					parts.add(d);
					hinges.add(hinge);
					angles.add(angle);
					run.level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16 | 32);
				}
				List<Display.BlockDisplay> old = raisedGates.put(run.def.id(), parts);
				if (old != null) {
					old.forEach(d -> {
						ownedDebris.remove(d.getUUID());
						d.discard();
					});
				}
				run.level.playSound(null, BlockPos.containing(run.center), net.minecraft.sounds.SoundEvents.IRON_DOOR_OPEN,
						net.minecraft.sounds.SoundSource.BLOCKS, 1.5f, 0.5f);
				int dur = Math.max(20, run.def.duration());
				int steps = 12;
				int every = Math.max(1, dur / steps);
				for (int step = 1; step <= steps; step++) {
					int k = step;
					// small steps on the arc (one big interpolation would cut the corner), easing out at the end
					double ease = 1 - Math.pow(1 - k / (double) steps, 2);
					engine.timeline().schedule(run.sceneId, "gate swing", 4 + (step - 1) * every, () -> {
						for (int i = 0; i < parts.size(); i++) {
							Display.BlockDisplay d = parts.get(i);
							if (d.isRemoved()) {
								continue;
							}
							Quaternionf q = new Quaternionf().rotationY((float) Math.toRadians(angles.get(i) * ease));
							// rotate about the hinge: T = (H - P) - R(H - P)
							Vector3f hp = new Vector3f((float) (hinges.get(i).x - d.getX()), 0, (float) (hinges.get(i).z - d.getZ()));
							Vector3f t = new Vector3f(hp).sub(q.transform(new Vector3f(hp)));
							d.setTransformationInterpolationDuration(every + 1);
							d.setTransformationInterpolationDelay(0);
							d.setTransformation(new Transformation(t, q, null, null));
						}
						if (k % 4 == 1) {
							run.level.playSound(null, BlockPos.containing(run.center), net.minecraft.sounds.SoundEvents.CHAIN_STEP,
									net.minecraft.sounds.SoundSource.BLOCKS, 1.2f, 0.5f);
						}
					});
				}
				engine.timeline().schedule(run.sceneId, "gate stop", 4 + steps * every, () -> {
					run.level.playSound(null, BlockPos.containing(run.center), net.minecraft.sounds.SoundEvents.ANVIL_LAND,
							net.minecraft.sounds.SoundSource.BLOCKS, 0.5f, 0.5f);
					run.level.sendParticles(ParticleTypes.LARGE_SMOKE, run.center.x, lo.getY() + 0.2, run.center.z, 10, width / 2.0, 0.1, width / 2.0, 0.01);
				});
				// restore closes the gate again (removes the displays, puts the blocks back)
			}
			case WALL_BURST -> {
				sound().play(run.sceneId, 0, BtbSounds.RUMBLE.location(), run.center, 2.5f, 1.1f);
				sound().play(run.sceneId, 0, BtbSounds.IMPACT.location(), run.center, 3f, 1.2f);
				cam(run).ifPresent(c -> c.shake(run.sceneId, 0, 1.3f, 30, 1.6f, near));
				screen(run).ifPresent(fx -> fx.send(run.sceneId, 0,
						new EffectSpec(EffectSpec.Kind.FLASH, 0xFFFF7A1A, 0xFFFF7A1A, 0.45f, 1, 0, 10, ""), nearby(run, 24)));
			}
		}
	}

	@Override
	public void tick(MinecraftServer server) {
		checkProximity(server);
		if (!runs.isEmpty()) {
			Iterator<Run> it = runs.values().iterator();
			while (it.hasNext()) {
				Run run = it.next();
				if (pausedScenes.contains(run.sceneId)) {
					continue;
				}
				if (run.wait > 0) {
					warn(run);
					if (--run.wait == 0) {
						start(run);
					}
					continue;
				}
				run.t++;
				step(run);
				if (run.t >= run.def.duration() && run.cursor >= run.order.size()) {
					it.remove();
					if (run.effect == DestructionDefinition.Effect.COLLAPSE) {
						aftershocks(run); // the rubble is what the debris left where it landed

						smolder(run);
					}
					if (run.effect == DestructionDefinition.Effect.EXPLODE || run.effect == DestructionDefinition.Effect.LAVA) {
						smolder(run);
					}
					LOG.info("Destruction '{}' ({}) done", run.def.id(), run.effect.id());
				}
			}
		}
		tickDebris();
	}

	private void step(Run run) {
		int target = (int) Math.min(run.order.size(), (long) run.order.size() * run.t / run.def.duration());
		if (run.effect == DestructionDefinition.Effect.COLLAPSE && run.cursor < run.order.size()) {
			collapseFx(run);
		}
		switch (run.effect) {
			case LAVA -> {
				fountain(run);
				// Lava pools spread outwards during the second half of the eruption.
				int lavaTarget = run.t < run.def.duration() / 2 ? 0
						: (int) Math.min(run.order.size(), (long) run.order.size() * (run.t - run.def.duration() / 2) * 2 / run.def.duration());
				lavaTarget = Math.min(lavaTarget, 40); // a handful of sources is plenty; they flow
				while (run.cursor < lavaTarget) {
					BlockPos p = run.order.get(run.cursor++);
					if (run.def.placeLava() && run.level.getBlockState(p).isAir()) {
						run.level.setBlock(p, Blocks.LAVA.defaultBlockState(), 3);
					}
				}
				if (run.t >= run.def.duration()) {
					run.cursor = run.order.size();
				}
			}
			case FIRE -> {
				while (run.cursor < target) {
					BlockPos p = run.order.get(run.cursor++);
					if (run.level.getBlockState(p).isAir()) {
						run.level.setBlock(p, BaseFireBlock.getState(run.level, p), 3);
						if (run.rnd.nextInt(4) == 0) {
							run.level.sendParticles(ParticleTypes.FLAME, p.getX() + 0.5, p.getY() + 0.3, p.getZ() + 0.5, 4, 0.3, 0.2, 0.3, 0.02);
						}
					}
				}
			}
			case WALL_BURST -> {
				// Crack open during the first third, then the lava jet pours out of the hole.
				int crackTicks = Math.max(1, run.def.duration() / 3);
				int crackTarget = (int) Math.min(run.order.size(), (long) run.order.size() * Math.min(run.t, crackTicks) / crackTicks);
				while (run.cursor < crackTarget) {
					removeBlock(run, run.order.get(run.cursor++), true);
				}
				jet(run);
				if (run.t == crackTicks && run.def.placeLava()) {
					// Real lava in the innermost hole cells; it flows out and down the wall.
					for (int i = 0; i < Math.min(3, run.order.size()); i++) {
						BlockPos p = run.order.get(i);
						if (run.level.getBlockState(p).isAir()) {
							run.level.setBlock(p, Blocks.LAVA.defaultBlockState(), 3);
						}
					}
				}
				if (run.t >= run.def.duration()) {
					run.cursor = run.order.size();
				}
			}
			case GATE -> {
				// handled in start(): the displays interpolate upwards on their own
			}
			default -> {
				int budget = 3000; // block changes per tick, keeps the server smooth on huge areas
				while (run.cursor < target && budget-- > 0) {
					removeBlock(run, run.order.get(run.cursor++), false);
				}
				if (run.t % 40 == 0 && run.cursor < run.order.size()) {
					sound().play(run.sceneId, 0, BtbSounds.COLLAPSE.location(), run.center, 2.5f, 0.9f + run.rnd.nextFloat() * 0.2f);
				}
			}
		}
	}

	private void removeBlock(Run run, BlockPos p, boolean fling) {
		BlockState state = run.level.getBlockState(p);
		if (state.isAir()) {
			return;
		}
		run.level.setBlock(p, Blocks.AIR.defaultBlockState(), BlockSnapshot.SILENT);
		Random r = run.rnd;
		if (run.effect == DestructionDefinition.Effect.COLLAPSE) {
			run.fallen.merge(BlockPos.asLong(p.getX(), 0, p.getZ()), 1, Integer::sum);
			if (run.palette.size() < 48 && !state.hasBlockEntity() && state.getFluidState().isEmpty() && r.nextInt(8) == 0) {
				run.palette.add(state.getBlock().defaultBlockState());
			}
		}
		BlockParticleOption dust = new BlockParticleOption(ParticleTypes.BLOCK, state);
		if (r.nextInt(3) == 0) {
			run.level.sendParticles(dust, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.05);
		}
		// Thin out debris on big areas so the visible amount stays roughly constant.
		int budget = run.effect == DestructionDefinition.Effect.COLLAPSE
				? Math.max(run.def.debris(), Math.min(1500, run.order.size() / 10)) : run.def.debris();
		double chance = Math.min(1.0, budget / (double) Math.max(1, run.order.size()) * 1.5);
		if (run.debrisSpawned >= budget || r.nextDouble() > chance) {
			if (run.effect == DestructionDefinition.Effect.EXPLODE && r.nextInt(12) == 0) {
				run.level.sendParticles(ParticleTypes.EXPLOSION, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 1, 0, 0, 0, 0);
			}
			return;
		}
		Vector3f v;
		int life;
		switch (run.effect) {
			case EXPLODE -> {
				Vec3 dir = Vec3.atCenterOf(p).subtract(run.center).normalize();
				float speed = 0.25f + r.nextFloat() * 0.35f;
				v = new Vector3f((float) dir.x * speed, (float) Math.abs(dir.y) * speed + 0.25f, (float) dir.z * speed);
				life = 30 + r.nextInt(25);
				if (r.nextInt(8) == 0) {
					run.level.sendParticles(ParticleTypes.EXPLOSION, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 1, 0, 0, 0, 0);
				}
			}
			case LAVA -> {
				v = new Vector3f((r.nextFloat() - 0.5f) * 0.3f, 0.55f + r.nextFloat() * 0.3f, (r.nextFloat() - 0.5f) * 0.3f);
				life = 45 + r.nextInt(20);
			}
			case WALL_BURST -> {
				float speed = 0.3f + r.nextFloat() * 0.3f;
				v = new Vector3f((float) run.outward.x * speed + (r.nextFloat() - 0.5f) * 0.15f, 0.12f + r.nextFloat() * 0.2f,
						(float) run.outward.z * speed + (r.nextFloat() - 0.5f) * 0.15f);
				life = 30 + r.nextInt(20);
			}
			default -> {
				// a collapsing building crumbles outwards: the stone drifts away from the centre while it falls,
				// so it piles up around the foot instead of vanishing inside the walls
				double ox = p.getX() + 0.5 - run.center.x, oz = p.getZ() + 0.5 - run.center.z, ol = Math.max(0.5, Math.sqrt(ox * ox + oz * oz));
				float out = run.effect == DestructionDefinition.Effect.COLLAPSE ? 0.03f + r.nextFloat() * 0.06f : 0;
				v = new Vector3f((float) (ox / ol) * out + (r.nextFloat() - 0.5f) * 0.05f, 0, (float) (oz / ol) * out + (r.nextFloat() - 0.5f) * 0.05f);
				int drop = Math.max(1, p.getY() - run.def.min().getY());
				life = (int) Math.ceil(Math.sqrt(2.0 * drop / GRAVITY)) + 1;
			}
		}
		boolean glowing = fling && (run.effect == DestructionDefinition.Effect.LAVA
				|| run.effect == DestructionDefinition.Effect.WALL_BURST && r.nextBoolean());
		// falling stone tumbles slowly about one axis; flung debris spins faster
		Vector3f spin = run.effect == DestructionDefinition.Effect.COLLAPSE
				? new Vector3f((r.nextFloat() - 0.5f) * 7, 0, (r.nextFloat() - 0.5f) * 7)
				: new Vector3f((r.nextFloat() - 0.5f) * 12, (r.nextFloat() - 0.5f) * 12, (r.nextFloat() - 0.5f) * 12);
		spawnDebris(run, p, glowing ? Blocks.MAGMA_BLOCK.defaultBlockState() : state, v, spin, life);
	}

	private void spawnDebris(Run run, BlockPos p, BlockState state, Vector3f v, Vector3f spin, int life) {
		Display.BlockDisplay d = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, run.level);
		d.setPos(p.getX(), p.getY(), p.getZ());
		d.setBlockState(state);

		d.setViewRange(1.5f);
		d.setTransformationInterpolationDuration(UPDATE_INTERVAL);
		d.addTag(DEBRIS_TAG);
		ownedDebris.add(d.getUUID());
		run.level.addFreshEntity(d);
		// life is only the longest possible flight now: debris ends when it hits the real ground
		Debris deb = new Debris(run.def.id(), d, v, spin, Math.max(life * 3, 160), state);
		deb.run = run;
		if (run.effect == DestructionDefinition.Effect.COLLAPSE && run.order.size() > 3000 && run.rnd.nextInt(4) == 0) {
			deb.size = 1.6f + run.rnd.nextFloat() * 0.8f; // big chunks among the debris
		}
		if (false) { // rubble is built as one heap at the end (see buildRubble)
			deb.rubbleMin = run.def.min();
			deb.rubbleMax = run.def.max();
		}
		debris.add(deb);
		run.debrisSpawned++;
	}

	private void fountain(Run run) {
		Vec3 c = run.center;
		Random r = run.rnd;
		run.level.sendParticles(ParticleTypes.LAVA, c.x, c.y + 0.5, c.z, 6, 0.4, 0.2, 0.4, 0.4);
		run.level.sendParticles(ParticleTypes.FALLING_LAVA, c.x, c.y + 3, c.z, 8, 1.2, 1.5, 1.2, 0.1);
		run.level.sendParticles(ParticleTypes.FLAME, c.x, c.y + 1, c.z, 6, 0.3, 1.2, 0.3, 0.06);
		if (run.t % 3 == 0) {
			run.level.sendParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y + 2, c.z, 3, 0.6, 0.8, 0.6, 0.04);
		}
		if (run.t % 2 == 0 && run.debrisSpawned < run.def.debris()) {
			Vector3f v = new Vector3f((r.nextFloat() - 0.5f) * 0.35f, 0.6f + r.nextFloat() * 0.35f, (r.nextFloat() - 0.5f) * 0.35f);
			spawnDebris(run, BlockPos.containing(c), Blocks.MAGMA_BLOCK.defaultBlockState(), v,
					new Vector3f((r.nextFloat() - 0.5f) * 16, (r.nextFloat() - 0.5f) * 16, (r.nextFloat() - 0.5f) * 16), 50 + r.nextInt(20));
		}
		if (run.t % 50 == 1) {
			sound().play(run.sceneId, 0, BtbSounds.RUMBLE.location(), c, 2.5f, 0.6f + r.nextFloat() * 0.2f);
		}
	}

	/** Horizontal lava jet out of the wall hole. */
	private void jet(Run run) {
		Vec3 c = run.center;
		Vec3 o = run.outward;
		Random r = run.rnd;
		double fade = run.t > run.def.duration() * 0.8 ? 0.4 : 1.0;
		for (int i = 0; i < (int) (8 * fade); i++) {
			double sp = 0.35 + r.nextDouble() * 0.35;
			// count = 0 makes the offsets a velocity: particles fly out of the wall.
			run.level.sendParticles(ParticleTypes.FLAME, c.x + o.x * 0.6, c.y, c.z + o.z * 0.6, 0,
					o.x * sp + (r.nextDouble() - 0.5) * 0.15, 0.05 + r.nextDouble() * 0.1, o.z * sp + (r.nextDouble() - 0.5) * 0.15, 1);
		}
		run.level.sendParticles(ParticleTypes.LAVA, c.x + o.x * 0.8, c.y, c.z + o.z * 0.8, (int) (4 * fade), 0.3, 0.3, 0.3, 0.3);
		run.level.sendParticles(ParticleTypes.FALLING_LAVA, c.x + o.x * 1.5, c.y, c.z + o.z * 1.5, (int) (6 * fade), 0.5, 0.2, 0.5, 0.05);
		if (run.t % 3 == 0) {
			run.level.sendParticles(ParticleTypes.LARGE_SMOKE, c.x + o.x, c.y + 0.5, c.z + o.z, 2, 0.4, 0.4, 0.4, 0.03);
		}
	}

	private void tickDebris() {
		if (debris.isEmpty()) {
			return;
		}
		// many pieces at once (a tower): fewer, longer interpolated updates – much less network traffic,
		// the client still moves them smoothly in between
		int updateEvery = debris.size() > 900 ? 4 : debris.size() > 400 ? 3 : UPDATE_INTERVAL;
		Iterator<Debris> it = debris.iterator();
		while (it.hasNext()) {
			Debris d = it.next();
			Run run = runs.get(d.areaId);
			if (run != null && pausedScenes.contains(run.sceneId)) {
				continue;
			}
			d.age++;
			if (d.age >= d.life || d.entity.isRemoved()) {
				if (!d.entity.isRemoved()) {
					Vec3 at = d.entity.position().add(translation(d));
					((ServerLevel) d.entity.level()).sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, d.state),
							at.x + 0.5, at.y + 0.2, at.z + 0.5, 10, 0.4, 0.1, 0.4, 0.1);
					((ServerLevel) d.entity.level()).sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, at.x + 0.5, at.y + 0.3, at.z + 0.5, 1, 0.2, 0, 0.2, 0.01);
					if (d.rubbleMin != null) {
						landRubble((ServerLevel) d.entity.level(), d, at);
					}
					d.entity.discard();
				}
				ownedDebris.remove(d.entity.getUUID());
				it.remove();
				continue;
			}
			if (d.landed) {
				if (d.age - d.landedAt >= (d.inFluid ? 12 : d.run != null && d.run.effect == DestructionDefinition.Effect.COLLAPSE ? 24 : 60)
						&& !waitsForSpace(d)) {
					settle(d);
					ownedDebris.remove(d.entity.getUUID());
					it.remove();
				}
				continue;
			}
			if (d.age > 1 && landed(d)) {
				continue;
			}
			if (d.age % updateEvery == 0) {
				Vec3 tr = translation(d);
				float s = (1f - Math.max(0, d.age - d.life + 6) / 6f * 0.4f) * d.size;
				Quaternionf q = new Quaternionf().rotationXYZ((float) Math.toRadians(d.spin.x * d.age),
						(float) Math.toRadians(d.spin.y * d.age), (float) Math.toRadians(d.spin.z * d.age));
				// Rotate around the block centre, not its corner.
				Vector3f half = new Vector3f(0.5f * s);
				Vector3f rotatedHalf = q.transform(new Vector3f(half));
				Vector3f t = new Vector3f((float) tr.x, (float) tr.y, (float) tr.z).add(0.5f, 0.5f, 0.5f).sub(rotatedHalf);
				d.entity.setTransformationInterpolationDuration(updateEvery);
				d.entity.setTransformation(new Transformation(t, q, new Vector3f(s), null));
				d.entity.setTransformationInterpolationDelay(0);
			}
		}
	}

	/** Checks the path of this tick for real ground (or lava); on contact the piece lies down flat on it. */
	private boolean landed(Debris d) {
		double vy = d.velocity.y - GRAVITY * d.age;
		if (vy >= 0) {
			return false;
		}
		ServerLevel level = (ServerLevel) d.entity.level();
		Vec3 base = d.entity.position();
		Vec3 now = translation(d);
		d.age--;
		Vec3 before = translation(d);
		d.age++;
		double cx = base.x + now.x + 0.5, cz = base.z + now.z + 0.5;
		double from = base.y + before.y, to = base.y + now.y;
		for (double y = from; y >= to - 1e-6; y -= 0.45) {
			BlockPos cell = BlockPos.containing(cx, y, cz);
			BlockState st = level.getBlockState(cell);
			boolean fluid = !st.getFluidState().isEmpty();
			boolean solid = !st.getCollisionShape(level, cell).isEmpty();
			if (!(solid || fluid) || passThrough(d, cell)) {
				continue;
			}
			d.landed = true;
			d.landedAt = d.age;
			d.inFluid = fluid && !solid;
			d.restCell = cell.above();
			float s = d.size;
			double restY = d.inFluid ? cell.getY() + 0.55 - s : cell.getY() + 1;
			// flat on the ground, snapped to the block grid (where it will become a real block)
			Vector3f t = new Vector3f((float) (d.restCell.getX() - base.x + 0.5 - 0.5 * s), (float) (restY - base.y),
					(float) (d.restCell.getZ() - base.z + 0.5 - 0.5 * s));
			d.entity.setTransformationInterpolationDuration(2);
			d.entity.setTransformation(new Transformation(t, new Quaternionf(), new Vector3f(s), null));
			d.entity.setTransformationInterpolationDelay(0);
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, d.state), cx, cell.getY() + 1.1, cz, 8, 0.4, 0.05, 0.4, 0.08);
			if (d.inFluid) {
				level.sendParticles(ParticleTypes.LAVA, cx, cell.getY() + 1, cz, 3, 0.3, 0.1, 0.3, 0);
			} else if (d.run != null && d.run.rnd.nextInt(6) == 0) {
				level.playSound(null, d.restCell, d.state.getSoundType().getBreakSound(), net.minecraft.sounds.SoundSource.BLOCKS, 0.8f, 0.6f);
			}
			return true;
		}
		return false;
	}

	/** Collapse: the structure below is coming down too – falling stone passes it instead of resting on it. */
	private static boolean passThrough(Debris d, BlockPos cell) {
		Run run = d.run;
		return run != null && run.effect == DestructionDefinition.Effect.COLLAPSE
				&& run.isStructure(cell) && !run.rubble.contains(cell.asLong());
	}

	/**
	 * A landed piece: in a collapse it becomes a real block where it lies (inside the area, on solid
	 * ground that stays) – the rubble grows where the debris actually falls. Otherwise it crumbles away.
	 */
	private void settle(Debris d) {
		ServerLevel level = (ServerLevel) d.entity.level();
		Run run = d.run;
		BlockPos c = d.restCell;
		boolean keep = !d.inFluid && run != null && !run.cancelled && run.effect == DestructionDefinition.Effect.COLLAPSE && c != null
				&& inside(run.def, c) && level.getBlockState(c).isAir()
				&& (!run.isStructure(c.below()) || run.rubble.contains(c.below().asLong()))
				&& !d.state.hasBlockEntity() && d.state.getFluidState().isEmpty();
		if (keep) {
			level.setBlock(c, d.state.getBlock().defaultBlockState(), BlockSnapshot.SILENT);
			run.rubble.add(c.asLong());
		} else if (c != null) {
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, d.state), c.getX() + 0.5, c.getY() + 0.3, c.getZ() + 0.5, 10, 0.3, 0.1, 0.3, 0.05);
		}
		d.entity.discard();
	}

	/** The spot is still taken by the lowest part of the structure, which comes down later: keep lying there until it is free. */
	private boolean waitsForSpace(Debris d) {
		Run run = d.run;
		if (run == null || run.effect != DestructionDefinition.Effect.COLLAPSE || d.restCell == null || d.age - d.landedAt > 600) {
			return false;
		}
		ServerLevel level = (ServerLevel) d.entity.level();
		return !level.getBlockState(d.restCell).isAir() && run.isStructure(d.restCell) && !run.rubble.contains(d.restCell.asLong());
	}

	private static boolean inside(DestructionDefinition def, BlockPos p) {
		BlockPos a = def.min(), b = def.max();
		return p.getX() >= a.getX() && p.getX() <= b.getX() && p.getY() >= a.getY() && p.getY() <= b.getY()
				&& p.getZ() >= a.getZ() && p.getZ() <= b.getZ();
	}

	/** Big collapse: explosions along the breaking edge, dust clouds, sparks, rumbling – scaled to the area size. */
	private void collapseFx(Run run) {
		Random r = run.rnd;
		BlockPos front = run.order.get(Math.min(run.cursor, run.order.size() - 1));
		double sx = (run.def.max().getX() - run.def.min().getX()) / 2.0 + 1, sz = (run.def.max().getZ() - run.def.min().getZ()) / 2.0 + 1;
		boolean big = run.order.size() > 3000;
		double cx = run.center.x, cz = run.center.z, fy = front.getY() + 0.5;
		if (run.t % (big ? 5 : 9) == 0) {
			double ex = cx + (r.nextDouble() - 0.5) * sx * 1.6, ez = cz + (r.nextDouble() - 0.5) * sz * 1.6;
			run.level.sendParticles(big ? ParticleTypes.EXPLOSION_EMITTER : ParticleTypes.EXPLOSION, ex, fy, ez, 1, 0, 0, 0, 0);
			sound().play(run.sceneId, 0, BtbSounds.IMPACT.location(), new Vec3(ex, fy, ez), big ? 3.5f : 2f, 0.6f + r.nextFloat() * 0.3f);
		}
		run.level.sendParticles(ParticleTypes.LARGE_SMOKE, true, true, cx, fy, cz, big ? 10 : 4, sx * 0.7, 1.5, sz * 0.7, 0.03);
		run.level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, true, cx, fy - 2, cz, big ? 4 : 1, sx * 0.6, 1, sz * 0.6, 0.02);
		run.level.sendParticles(ParticleTypes.LAVA, true, true, cx, fy, cz, big ? 4 : 1, sx * 0.5, 0.5, sz * 0.5, 0.2);
		run.level.sendParticles(ParticleTypes.ASH, true, true, cx, fy, cz, 20, sx, 4, sz, 0.02);
		// dust rolling out at the foot
		if (run.t % 4 == 0) {
			run.level.sendParticles(ParticleTypes.CLOUD, true, true, cx, run.def.min().getY() + 1, cz, big ? 14 : 5, sx * 0.9, 0.3, sz * 0.9, 0.06);
		}
		if (run.t % 30 == 1) {
			sound().play(run.sceneId, 0, BtbSounds.RUMBLE.location(), run.center, big ? 4f : 2.5f, 0.5f + r.nextFloat() * 0.2f);
			sound().play(run.sceneId, 0, BtbSounds.COLLAPSE.location(), new Vec3(cx, fy, cz), big ? 4f : 2.5f, 0.8f + r.nextFloat() * 0.3f);
		}
		if (big && run.t % 40 == 0) {
			cam(run).ifPresent(c -> c.shake(run.sceneId, 0, 1.6f, 25, 1.2f, nearby(run, 120)));
		}
	}

	/**
	 * After a collapse the material lies on the ground: a rubble heap whose height follows how much came
	 * down in each spot (smoothed, ~12 % of the fallen volume). Stays inside the area, so restore removes it.
	 */
	/**
	 * After a collapse the world does not stop at once: for ~8 s dust keeps trickling, single blocks
	 * break loose and fall, a low rumble rolls away and the ground trembles one last time.
	 */
	private void aftershocks(Run run) {
		BlockPos lo = run.def.min(), hi = run.def.max();
		int span = Math.max(1, (hi.getX() - lo.getX()) * (hi.getZ() - lo.getZ()));
		int perBurst = Math.max(3, Math.min(14, span / 40));
		for (int i = 1; i <= 12; i++) {
			int at = i * 13 + run.rnd.nextInt(8);
			boolean big = i == 4;
			engine.timeline().schedule(run.sceneId, "aftershock", at, () -> {
				if (run.cancelled) {
					return; // the area was restored meanwhile
				}
				java.util.Random r = new java.util.Random(run.rnd.nextLong());
				for (int k = 0; k < perBurst; k++) {
					int x = lo.getX() + r.nextInt(hi.getX() - lo.getX() + 1), z = lo.getZ() + r.nextInt(hi.getZ() - lo.getZ() + 1);
					BlockPos top = run.level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, new BlockPos(x, 0, z));
					double y = Math.min(top.getY() + 6 + r.nextInt(10), run.level.getMaxY() - 1);
					run.level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.FALLING_DUST,
							Blocks.BLACKSTONE.defaultBlockState()), true, true, x + 0.5, y, z + 0.5, 6, 0.6, 0.4, 0.6, 0);
					if (r.nextInt(3) == 0) {
						run.level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, true, x + 0.5, top.getY() + 0.5, z + 0.5, 1, 0.8, 0.2, 0.8, 0.01);
					}
					if (r.nextInt(4) == 0 && !run.palette.isEmpty()) {
						BlockState st = run.palette.get(r.nextInt(run.palette.size()));
						BlockPos from = new BlockPos(x, (int) y, z);
						int drop = Math.max(1, (int) y - top.getY());
						spawnDebris(run, from, st, new Vector3f((r.nextFloat() - 0.5f) * 0.04f, 0, (r.nextFloat() - 0.5f) * 0.04f),
								new Vector3f((r.nextFloat() - 0.5f) * 10, (r.nextFloat() - 0.5f) * 10, (r.nextFloat() - 0.5f) * 10),
								(int) Math.ceil(Math.sqrt(2.0 * drop / GRAVITY)) + 1);
					}
				}
				if (big) {
					sound().play(run.sceneId, 0, BtbSounds.RUMBLE.location(), run.center, 2f, 0.55f);
					cam(run).ifPresent(c -> c.shake(run.sceneId, 0, 0.45f, 20, 0.8f, nearby(run, 120)));
				} else if (r.nextInt(3) == 0) {
					sound().play(run.sceneId, 0, BtbSounds.COLLAPSE.location(), run.center, 0.7f, 0.7f + r.nextFloat() * 0.3f);
				}
			});
		}
	}

	/** Glowing remains: smoke and embers keep rising from the debris for a minute. */
	private void smolder(Run run) {
		engine.module(de.behindtheblocks.cinematic.director.WorldFxManager.class).ifPresent(w ->
				w.smolder(run.level, run.def.min(), run.def.max(), 60));
	}

	private void buildRubble(Run run) {
		if (run.palette.isEmpty() || run.fallen.isEmpty()) {
			return;
		}
		int x0 = run.def.min().getX(), z0 = run.def.min().getZ(), x1 = run.def.max().getX(), z1 = run.def.max().getZ();
		int w = x1 - x0 + 1, d = z1 - z0 + 1;
		double[][] h = new double[w][d];
		for (var e : run.fallen.entrySet()) {
			int x = BlockPos.getX(e.getKey()) - x0, z = BlockPos.getZ(e.getKey()) - z0;
			if (x >= 0 && x < w && z >= 0 && z < d) {
				h[x][z] = e.getValue() * 0.2;
			}
		}
		// two blur passes: the heap spreads out a little and gets soft edges
		for (int pass = 0; pass < 2; pass++) {
			double[][] n = new double[w][d];
			for (int x = 0; x < w; x++) {
				for (int z = 0; z < d; z++) {
					double sum = 0;
					int c = 0;
					for (int dx = -2; dx <= 2; dx++) {
						for (int dz = -2; dz <= 2; dz++) {
							int xx = x + dx, zz = z + dz;
							if (xx >= 0 && xx < w && zz >= 0 && zz < d) {
								sum += h[xx][zz];
								c++;
							}
						}
					}
					n[x][z] = sum / c;
				}
			}
			h = n;
		}
		Random r = new Random(run.def.seed() * 31 + 5);
		for (int x = 0; x < w; x++) {
			for (int z = 0; z < d; z++) {
				int height = (int) Math.round(h[x][z] + (r.nextDouble() - 0.5) * 0.8);
				if (height <= 0) {
					continue;
				}
				BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x0 + x, run.def.min().getY(), z0 + z);
				// start on the ground inside the area
				while (p.getY() < run.def.max().getY() && !run.level.getBlockState(p).isAir()) {
					p.move(0, 1, 0);
				}
				for (int i = 0; i < height && p.getY() <= run.def.max().getY(); i++, p.move(0, 1, 0)) {
					if (run.level.getBlockState(p).isAir()) {
						run.level.setBlock(p, run.palette.get(r.nextInt(run.palette.size())), BlockSnapshot.SILENT);
					}
				}
			}
		}
		run.level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, true, run.center.x, run.def.min().getY() + 3, run.center.z, 40,
				w / 4.0, 2, d / 4.0, 0.02);
		run.level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, true, true, run.center.x, run.def.min().getY() + 2, run.center.z, 3, w / 5.0, 1, d / 5.0, 0);
		run.level.sendParticles(ParticleTypes.CLOUD, true, true, run.center.x, run.def.min().getY() + 1, run.center.z, 120, w / 2.0, 1, d / 2.0, 0.15);
		sound().play(run.sceneId, 0, BtbSounds.IMPACT.location(), run.center, 5f, 0.5f);
		cam(run).ifPresent(c -> c.shake(run.sceneId, 0, 2.4f, 30, 1.5f, nearby(run, 140)));
	}

	/** The fallen block stays on the ground: rubble heaps make a collapse look real. */
	private static void landRubble(ServerLevel level, Debris d, Vec3 at) {
		int x = (int) Math.floor(at.x + 0.5), z = (int) Math.floor(at.z + 0.5);
		if (x < d.rubbleMin.getX() || x > d.rubbleMax.getX() || z < d.rubbleMin.getZ() || z > d.rubbleMax.getZ()) {
			return;
		}
		int y = Math.min(d.rubbleMax.getY(), (int) Math.floor(at.y + 0.5));
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, y, z);
		while (p.getY() > d.rubbleMin.getY() && level.getBlockState(p.below()).isAir()) {
			p.move(0, -1, 0);
		}
		if (level.getBlockState(p).isAir() && !level.getBlockState(p.below()).isAir()) {
			level.setBlock(p, d.state.getBlock().defaultBlockState(), BlockSnapshot.SILENT);
		}
	}

	private static Vec3 translation(Debris d) {
		float t = d.age;
		return new Vec3(d.velocity.x * t, d.velocity.y * t - 0.5 * GRAVITY * t * t, d.velocity.z * t);
	}

	// ------------------------------------------------------------------ restore

	/** Stops the destruction (if running), removes debris and restores the backup. */
	public int restore(String id) {
		List<Display.BlockDisplay> gate = raisedGates.remove(id);
		if (gate != null) {
			gate.forEach(d -> {
				ownedDebris.remove(d.getUUID());
				d.discard();
			});
		}
		runs.remove(id);
		Run last = recentRuns.remove(id);
		if (last != null) {
			last.cancelled = true;
		}
		proximityFired.remove(id);
		triggeredBy.remove(id);
		debris.removeIf(d -> {
			if (d.areaId.equals(id)) {
				d.entity.discard();
				ownedDebris.remove(d.entity.getUUID());
				return true;
			}
			return false;
		});
		JsonObject json = definitions.get(id);
		if (json == null || server == null) {
			return 0;
		}
		DestructionDefinition def = DestructionDefinition.parse(id, json);
		ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, def.dimension()));
		if (level == null) {
			return 0;
		}
		BlockSnapshot snap = backups.get(id);
		try {
			if (snap == null && Files.exists(backupFile(id))) {
				snap = BlockSnapshot.load(level, backupFile(id));
				backups.put(id, snap);
			}
		} catch (IOException e) {
			LOG.error("Cannot read backup of '{}'", id, e);
		}
		if (snap == null) {
			return 0;
		}
		int changed = snap.restore(level);
		LOG.info("Restored '{}' ({} blocks changed)", id, changed);
		return changed;
	}

	// ------------------------------------------------------------------ helpers

	private List<ServerPlayer> nearby(Run run, double radius) {
		return run.level.players().stream().filter(p -> p.position().distanceTo(run.center) <= radius).toList();
	}

	private SoundManager sound() {
		return engine.module(SoundManager.class).orElseThrow();
	}

	private java.util.Optional<CameraManager> camera() {
		return engine.module(CameraManager.class);
	}

	/** Stores (and saves) a definition; an old backup of the same id is discarded. */
	public void putDefinition(String id, JsonObject json) {
		DestructionDefinition.parse(id, json);
		definitions.put(id, json);
		backups.remove(id);
		proximityFired.remove(id);
		try {
			if (server != null) {
				Files.deleteIfExists(backupFile(id));
			}
		} catch (IOException e) {
			LOG.warn("Could not delete old backup of {}", id, e);
		}
		save(id);
		rebuildProximity();
	}

	public java.util.Collection<String> ids() {
		return definitions.keySet();
	}

	// ------------------------------------------------------------------ commands

	@Override
	public void contributeCommands(LiteralArgumentBuilder<CommandSourceStack> root, CommandBuildContext buildContext) {
		SuggestionProvider<CommandSourceStack> ids = (c, b) -> SharedSuggestionProvider.suggest(definitions.keySet(), b);
		SuggestionProvider<CommandSourceStack> effects = (c, b) -> SharedSuggestionProvider.suggest(DestructionDefinition.effectIds(), b);
		LiteralArgumentBuilder<CommandSourceStack> ds = literal("destruction");
		ds.then(literal("list").executes(c -> {
			Feedback.ok(c.getSource(), definitions.size() + " area(s): " + String.join(", ", definitions.keySet()));
			return definitions.size();
		}));
		ds.then(literal("reload").executes(c -> Feedback.ok(c.getSource(), "Loaded " + reload() + " area(s)")));
		ds.then(literal("create").then(argument("id", StringArgumentType.word())
				.then(argument("from", BlockPosArgument.blockPos()).then(argument("to", BlockPosArgument.blockPos()).executes(c -> {
					String id = StringArgumentType.getString(c, "id");
					if (!SceneLoader.ID_PATTERN.matcher(id).matches()) {
						return Feedback.fail(c.getSource(), "Id must match [a-z0-9_]");
					}
					JsonObject json = DestructionDefinition.template(c.getSource().getLevel().dimension().identifier().toString(),
							BlockPosArgument.getBlockPos(c, "from"), BlockPosArgument.getBlockPos(c, "to"));
					try {
						DestructionDefinition.parse(id, json);
					} catch (JsonParamException e) {
						return Feedback.fail(c.getSource(), e.getMessage());
					}
					definitions.put(id, json);
					backups.remove(id);
					// a backup of an older area with the same name (e.g. the film set built somewhere else before)
					// must not be restored here: it is taken fresh before the next trigger
					try {
						Files.deleteIfExists(backupFile(id));
					} catch (IOException e) {
						LOG.warn("Could not delete old backup of {}", id, e);
					}
					save(id);
					return Feedback.ok(c.getSource(), "Area '" + id + "' created. Backup is taken automatically before the first trigger.");
				})))));
		ds.then(literal("effect").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("effect", StringArgumentType.word()).suggests(effects)
						.executes(c -> edit(c, o -> o.addProperty("effect", StringArgumentType.getString(c, "effect")))))));
		ds.then(literal("duration").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("seconds", DoubleArgumentType.doubleArg(0.05, 300))
						.executes(c -> edit(c, o -> o.addProperty("duration", Math.max(1, (int) Math.round(DoubleArgumentType.getDouble(c, "seconds") * 20))))))));
		// Break out when someone walks past (lava from the wall) – no file editing needed.
		ds.then(literal("proximity").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(literal("off").executes(c -> edit(c, o -> o.addProperty("proximity", 0))))
				.then(argument("radius", DoubleArgumentType.doubleArg(0.5, 64))
						.executes(c -> edit(c, o -> {
							o.addProperty("proximity", DoubleArgumentType.getDouble(c, "radius"));
							o.addProperty("proximity_once", true);
						})))));
		// chain <id> <next[,next2]> <seconds> · chain <id> off: when <id> goes, the next area follows after the delay
		ds.then(literal("chain").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(literal("off").executes(c -> edit(c, o -> {
					o.remove("next");
					o.remove("next_delay");
				})))
				.then(argument("next", StringArgumentType.word()).suggests(ids)
						.then(argument("seconds", DoubleArgumentType.doubleArg(0.05, 60)).executes(c -> edit(c, o -> {
							o.addProperty("next", StringArgumentType.getString(c, "next"));
							o.addProperty("next_delay", (int) Math.round(DoubleArgumentType.getDouble(c, "seconds") * 20));
						}))))));
		ds.then(literal("warn").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(literal("off").executes(c -> edit(c, o -> o.addProperty("warn", 0))))
				.then(argument("seconds", DoubleArgumentType.doubleArg(0.1, 20))
						.executes(c -> edit(c, o -> o.addProperty("warn", (int) Math.round(DoubleArgumentType.getDouble(c, "seconds") * 20)))))));
		ds.then(literal("screenfx").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(literal("on").executes(c -> edit(c, o -> o.addProperty("screen_fx", true))))
				.then(literal("off").executes(c -> edit(c, o -> o.addProperty("screen_fx", false))))));
		ds.then(literal("direction").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("dir", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("north", "south", "east", "west", "auto"), b))
						.executes(c -> edit(c, o -> {
							String d = StringArgumentType.getString(c, "dir");
							if (d.equals("auto")) {
								o.remove("direction");
							} else {
								o.addProperty("direction", d);
							}
						})))));
		ds.then(literal("seed").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("seed", IntegerArgumentType.integer())
						.executes(c -> edit(c, o -> o.addProperty("seed", IntegerArgumentType.getInteger(c, "seed")))))));
		ds.then(literal("center").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("pos", BlockPosArgument.blockPos())
						.executes(c -> edit(c, o -> o.add("center", DestructionDefinition.arr(BlockPosArgument.getBlockPos(c, "pos"))))))));
		ds.then(literal("trigger").then(argument("id", StringArgumentType.word()).suggests(ids)
				.executes(c -> triggerCmd(c, null))
				.then(argument("effect", StringArgumentType.word()).suggests(effects)
						.executes(c -> triggerCmd(c, StringArgumentType.getString(c, "effect"))))));
		ds.then(literal("restore")
				.executes(c -> {
					int total = 0;
					for (String id : List.copyOf(definitions.keySet())) {
						total += restore(id);
					}
					return Feedback.ok(c.getSource(), "Restored all areas (" + total + " blocks)");
				})
				.then(argument("id", StringArgumentType.word()).suggests(ids)
						.executes(c -> Feedback.ok(c.getSource(), "Restored " + restore(StringArgumentType.getString(c, "id")) + " block(s)"))));
		ds.then(literal("backup").then(argument("id", StringArgumentType.word()).suggests(ids).executes(c -> {
			String id = StringArgumentType.getString(c, "id");
			JsonObject json = definitions.get(id);
			if (json == null) {
				return Feedback.fail(c.getSource(), "Unknown area");
			}
			DestructionDefinition def = DestructionDefinition.parse(id, json);
			try {
				BlockSnapshot s = backup(def, server.getLevel(ResourceKey.create(Registries.DIMENSION, def.dimension())), true);
				return Feedback.ok(c.getSource(), "Backup refreshed (" + s.volume() + " blocks)");
			} catch (IOException e) {
				return Feedback.fail(c.getSource(), "Backup failed: " + e.getMessage());
			}
		})));
		ds.then(literal("delete").then(argument("id", StringArgumentType.word()).suggests(ids).executes(c -> {
			String id = StringArgumentType.getString(c, "id");
			if (definitions.remove(id) == null) {
				return Feedback.fail(c.getSource(), "Unknown area");
			}
			try {
				Files.deleteIfExists(configDirectory().resolve(id + ".json"));
			} catch (IOException e) {
				LOG.error("Could not delete area file", e);
			}
			return Feedback.ok(c.getSource(), "Area '" + id + "' deleted (its backup file is kept in the world folder)");
		})));
		root.then(ds);
	}

	private int triggerCmd(CommandContext<CommandSourceStack> c, String effect) {
		DestructionDefinition.Effect fx = null;
		if (effect != null) {
			try {
				fx = DestructionDefinition.Effect.byId(effect);
			} catch (IllegalArgumentException e) {
				return Feedback.fail(c.getSource(), "Unknown effect");
			}
		}
		String err = trigger(StringArgumentType.getString(c, "id"), fx, TimelineManager.MANUAL);
		return err == null ? Feedback.ok(c.getSource(), "Destruction triggered") : Feedback.fail(c.getSource(), err);
	}

	private int edit(CommandContext<CommandSourceStack> c, Consumer<JsonObject> op) {
		String id = StringArgumentType.getString(c, "id");
		JsonObject current = definitions.get(id);
		if (current == null) {
			return Feedback.fail(c.getSource(), "Unknown area '" + id + "'");
		}
		JsonObject copy = current.deepCopy();
		op.accept(copy);
		try {
			DestructionDefinition.parse(id, copy);
		} catch (JsonParamException e) {
			return Feedback.fail(c.getSource(), e.getMessage());
		}
		definitions.put(id, copy);
		save(id);
		rebuildProximity();
		return Feedback.ok(c.getSource(), "Area '" + id + "' updated");
	}
}
