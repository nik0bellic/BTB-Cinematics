package de.behindtheblocks.cinematic.story;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import de.behindtheblocks.cinematic.command.CommandContributor;
import de.behindtheblocks.cinematic.command.Feedback;
import de.behindtheblocks.cinematic.core.CinematicEngine;
import de.behindtheblocks.cinematic.core.CinematicModule;
import de.behindtheblocks.cinematic.core.bus.SceneMessages;
import de.behindtheblocks.cinematic.event.builtin.BuiltinEvents;
import de.behindtheblocks.cinematic.json.JsonParamException;
import de.behindtheblocks.cinematic.json.JsonParams;
import de.behindtheblocks.cinematic.sound.BtbSounds;
import de.behindtheblocks.cinematic.sound.SoundManager;
import de.behindtheblocks.cinematic.timeline.TimelineManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Enemy waves and scripted attacks for action scenes.
 *
 * <ul>
 *   <li>{@code spawn_wave}: spawns mobs over time around a point with an appearance effect (soul fire,
 *       smoke, portal). Spawned mobs are tagged and removed again on scene reset — every take
 *       starts clean.</li>
 *   <li>{@code fireball}: a ghast fireball from A to B (or at a player) that never damages the world.</li>
 * </ul>
 * Positions are reproducible: the spawn offsets come from a seed.
 */
public final class SpawnManager implements CinematicModule, CommandContributor {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/spawn");
	public static final String TAG = "btb_spawned";
	/** Mobs with this tag drop the Nether key when they die (boss → key → gate/portal). */
	public static final String KEY_TAG = "btb_key";

	private final Map<UUID, String> spawned = new HashMap<>();
	private CinematicEngine engine;
	private MinecraftServer server;

	@Override
	public String id() {
		return "spawn";
	}

	@Override
	public void initialize(CinematicEngine engine) {
		this.engine = engine;
		engine.bus().subscribe(SceneMessages.Reset.class, m -> clear(m.sceneId()));
		engine.events().register(BuiltinEvents.simple("spawn_wave",
				"Spawns a wave of mobs. {entity, count, pos [x,y,z], radius?, interval? ticks, effect? soul|smoke|portal|none, seed?, glowing?, still? (stand still), key? (drops the Nether key), boss?}",
				p -> {
					EntityType<?> type = type(p.requireString("entity"));
					int count = p.getInt("count", 3);
					if (count < 1 || count > 100) {
						throw new JsonParamException("'count' must be 1..100");
					}
					double[] pos = p.requireDoubleArray("pos", 3);
					double radius = p.getDouble("radius", 3);
					int interval = Math.max(0, p.getInt("interval", 6));
					String effect = p.getString("effect", "soul");
					long seed = (long) p.getDouble("seed", 7);
					String flags = (p.getBoolean("glowing", false) ? "glow " : "") + (p.getBoolean("still", false) ? "still " : "")
							+ (p.getBoolean("key", false) ? "key " : "") + (p.getBoolean("boss", false) ? "boss " : "");
					return ctx -> wave(ctx.sceneId(), ctx.server().overworld(), type, count, new Vec3(pos[0], pos[1], pos[2]), radius,
							interval, effect, seed, flags, dimensionOf(ctx.server(), p));
				}));
		engine.events().register(BuiltinEvents.simple("fireball",
				"Ghast fireball that never damages the world. {from [x,y,z], to [x,y,z] | target player, speed?}",
				p -> {
					double[] from = p.requireDoubleArray("from", 3);
					double[] to = p.has("to") ? p.requireDoubleArray("to", 3) : null;
					String target = p.has("target") ? p.requireString("target") : null;
					if (to == null && target == null) {
						throw new JsonParamException("needs 'to' or 'target'");
					}
					double speed = p.getDouble("speed", 0.9);
					return ctx -> {
						Vec3 dest = to != null ? new Vec3(to[0], to[1], to[2]) : null;
						if (dest == null) {
							ServerPlayer tp = ctx.server().getPlayerList().getPlayerByName(target);
							if (tp == null) {
								throw new IllegalStateException("target '" + target + "' is not online");
							}
							dest = tp.getEyePosition();
						}
						fireball(new Vec3(from[0], from[1], from[2]), dest, speed);
					};
				}));
		engine.events().register(BuiltinEvents.simple("ghast_attack",
				"Ghasts at the horizon firing harmless fireballs at the players. {count?, pos [x,y,z], shots? per ghast, interval? ticks}",
				p -> {
					int count = p.getInt("count", 2);
					double[] pos = p.requireDoubleArray("pos", 3);
					int shots = p.getInt("shots", 8);
					int interval = Math.max(10, p.getInt("interval", 60));
					return ctx -> ghastAttack(ctx.sceneId(), ctx.server().overworld(), count, new Vec3(pos[0], pos[1], pos[2]), shots, interval);
				}));
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity.entityTags().contains(KEY_TAG) && entity.level() instanceof ServerLevel lvl) {
				de.behindtheblocks.cinematic.content.entity.HeartWarden.dropKeyDramatic(lvl,
						new Vec3(entity.getX(), entity.getY() + entity.getBbHeight() * 0.6, entity.getZ()), entity.getYRot());
				lvl.sendParticles(ParticleTypes.END_ROD, entity.getX(), entity.getY() + 1, entity.getZ(), 40, 0.4, 0.8, 0.4, 0.08);
				lvl.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, entity.getX(), entity.getY() + 0.3, entity.getZ(), 30, 0.6, 0.1, 0.6, 0.05);
				engine.module(SoundManager.class).ifPresent(sm -> sm.play(TimelineManager.MANUAL, 0, BtbSounds.CRYSTAL_SHIMMER.location(),
						entity.position(), 1.4f, 1f));
			}
		});
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			// Leftovers from an earlier session are not tracked anymore → remove them.
			if (entity.entityTags().contains(TAG) && !spawned.containsKey(entity.getUUID())) {
				entity.discard();
			}
		});
	}

	private static String dimensionOf(MinecraftServer server, JsonParams p) {
		return p.getString("dimension", "");
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		this.server = server;
	}

	private static EntityType<?> type(String raw) {
		Identifier id = Identifier.tryParse(raw.contains(":") ? raw : "minecraft:" + raw);
		if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
			throw new JsonParamException("unknown entity '" + raw + "'");
		}
		return BuiltInRegistries.ENTITY_TYPE.getValue(id);
	}

	private void wave(String sceneId, ServerLevel fallback, EntityType<?> type, int count, Vec3 center, double radius, int interval,
			String effect, long seed, String flags, String dimension) {
		ServerLevel level = fallback;
		if (!dimension.isEmpty()) {
			for (ServerLevel l : server.getAllLevels()) {
				if (l.dimension().identifier().toString().equals(dimension)) {
					level = l;
				}
			}
		} else {
			// Default: the level of the first player (film sets are usually where the players are).
			if (!server.getPlayerList().getPlayers().isEmpty()) {
				level = server.getPlayerList().getPlayers().get(0).level();
			}
		}
		if (peacefulBlocks(level, type)) {
			// Waves from scenes and triggers run silently – tell the operators, otherwise "nothing spawns" is a mystery.
			for (ServerPlayer op : server.getPlayerList().getPlayers()) {
				if (op.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) {
					op.sendSystemMessage(net.minecraft.network.chat.Component.literal("[BTB] Schwierigkeit „Friedlich“: Monster verschwinden sofort. /difficulty easy")
							.withStyle(net.minecraft.ChatFormatting.RED));
				}
			}
			LOG.warn("Spawn wave of {} on Peaceful: hostile mobs vanish immediately. Use /difficulty easy (or higher).",
					BuiltInRegistries.ENTITY_TYPE.getKey(type));
		}
		Random r = new Random(seed);
		ServerLevel lvl = level;
		for (int i = 0; i < count; i++) {
			double a = r.nextDouble() * Math.PI * 2, d = Math.sqrt(r.nextDouble()) * radius;
			Vec3 p = center.add(Math.cos(a) * d, 0, Math.sin(a) * d);
			engine.timeline().schedule(sceneId, "spawn", i * interval, () -> spawnOne(sceneId, lvl, type, p, effect, flags));
		}
	}

	private static boolean peacefulBlocks(ServerLevel level, EntityType<?> type) {
		return false; // film creatures are kept on Peaceful too (MobDespawnMixin)
	}

	private void spawnOne(String sceneId, ServerLevel level, EntityType<?> type, Vec3 p, String effect, String flags) {
		if (de.behindtheblocks.cinematic.replay.ReplayManager.playing) {
			return; // during a replay the recorded creatures are played by puppets
		}
		if (de.behindtheblocks.cinematic.director.WorldFxManager.rehearsal) {
			level.sendParticles(net.minecraft.core.particles.ParticleTypes.GLOW, p.x, p.y + 1, p.z, 6, 0.3, 0.6, 0.3, 0); // only shows where it would appear
			return;
		}
		boolean glow = flags.contains("glow"), still = flags.contains("still"), boss = flags.contains("boss");
		if (boss) {
			// the boss is always our own creature: the Heart Warden (drops the key by itself)
			type = de.behindtheblocks.cinematic.content.entity.BtbEntities.HEART_WARDEN;
		}
		boolean key = !boss && flags.contains("key") && type != de.behindtheblocks.cinematic.content.entity.BtbEntities.HEART_WARDEN;
		Entity e = type.create(level, EntitySpawnReason.COMMAND);
		if (e == null) {
			return;
		}
		e.snapTo(p.x, p.y, p.z, (float) (Math.random() * 360), 0);
		e.addTag(TAG);
		if (e instanceof Mob mob) {
			mob.setPersistenceRequired();
		}
		if (glow) {
			e.setGlowingTag(true);
		}
		if (still && e instanceof Mob mob) {
			mob.setNoAi(true); // statue for staged shots: does not move or attack
		}
		if (key) {
			e.addTag(KEY_TAG);
		}
		spawned.put(e.getUUID(), sceneId);
		level.addFreshEntity(e);
		switch (effect) {
			case "smoke" -> level.sendParticles(ParticleTypes.LARGE_SMOKE, p.x, p.y + 1, p.z, 20, 0.4, 0.8, 0.4, 0.02);
			case "portal" -> level.sendParticles(ParticleTypes.REVERSE_PORTAL, p.x, p.y + 1, p.z, 40, 0.4, 0.9, 0.4, 0.1);
			case "none" -> { }
			default -> {
				level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, p.x, p.y + 0.2, p.z, 30, 0.4, 0.1, 0.4, 0.08);
				level.sendParticles(ParticleTypes.SOUL, p.x, p.y + 1, p.z, 6, 0.3, 0.6, 0.3, 0.02);
			}
		}
		engine.module(SoundManager.class).ifPresent(s -> s.play(sceneId, 0, BtbSounds.WHOOSH.location(), p, 0.7f, 0.7f));
	}

	/** Removes all mobs spawned by a scene ("" = spawned by commands). */
	public int clear(String sceneId) {
		int n = 0;
		for (ServerLevel level : server == null ? List.<ServerLevel>of() : server.getAllLevels()) {
			// look up only our own mobs by id instead of scanning every entity of the world
			for (java.util.UUID id : List.copyOf(spawned.keySet())) {
				String owner = spawned.get(id);
				Entity e = level.getEntity(id);
				if (e != null && owner != null && (sceneId == null || owner.equals(sceneId))) {
					e.discard();
					spawned.remove(id);
					n++;
				}
			}
		}
		return n;
	}

	private static List<Entity> collect(ServerLevel level) {
		List<Entity> out = new ArrayList<>();
		level.getAllEntities().forEach(e -> {
			if (e.entityTags().contains(TAG)) {
				out.add(e);
			}
		});
		return out;
	}

	private void fireball(Vec3 from, Vec3 to, double speed) {
		Vec3 dir = to.subtract(from).normalize().scale(speed);
		// ExplosionPower 0: impact flash and sound, but never a crater — sets must stay intact.
		String cmd = String.format(Locale.ROOT, "summon minecraft:fireball %.2f %.2f %.2f {ExplosionPower:0b,Motion:[%.3f,%.3f,%.3f],acceleration_power:0.0d}",
				from.x, from.y, from.z, dir.x, dir.y, dir.z);
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd);
	}

	@Override
	public void contributeCommands(LiteralArgumentBuilder<CommandSourceStack> root, CommandBuildContext buildContext) {
		root.then(literal("spawn")
				.then(literal("clear").executes(c -> Feedback.ok(c.getSource(), "Removed " + clear(null) + " spawned mob(s)")))
				// Full ids so entities of other mods work too, e.g. mutantmonsters:mutant_zombie
				.then(argument("entity", net.minecraft.commands.arguments.IdentifierArgument.id())
						.suggests((c, b) -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.ENTITY_TYPE.keySet(), b))
						.then(argument("count", IntegerArgumentType.integer(1, 100))
								.executes(c -> spawnCmd(c.getSource(), net.minecraft.commands.arguments.IdentifierArgument.getId(c, "entity").toString(),
										IntegerArgumentType.getInteger(c, "count"), c.getSource().getPosition(), 4))
								.then(argument("pos", Vec3Argument.vec3())
										.executes(c -> spawnCmd(c.getSource(), net.minecraft.commands.arguments.IdentifierArgument.getId(c, "entity").toString(),
												IntegerArgumentType.getInteger(c, "count"), Vec3Argument.getVec3(c, "pos"), 4))
										.then(argument("radius", DoubleArgumentType.doubleArg(0, 64))
												.executes(c -> spawnCmd(c.getSource(), net.minecraft.commands.arguments.IdentifierArgument.getId(c, "entity").toString(),
														IntegerArgumentType.getInteger(c, "count"), Vec3Argument.getVec3(c, "pos"),
														DoubleArgumentType.getDouble(c, "radius"), ""))
												// still = statues, key = drops the Nether key, boss = big guardian with key, glow
												.then(argument("flags", StringArgumentType.greedyString())
														.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("still", "key", "boss", "glow", "still glow"), b))
														.executes(c -> spawnCmd(c.getSource(), net.minecraft.commands.arguments.IdentifierArgument.getId(c, "entity").toString(),
																IntegerArgumentType.getInteger(c, "count"), Vec3Argument.getVec3(c, "pos"),
																DoubleArgumentType.getDouble(c, "radius"), StringArgumentType.getString(c, "flags")))))))));
		// Ghasts hovering at the horizon that keep firing (harmless) fireballs at the players.
		root.then(literal("ghastattack").then(argument("count", IntegerArgumentType.integer(1, 8))
				.then(argument("pos", Vec3Argument.vec3())
						.executes(c -> {
							ghastAttack(TimelineManager.MANUAL, c.getSource().getLevel(), IntegerArgumentType.getInteger(c, "count"),
									Vec3Argument.getVec3(c, "pos"), 10, 60);
							return Feedback.ok(c.getSource(), "Ghasts at the horizon – they fire for 30 s (remove: /btb spawn clear)");
						}))));
	}

	private void ghastAttack(String sceneId, ServerLevel fallback, int count, Vec3 center, int shots, int interval) {
		ServerLevel level = server.getPlayerList().getPlayers().isEmpty() ? fallback : server.getPlayerList().getPlayers().get(0).level();
		Random r = new Random(11);
		for (int i = 0; i < count; i++) {
			Vec3 p = center.add((r.nextDouble() - 0.5) * 24, r.nextDouble() * 8, (r.nextDouble() - 0.5) * 24);
			spawnOne(sceneId, level, net.minecraft.world.entity.EntityTypes.GHAST, p, "none", "still");
			for (int k = 0; k < shots; k++) {
				int delay = 30 + k * interval + r.nextInt(20);
				engine.timeline().schedule(sceneId, "ghast shot", delay, () -> {
					ServerPlayer target = null;
					double best = Double.MAX_VALUE;
					for (ServerPlayer pl : level.players()) {
						double d = pl.distanceToSqr(p);
						if (!pl.isSpectator() && d < best) {
							best = d;
							target = pl;
						}
					}
					if (target != null && best < 160 * 160) {
						// aim a little off: the shots fly past and explode around the actors (no damage to blocks)
						Vec3 aim = target.getEyePosition().add((r.nextDouble() - 0.5) * 6, -1, (r.nextDouble() - 0.5) * 6);
						engine.module(SoundManager.class).ifPresent(sm -> sm.play(sceneId, 0,
								net.minecraft.sounds.SoundEvents.GHAST_SHOOT.location(), p, 3f, 1f));
						fireball(p.add(0, 1, 0), aim, 1.1);
					}
				});
			}
		}
	}

	private int spawnCmd(CommandSourceStack src, String entity, int count, Vec3 pos, double radius) {
		return spawnCmd(src, entity, count, pos, radius, "");
	}

	private int spawnCmd(CommandSourceStack src, String entity, int count, Vec3 pos, double radius, String flags) {
		try {
			EntityType<?> t = type(entity);
			wave(TimelineManager.MANUAL, src.getLevel(), t, count, pos, radius, 5, "soul", System.nanoTime(), flags,
					src.getLevel().dimension().identifier().toString());
			if (peacefulBlocks(src.getLevel(), t)) {
				return Feedback.fail(src, "Difficulty is Peaceful: " + entity + " would vanish at once. Use /difficulty easy (or higher).");
			}
			return Feedback.ok(src, "Spawning " + count + " × " + entity + " (remove with /btb spawn clear)");
		} catch (JsonParamException e) {
			return Feedback.fail(src, e.getMessage());
		}
	}
}
