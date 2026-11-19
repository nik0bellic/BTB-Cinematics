package de.behindtheblocks.cinematic.director;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import de.behindtheblocks.cinematic.camera.CameraManager;
import de.behindtheblocks.cinematic.command.CommandContributor;
import de.behindtheblocks.cinematic.command.Feedback;
import de.behindtheblocks.cinematic.core.CinematicEngine;
import de.behindtheblocks.cinematic.core.CinematicModule;
import de.behindtheblocks.cinematic.sound.BtbSounds;
import de.behindtheblocks.cinematic.timeline.TimelineManager;
import de.behindtheblocks.cinematic.util.Colors;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Crew tools and world events for shots:
 * <ul>
 *   <li>Markierungen: coloured floor marks that show actors where to stand – visible to the crew only,
 *       never to a player tagged {@code kamera} and never in the clean feed.</li>
 *   <li>Wind: one direction for all ash, smoke, sparks and flames.</li>
 *   <li>Lava-Ausbruch: a fountain of fire and lava breaking out of the ground, raining sparks, rumbling.</li>
 *   <li>Ghast-Absturz: a burning ghast tumbles out of the sky and crashes.</li>
 * </ul>
 */
public final class WorldFxManager implements CinematicModule, CommandContributor {
	private record Marker(String dim, double x, double y, double z, int color) {
	}

	private final Map<String, Marker> markers = new LinkedHashMap<>();
	/** Rehearsal (Probe): no destruction, no spawns, no eruptions – cameras, effects and marks only. */
	public static volatile boolean rehearsal;
	private final List<Object[]> smolders = new ArrayList<>(); // {level, min, max, ticksLeft}
	private final List<Object[]> fades = new ArrayList<>(); // {level, positions, from[], to, ticks, t}
	private final List<Object[]> countdowns = new ArrayList<>(); // {players, seconds, scene, t}
	private boolean markersVisible = true;
	private boolean dirty = true;
	private float windDeg, windStrength;
	private CinematicEngine engine;
	private int clock;

	private static final class Eruption {
		ServerLevel level;
		Vec3 pos;
		int t, life;
		float strength;
	}

	private static final class Crash {
		ServerLevel level;
		Ghast ghast;
		Vec3 from, to;
		int t, life;
		float spin;
	}

	private final List<Eruption> eruptions = new ArrayList<>();
	private final List<Crash> crashes = new ArrayList<>();
	private final List<Object[]> smoke = new ArrayList<>(); // {level, pos, ticksLeft}
	private final Random rnd = new Random();

	@Override
	public String id() {
		return "worldfx";
	}

	@Override
	public void initialize(CinematicEngine engine) {
		this.engine = engine;
	}

	@Override
	public void onServerStopping(MinecraftServer server) {
		crashes.forEach(c -> c.ghast.discard());
		crashes.clear();
		eruptions.clear();
	}

	// ------------------------------------------------------------------ tick

	@Override
	public void tick(MinecraftServer server) {
		clock++;
		if (dirty || clock % 60 == 0) {
			dirty = false;
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				JsonObject o = new JsonObject();
				JsonArray a = new JsonArray();
				boolean hide = !markersVisible || p.entityTags().contains("kamera");
				if (!hide) {
					for (Map.Entry<String, Marker> e : markers.entrySet()) {
						if (!e.getValue().dim.equals(p.level().dimension().identifier().toString())) {
							continue;
						}
						JsonObject m = new JsonObject();
						m.addProperty("name", e.getKey());
						m.addProperty("x", e.getValue().x);
						m.addProperty("y", e.getValue().y);
						m.addProperty("z", e.getValue().z);
						m.addProperty("color", e.getValue().color);
						a.add(m);
					}
				}
				o.add("markers", a);
				engine.cues().queue(TimelineManager.MANUAL, 0, "markers", o, List.of(p));
			}
		}
		tickEruptions();
		tickCrashes();
		tickSmolder();
		tickFades();
		tickCountdowns(server);
		if (rehearsal && clock % 40 == 0) {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (!p.entityTags().contains("kamera")) {
					p.sendSystemMessage(net.minecraft.network.chat.Component.literal("PROBE – nichts wird zerstört, keine Gegner"), true);
				}
			}
		}
		Iterator<Object[]> it = smoke.iterator();
		while (it.hasNext()) {
			Object[] s = it.next();
			ServerLevel l = (ServerLevel) s[0];
			Vec3 p = (Vec3) s[1];
			int left = (int) s[2] - 1;
			s[2] = left;
			if (left % 2 == 0) {
				l.sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, true, true, p.x, p.y + 0.5, p.z, 2, 1.2, 0.3, 1.2, 0.02);
				l.sendParticles(ParticleTypes.FLAME, true, true, p.x, p.y + 0.3, p.z, 3, 1.5, 0.2, 1.5, 0.01);
			}
			if (left <= 0) {
				it.remove();
			}
		}
	}

	private void tickEruptions() {
		Iterator<Eruption> it = eruptions.iterator();
		while (it.hasNext()) {
			Eruption e = it.next();
			e.t++;
			float s = e.strength * (e.t < 10 ? e.t / 10f : e.t > e.life - 30 ? Math.max(0, (e.life - e.t) / 30f) : 1f);
			ServerLevel l = e.level;
			Vec3 p = e.pos;
			// the fountain: fast upward jets of fire, glowing lava drops, thick smoke
			for (int i = 0; i < Math.round(22 * s); i++) {
				double vx = (rnd.nextDouble() - 0.5) * 0.25, vz = (rnd.nextDouble() - 0.5) * 0.25, vy = 0.6 + rnd.nextDouble() * 0.7 * s;
				l.sendParticles(ParticleTypes.FLAME, true, true, p.x + vx, p.y + 0.2, p.z + vz, 0, vx, vy, vz, 1);
			}
			for (int i = 0; i < Math.round(9 * s); i++) {
				double vx = (rnd.nextDouble() - 0.5) * 0.5, vz = (rnd.nextDouble() - 0.5) * 0.5;
				l.sendParticles(new DustParticleOptions(0xFF7A18, 3.5f), true, true, p.x, p.y + 0.5 + rnd.nextDouble() * 5 * s, p.z, 0, vx, 0.3, vz, 1);
			}
			l.sendParticles(ParticleTypes.LAVA, true, true, p.x, p.y + 0.5, p.z, Math.round(10 * s), 0.8, 0.4, 0.8, 0);
			l.sendParticles(ParticleTypes.FLAME, true, true, p.x, p.y + 0.3, p.z, Math.round(12 * s), 1.2, 0.2, 1.2, 0.04);
			if (e.t % 2 == 0) {
				l.sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, true, true, p.x, p.y + 2 + 6 * s, p.z, 2, 0.8, 1.5, 0.8, 0.03);
				l.sendParticles(ParticleTypes.LARGE_SMOKE, true, true, p.x, p.y + 1, p.z, 4, 1.0, 0.5, 1.0, 0.05);
			}
			// spark rain around the vent
			for (int i = 0; i < Math.round(6 * s); i++) {
				double a = rnd.nextDouble() * Math.PI * 2, r = 2 + rnd.nextDouble() * 9 * s;
				l.sendParticles(ParticleTypes.FALLING_LAVA, true, true, p.x + Math.cos(a) * r, p.y + 7 + rnd.nextDouble() * 6 * s,
						p.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
			}
			if (e.t == 1 || e.t % 30 == 0) {
				l.playSound(null, BlockPos.containing(p), BtbSounds.RUMBLE, SoundSource.BLOCKS, 3f * s, 0.6f);
				l.playSound(null, BlockPos.containing(p), SoundEvents.LAVA_POP, SoundSource.BLOCKS, 2f, 0.6f);
				engine.module(CameraManager.class).ifPresent(c -> c.shake(TimelineManager.MANUAL, 0, 0.9f * e.strength, 30, 1.2f,
						l.players().stream().filter(pl -> pl.position().distanceTo(p) < 40).map(pl -> (ServerPlayer) pl).toList()));
			}
			if (e.t == 1) {
				l.playSound(null, BlockPos.containing(p), BtbSounds.IMPACT, SoundSource.BLOCKS, 3f, 0.5f);
				l.sendParticles(ParticleTypes.EXPLOSION_EMITTER, true, true, p.x, p.y + 0.5, p.z, 1, 0, 0, 0, 0);
			}
			if (e.t >= e.life) {
				it.remove();
			}
		}
	}

	private void tickCrashes() {
		Iterator<Crash> it = crashes.iterator();
		while (it.hasNext()) {
			Crash c = it.next();
			c.t++;
			double t = Math.min(1, c.t / (double) c.life);
			double y = c.from.y + (c.to.y - c.from.y) * t * t; // falls faster and faster
			double x = c.from.x + (c.to.x - c.from.x) * t, z = c.from.z + (c.to.z - c.from.z) * t;
			c.spin += 3 + 9 * t;
			c.ghast.snapTo(x, y, z, c.ghast.getYRot() + c.spin * 0.2f, (float) Math.min(80, 20 + t * 70));
			c.ghast.setRemainingFireTicks(100);
			c.level.sendParticles(ParticleTypes.LARGE_SMOKE, true, true, x, y + 2, z, 12, 1.4, 1.4, 1.4, 0.03);
			c.level.sendParticles(ParticleTypes.FLAME, true, true, x, y + 2, z, 18, 1.6, 1.6, 1.6, 0.04);
			c.level.sendParticles(new DustParticleOptions(0x2A2A2A, 4f), true, true, x, y + 2, z, 6, 1.0, 1.0, 1.0, 0);
			c.level.sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, true, true, x, y + 3, z, 3, 0.6, 0.6, 0.6, 0.01);
			if (c.t == c.life / 3) {
				c.level.playSound(null, c.ghast.blockPosition(), SoundEvents.GHAST_DEATH, SoundSource.HOSTILE, 5f, 0.8f);
			}
			if (c.t >= c.life) {
				Vec3 g = c.to;
				c.level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, true, true, g.x, g.y + 1, g.z, 4, 2, 0.8, 2, 0);
				c.level.sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, true, true, g.x, g.y + 1, g.z, 25, 2.5, 1, 2.5, 0.05);
				c.level.sendParticles(ParticleTypes.LAVA, true, true, g.x, g.y + 1, g.z, 30, 2, 0.5, 2, 0);
				c.level.playSound(null, BlockPos.containing(g), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 6f, 0.6f);
				c.level.playSound(null, BlockPos.containing(g), BtbSounds.IMPACT, SoundSource.HOSTILE, 4f, 0.6f);
				engine.module(CameraManager.class).ifPresent(cm -> cm.shake(TimelineManager.MANUAL, 0, 1.4f, 30, 1.2f,
						c.level.players().stream().filter(pl -> pl.position().distanceTo(g) < 70).map(pl -> (ServerPlayer) pl).toList()));
				smoke.add(new Object[] {c.level, g, 120});
				c.ghast.discard();
				it.remove();
			}
		}
	}

	// ------------------------------------------------------------------ API (also used by scenes via commands)

	public void smolder(ServerLevel level, BlockPos min, BlockPos max, int seconds) {
		smolders.add(new Object[] {level, min, max, seconds * 20});
	}

	private void tickSmolder() {
		Iterator<Object[]> it = smolders.iterator();
		while (it.hasNext()) {
			Object[] s = it.next();
			int left = (int) s[3] - 1;
			s[3] = left;
			if (left <= 0) {
				it.remove();
				continue;
			}
			if (left % 8 != 0) {
				continue;
			}
			ServerLevel l = (ServerLevel) s[0];
			BlockPos lo = (BlockPos) s[1], hi = (BlockPos) s[2];
			float fade = Math.min(1f, left / 400f);
			for (int i = 0; i < Math.max(1, Math.round(4 * fade)); i++) {
				int x = lo.getX() + rnd.nextInt(hi.getX() - lo.getX() + 1), z = lo.getZ() + rnd.nextInt(hi.getZ() - lo.getZ() + 1);
				BlockPos top = l.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(x, 0, z));
				if (top.getY() > hi.getY() + 2 || top.getY() < lo.getY() - 30) {
					continue;
				}
				l.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, true, x + 0.5, top.getY() + 0.2, z + 0.5, 0, 0, 0.03, 0, 1);
				l.sendParticles(ParticleTypes.SMALL_FLAME, x + 0.5, top.getY() + 0.1, z + 0.5, 2, 0.3, 0.05, 0.3, 0.005);
				if (rnd.nextInt(4) == 0) {
					l.sendParticles(ParticleTypes.LAVA, x + 0.5, top.getY() + 0.1, z + 0.5, 1, 0.2, 0, 0.2, 0);
				}
			}
		}
	}

	// ------------------------------------------------------------------ light changes

	/** Fades all lamps of the mod (and vanilla light blocks) in a box to a brightness 0..15. */
	private int lightFade(CommandSourceStack src, BlockPos a, BlockPos b, int to, double seconds) {
		ServerLevel l = src.getLevel();
		List<BlockPos> found = new ArrayList<>();
		BlockPos lo = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
		BlockPos hi = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
		long volume = (long) (hi.getX() - lo.getX() + 1) * (hi.getY() - lo.getY() + 1) * (hi.getZ() - lo.getZ() + 1);
		if (volume > 400_000) {
			return Feedback.fail(src, "Bereich zu groß (max. 400 000 Blöcke)");
		}
		for (BlockPos p : BlockPos.betweenClosed(lo, hi)) {
			var st = l.getBlockState(p);
			if (st.hasProperty(de.behindtheblocks.cinematic.content.AtmosphereBlock.POWER)
					|| st.is(net.minecraft.world.level.block.Blocks.LIGHT)) {
				found.add(p.immutable());
			}
		}
		int[] from = new int[found.size()];
		for (int i = 0; i < from.length; i++) {
			var st = l.getBlockState(found.get(i));
			from[i] = st.hasProperty(de.behindtheblocks.cinematic.content.AtmosphereBlock.POWER)
					? st.getValue(de.behindtheblocks.cinematic.content.AtmosphereBlock.POWER) : st.getValue(net.minecraft.world.level.block.LightBlock.LEVEL);
		}
		fades.add(new Object[] {l, found, from, to, Math.max(1, (int) Math.round(seconds * 20)), new int[1]});
		return Feedback.ok(src, found.size() + " Lampen dimmen auf " + to + " in " + seconds + " s");
	}

	@SuppressWarnings("unchecked")
	private void tickFades() {
		Iterator<Object[]> it = fades.iterator();
		while (it.hasNext()) {
			Object[] f = it.next();
			ServerLevel l = (ServerLevel) f[0];
			List<BlockPos> ps = (List<BlockPos>) f[1];
			int[] from = (int[]) f[2];
			int to = (int) f[3], ticks = (int) f[4];
			int t = ++((int[]) f[5])[0];
			if (t % 4 != 0 && t < ticks) {
				continue;
			}
			double k = Math.min(1, t / (double) ticks);
			for (int i = 0; i < ps.size(); i++) {
				int v = (int) Math.round(from[i] + (to - from[i]) * k);
				var st = l.getBlockState(ps.get(i));
				if (st.hasProperty(de.behindtheblocks.cinematic.content.AtmosphereBlock.POWER)) {
					if (st.getValue(de.behindtheblocks.cinematic.content.AtmosphereBlock.POWER) != v) {
						l.setBlock(ps.get(i), st.setValue(de.behindtheblocks.cinematic.content.AtmosphereBlock.POWER, v), 3);
					}
				} else if (st.is(net.minecraft.world.level.block.Blocks.LIGHT) && st.getValue(net.minecraft.world.level.block.LightBlock.LEVEL) != v) {
					l.setBlock(ps.get(i), st.setValue(net.minecraft.world.level.block.LightBlock.LEVEL, v), 3);
				}
			}
			if (t >= ticks) {
				it.remove();
			}
		}
	}

	// ------------------------------------------------------------------ countdown for the actors

	private int countdown(CommandSourceStack src, int seconds, String scene) {
		var actors = src.getServer().getPlayerList().getPlayers().stream().filter(p -> !p.entityTags().contains("kamera")).toList();
		countdowns.add(new Object[] {actors, seconds, scene, new int[1]});
		return Feedback.ok(src, "Countdown " + seconds + " s" + (scene == null ? "" : " → " + scene));
	}

	@SuppressWarnings("unchecked")
	private void tickCountdowns(MinecraftServer server) {
		Iterator<Object[]> it = countdowns.iterator();
		while (it.hasNext()) {
			Object[] c = it.next();
			List<ServerPlayer> actors = (List<ServerPlayer>) c[0];
			int seconds = (int) c[1];
			int t = ((int[]) c[3])[0]++;
			if (t % 20 != 0) {
				continue;
			}
			int left = seconds - t / 20;
			String text = left > 0 ? String.valueOf(left) : "ACTION";
			for (ServerPlayer p : actors) {
				if (p.isRemoved()) {
					continue;
				}
				p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(0, left > 0 ? 18 : 14, 4));
				p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(
						net.minecraft.network.chat.Component.literal(text).withStyle(s -> s.withColor(left > 0 ? 0xF0E6D8 : 0xFF5A30))));
				p.level().playSound(null, p.blockPosition(), left > 0 ? SoundEvents.NOTE_BLOCK_HAT.value() : SoundEvents.NOTE_BLOCK_BELL.value(),
						SoundSource.MASTER, 1f, left > 0 ? 1.2f : 1f);
			}
			if (left <= 0) {
				if (c[2] != null) {
					server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "btb scene restart " + c[2]);
				}
				it.remove();
			}
		}
	}

	// ------------------------------------------------------------------ weather

	private int weather(CommandSourceStack src, String type, float strength, double seconds) {
		JsonObject o = new JsonObject();
		o.addProperty("type", type);
		o.addProperty("strength", strength);
		o.addProperty("seconds", seconds);
		engine.cues().queue(TimelineManager.MANUAL, 0, "weather", o);
		var fx = engine.module(de.behindtheblocks.cinematic.effects.EffectManager.class);
		int ticks = (int) Math.round(seconds * 20);
		fx.ifPresent(m -> {
			switch (type) {
				case "glutsturm" -> {
					m.send(TimelineManager.MANUAL, 0, new de.behindtheblocks.cinematic.effects.EffectSpec(
							de.behindtheblocks.cinematic.effects.EffectSpec.Kind.TINT, 0xFF7A2A08, 0xFF7A2A08, 0.14f * strength, ticks, -1, 0, ""), null);
					m.send(TimelineManager.MANUAL, 0, new de.behindtheblocks.cinematic.effects.EffectSpec(
							de.behindtheblocks.cinematic.effects.EffectSpec.Kind.HAZE, 0xFF000000, 0xFF000000, Math.min(1, 0.7f * strength), ticks, -1, 0, ""), null);
				}
				case "asche" -> m.send(TimelineManager.MANUAL, 0, new de.behindtheblocks.cinematic.effects.EffectSpec(
						de.behindtheblocks.cinematic.effects.EffectSpec.Kind.TINT, 0xFF505058, 0xFF505058, 0.12f * strength, ticks, -1, 0, ""), null);
				default -> {
					m.send(TimelineManager.MANUAL, 0, new de.behindtheblocks.cinematic.effects.EffectSpec(
							de.behindtheblocks.cinematic.effects.EffectSpec.Kind.CLEAR, 0, 0, 1, ticks, 0, 0, "tint"), null);
					m.send(TimelineManager.MANUAL, 0, new de.behindtheblocks.cinematic.effects.EffectSpec(
							de.behindtheblocks.cinematic.effects.EffectSpec.Kind.CLEAR, 0, 0, 1, ticks, 0, 0, "haze"), null);
				}
			}
		});
		if (type.equals("glutsturm")) {
			wind(src, src.getRotation().y, Math.min(1, 0.8f * strength));
		} else if (type.equals("klar")) {
			wind(src, 0, 0);
		}
		return Feedback.ok(src, "Wetter: " + type);
	}

	/** Blood moon: the sky and the whole picture turn deep red and dark (colour grade + tint + vignette). */
	private int bloodMoon(CommandSourceStack src, boolean on, double seconds) {
		int t = (int) Math.round(seconds * 20);
		engine.module(de.behindtheblocks.cinematic.effects.EffectManager.class).ifPresent(m -> {
			if (on) {
				m.send(TimelineManager.MANUAL, 0, new de.behindtheblocks.cinematic.effects.EffectSpec(
						de.behindtheblocks.cinematic.effects.EffectSpec.Kind.LUT, 0xFF000000, 0xFF000000, 0.75f, t, -1, 0, "glut"), null);
				m.send(TimelineManager.MANUAL, 0, new de.behindtheblocks.cinematic.effects.EffectSpec(
						de.behindtheblocks.cinematic.effects.EffectSpec.Kind.TINT, 0xFF5A0606, 0xFF5A0606, 0.22f, t, -1, 0, ""), null);
				m.send(TimelineManager.MANUAL, 0, new de.behindtheblocks.cinematic.effects.EffectSpec(
						de.behindtheblocks.cinematic.effects.EffectSpec.Kind.VIGNETTE, 0xFF200000, 0xFF200000, 0.7f, t, -1, 0, ""), null);
			} else {
				for (String slot : List.of("lut", "tint", "vignette")) {
					m.send(TimelineManager.MANUAL, 0, new de.behindtheblocks.cinematic.effects.EffectSpec(
							de.behindtheblocks.cinematic.effects.EffectSpec.Kind.CLEAR, 0, 0, 1, t, 0, 0, slot), null);
				}
			}
		});
		JsonObject o = new JsonObject();
		o.addProperty("on", on);
		o.addProperty("seconds", seconds);
		engine.cues().queue(TimelineManager.MANUAL, 0, "bloodmoon", o);
		return Feedback.ok(src, on ? "Blutmond" : "Blutmond aus");
	}

	public void erupt(ServerLevel level, Vec3 pos, double seconds, float strength) {
		if (rehearsal) {
			return;
		}
		de.behindtheblocks.cinematic.replay.ReplayManager.log("command", String.format(java.util.Locale.ROOT, "btb ausbruch %.2f %.2f %.2f %.1f %.2f",
				pos.x, pos.y, pos.z, seconds, strength));
		Eruption e = new Eruption();
		e.level = level;
		e.pos = pos;
		e.life = (int) Math.round(seconds * 20);
		e.strength = strength;
		eruptions.add(e);
	}

	public void ghastCrash(ServerLevel level, Vec3 from, float yaw) {
		if (rehearsal) {
			return;
		}
		de.behindtheblocks.cinematic.replay.ReplayManager.log("command", String.format(java.util.Locale.ROOT, "execute rotated %.1f 0 run btb ghastcrash %.2f %.2f %.2f",
				yaw, from.x, from.y, from.z));
		Ghast g = de.behindtheblocks.cinematic.content.entity.BtbEntities.CRASH_GHAST.create(level, EntitySpawnReason.COMMAND);
		if (g == null) {
			return;
		}
		g.setNoAi(true);
		g.addTag("btb_invulnerable");
		g.setSilent(false);
		g.addTag("btb_crash");
		g.snapTo(from.x, from.y, from.z, yaw, 20);
		level.addFreshEntity(g);
		level.playSound(null, BlockPos.containing(from), SoundEvents.GHAST_HURT, SoundSource.HOSTILE, 5f, 0.7f);
		level.sendParticles(ParticleTypes.EXPLOSION, true, true, from.x, from.y + 2, from.z, 4, 1, 1, 1, 0);
		Vec3 dir = Vec3.directionFromRotation(0, yaw);
		Vec3 land = from.add(dir.scale(28));
		BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, BlockPos.containing(land));
		Crash c = new Crash();
		c.level = level;
		c.ghast = g;
		c.from = from;
		c.to = new Vec3(land.x, Math.min(from.y - 4, top.getY()), land.z);
		c.life = (int) Math.max(40, Math.min(110, (from.y - c.to.y) * 2.2));
		crashes.add(c);
	}

	// ------------------------------------------------------------------ commands

	@Override
	public void contributeCommands(LiteralArgumentBuilder<CommandSourceStack> root, CommandBuildContext buildContext) {
		var colors = (com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack>) (c, b) -> SharedSuggestionProvider.suggest(Colors.names(), b);
		var names = (com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack>) (c, b) -> SharedSuggestionProvider.suggest(markers.keySet(), b);
		root.then(literal("mark")
				.then(literal("add").then(argument("name", StringArgumentType.word())
						.executes(c -> mark(c.getSource(), StringArgumentType.getString(c, "name"), "yellow"))
						.then(argument("color", StringArgumentType.word()).suggests(colors)
								.executes(c -> mark(c.getSource(), StringArgumentType.getString(c, "name"), StringArgumentType.getString(c, "color"))))))
				.then(literal("remove").then(argument("name", StringArgumentType.word()).suggests(names).executes(c -> {
					boolean ok = markers.remove(StringArgumentType.getString(c, "name")) != null;
					dirty = true;
					return ok ? Feedback.ok(c.getSource(), "Markierung entfernt") : Feedback.fail(c.getSource(), "Keine solche Markierung");
				})))
				.then(literal("clear").executes(c -> {
					markers.clear();
					dirty = true;
					return Feedback.ok(c.getSource(), "Alle Markierungen entfernt");
				}))
				.then(literal("list").executes(c -> Feedback.ok(c.getSource(), markers.isEmpty() ? "Keine Markierungen"
						: "Markierungen: " + String.join(", ", markers.keySet()))))
				.then(literal("show").executes(c -> {
					markersVisible = true;
					dirty = true;
					return Feedback.ok(c.getSource(), "Markierungen sichtbar (nicht für Kamera-Spieler)");
				}))
				.then(literal("hide").executes(c -> {
					markersVisible = false;
					dirty = true;
					return Feedback.ok(c.getSource(), "Markierungen ausgeblendet");
				})));
		root.then(literal("wind")
				.then(literal("off").executes(c -> wind(c.getSource(), 0, 0)))
				.then(argument("direction", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("~", "north", "east", "south", "west", "90"), b))
						.then(argument("strength", FloatArgumentType.floatArg(0, 1)).executes(c -> {
							String d = StringArgumentType.getString(c, "direction");
							float deg = switch (d) {
								case "north" -> 180;
								case "east" -> 270;
								case "south" -> 0;
								case "west" -> 90;
								case "~" -> c.getSource().getRotation().y;
								default -> {
									try {
										yield Float.parseFloat(d);
									} catch (NumberFormatException e) {
										yield 0;
									}
								}
							};
							return wind(c.getSource(), deg, FloatArgumentType.getFloat(c, "strength"));
						}))));
		root.then(literal("ausbruch").then(argument("pos", Vec3Argument.vec3())
				.executes(c -> {
					erupt(c.getSource().getLevel(), Vec3Argument.getVec3(c, "pos"), 8, 1f);
					return Feedback.ok(c.getSource(), "Lava-Ausbruch");
				})
				.then(argument("seconds", DoubleArgumentType.doubleArg(1, 120))
						.executes(c -> {
							erupt(c.getSource().getLevel(), Vec3Argument.getVec3(c, "pos"), DoubleArgumentType.getDouble(c, "seconds"), 1f);
							return Feedback.ok(c.getSource(), "Lava-Ausbruch");
						})
						.then(argument("strength", FloatArgumentType.floatArg(0.2f, 2f)).executes(c -> {
							erupt(c.getSource().getLevel(), Vec3Argument.getVec3(c, "pos"), DoubleArgumentType.getDouble(c, "seconds"),
									FloatArgumentType.getFloat(c, "strength"));
							return Feedback.ok(c.getSource(), "Lava-Ausbruch");
						})))));
		var corners = (com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack>) (c, b) -> SharedSuggestionProvider.suggest(List.of("tr", "tl", "br", "bl"), b);
		root.then(literal("pip")
				.then(literal("off").executes(c -> pip(c.getSource(), null, "tr", 0.3f)))
				.then(argument("actor", net.minecraft.commands.arguments.EntityArgument.entity())
						.executes(c -> pip(c.getSource(), net.minecraft.commands.arguments.EntityArgument.getEntity(c, "actor"), "tr", 0.3f))
						.then(argument("corner", StringArgumentType.word()).suggests(corners)
								.executes(c -> pip(c.getSource(), net.minecraft.commands.arguments.EntityArgument.getEntity(c, "actor"),
										StringArgumentType.getString(c, "corner"), 0.3f))
								.then(argument("size", FloatArgumentType.floatArg(0.15f, 0.5f))
										.executes(c -> pip(c.getSource(), net.minecraft.commands.arguments.EntityArgument.getEntity(c, "actor"),
												StringArgumentType.getString(c, "corner"), FloatArgumentType.getFloat(c, "size")))))));
		root.then(literal("licht").then(argument("from", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
				.then(argument("to", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
						.then(argument("brightness", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 15))
								.executes(c -> lightFade(c.getSource(), net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "from"),
										net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "to"),
										com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "brightness"), 2))
								.then(argument("seconds", DoubleArgumentType.doubleArg(0, 600))
										.executes(c -> lightFade(c.getSource(), net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "from"),
												net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "to"),
												com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "brightness"), DoubleArgumentType.getDouble(c, "seconds"))))))));
		root.then(literal("countdown")
				.executes(c -> countdown(c.getSource(), 3, null))
				.then(argument("seconds", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 10))
						.executes(c -> countdown(c.getSource(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "seconds"), null))
						.then(argument("scene", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(engine.scenes().sceneIds(), b))
								.executes(c -> countdown(c.getSource(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "seconds"),
										StringArgumentType.getString(c, "scene"))))));
		root.then(literal("probe")
				.then(literal("an").executes(c -> {
					rehearsal = true;
					return Feedback.ok(c.getSource(), "Probe-Modus: keine Zerstörung, keine Gegner, kein Ausbruch – Kameras und Effekte laufen");
				}))
				.then(literal("aus").executes(c -> {
					rehearsal = false;
					return Feedback.ok(c.getSource(), "Probe-Modus aus – Dreh");
				})));
		root.then(literal("wetter")
				.then(argument("type", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("asche", "glutsturm", "klar"), b))
						.executes(c -> weather(c.getSource(), StringArgumentType.getString(c, "type"), 1f, 8))
						.then(argument("strength", FloatArgumentType.floatArg(0.1f, 2f))
								.executes(c -> weather(c.getSource(), StringArgumentType.getString(c, "type"), FloatArgumentType.getFloat(c, "strength"), 8))
								.then(argument("seconds", DoubleArgumentType.doubleArg(0, 120))
										.executes(c -> weather(c.getSource(), StringArgumentType.getString(c, "type"), FloatArgumentType.getFloat(c, "strength"),
												DoubleArgumentType.getDouble(c, "seconds")))))));
		root.then(literal("blutmond")
				.then(literal("an").executes(c -> bloodMoon(c.getSource(), true, 6))
						.then(argument("seconds", DoubleArgumentType.doubleArg(0, 120)).executes(c -> bloodMoon(c.getSource(), true, DoubleArgumentType.getDouble(c, "seconds")))))
				.then(literal("aus").executes(c -> bloodMoon(c.getSource(), false, 4))
						.then(argument("seconds", DoubleArgumentType.doubleArg(0, 120)).executes(c -> bloodMoon(c.getSource(), false, DoubleArgumentType.getDouble(c, "seconds"))))));
		root.then(literal("ghastcrash").then(argument("pos", Vec3Argument.vec3()).executes(c -> {
			ghastCrash(c.getSource().getLevel(), Vec3Argument.getVec3(c, "pos"), c.getSource().getRotation().y);
			return Feedback.ok(c.getSource(), "Ghast stürzt ab (in deine Blickrichtung)");
		})));
	}

	// ------------------------------------------------------------------ picture in picture

	/**
	 * Shows the actor's own view as an inset: every player tagged {@code pip} (a second Minecraft window)
	 * spectates the actor and switches to the clean feed; the recording client tells OBS to show the source
	 * "BTB PiP" (window capture of that second window) in the corner and frames it.
	 */
	private int pip(CommandSourceStack src, net.minecraft.world.entity.Entity actor, String corner, float size) {
		var server = src.getServer();
		var viewers = server.getPlayerList().getPlayers().stream().filter(p -> p.entityTags().contains("pip")).toList();
		for (ServerPlayer v : viewers) {
			if (actor != null) {
				v.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
				if (v.level() != actor.level() || v.position().distanceTo(actor.position()) > 64) {
					v.teleportTo((ServerLevel) actor.level(), actor.getX(), actor.getY(), actor.getZ(), java.util.Set.of(), actor.getYRot(), actor.getXRot(), false);
				}
				v.setCamera(actor);
			} else {
				v.setCamera(v);
			}
			JsonObject view = new JsonObject();
			view.addProperty("on", actor != null);
			engine.cues().queue(TimelineManager.MANUAL, 0, "pip_view", view, List.of(v));
		}
		JsonObject o = new JsonObject();
		o.addProperty("on", actor != null);
		o.addProperty("corner", corner);
		o.addProperty("size", size);
		o.addProperty("label", actor == null ? "" : actor.getName().getString());
		engine.cues().queue(TimelineManager.MANUAL, 0, "pip", o,
				server.getPlayerList().getPlayers().stream().filter(p -> !p.entityTags().contains("pip")).toList());
		if (actor == null) {
			return Feedback.ok(src, "Bild-im-Bild aus");
		}
		return viewers.isEmpty()
				? Feedback.fail(src, "Kein Spieler mit Tag pip. Zweites Minecraft starten, einloggen, dann: /tag <Name> add pip")
				: Feedback.ok(src, "Bild-im-Bild: Sicht von " + actor.getName().getString() + " (" + viewers.size() + " Zuschauer-Fenster)");
	}

	private int mark(CommandSourceStack src, String name, String color) {
		int argb;
		try {
			argb = Colors.parse(color);
		} catch (RuntimeException e) {
			return Feedback.fail(src, "Unbekannte Farbe");
		}
		Vec3 p = src.getPosition();
		markers.put(name, new Marker(src.getLevel().dimension().identifier().toString(), Math.floor(p.x) + 0.5, Math.floor(p.y) + 0.05,
				Math.floor(p.z) + 0.5, argb & 0xFFFFFF));
		dirty = true;
		return Feedback.ok(src, "Markierung „" + name + "“ gesetzt – sichtbar für die Crew, nie für Spieler mit Tag kamera");
	}

	private int wind(CommandSourceStack src, float deg, float strength) {
		windDeg = deg;
		windStrength = strength;
		JsonObject o = new JsonObject();
		o.addProperty("deg", deg);
		o.addProperty("strength", strength);
		engine.cues().queue(TimelineManager.MANUAL, 0, "wind", o);
		return Feedback.ok(src, strength <= 0 ? "Wind aus" : "Wind " + (int) deg + "°, Stärke " + strength);
	}

	/** New players (or after a reset) get the current wind. */
	public void resendWind() {
		JsonObject o = new JsonObject();
		o.addProperty("deg", windDeg);
		o.addProperty("strength", windStrength);
		engine.cues().queue(TimelineManager.MANUAL, 0, "wind", o);
	}
}
