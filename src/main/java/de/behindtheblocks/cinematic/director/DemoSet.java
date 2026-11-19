package de.behindtheblocks.cinematic.director;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.behindtheblocks.cinematic.BtbCinematic;
import de.behindtheblocks.cinematic.ambience.AmbienceManager;
import de.behindtheblocks.cinematic.content.BtbBlocks;
import de.behindtheblocks.cinematic.content.PortalFrameBlock;
import de.behindtheblocks.cinematic.camera.CameraManager;
import de.behindtheblocks.cinematic.camera.CameraPath;
import de.behindtheblocks.cinematic.camera.CameraPoint;
import de.behindtheblocks.cinematic.camera.CameraPose;
import de.behindtheblocks.cinematic.camera.Easing;
import de.behindtheblocks.cinematic.core.CinematicEngine;
import de.behindtheblocks.cinematic.destruction.DestructionManager;
import de.behindtheblocks.cinematic.showcase.ShowcaseManager;
import de.behindtheblocks.cinematic.world.BlockSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds the demo set for the {@code heart_discovery} scene around a position and wires up the
 * matching camera preset, showcase and destruction areas. The original terrain is backed up and
 * comes back with {@code /btb demo remove}.
 *
 * <p>Layout (relative to the origin O on the ground):
 * the Heart Tower centred on O (heart on its altar inside), the approach path to the south with two
 * walls that burst open with lava when someone walks past, a ruined watchtower at O+(17,0,-15).
 */
final class DemoSet {
	static final int R = 38; // half size of the built area
	static final int H = 100;

	private DemoSet() {
	}

	static Path backupFile(ServerLevel level) {
		return level.getServer().getWorldPath(LevelResource.ROOT).resolve(BtbCinematic.MOD_ID).resolve("demo_backup.nbt");
	}

	static Path originFile(ServerLevel level) {
		return level.getServer().getWorldPath(LevelResource.ROOT).resolve(BtbCinematic.MOD_ID).resolve("demo_origin.txt");
	}

	static String build(CinematicEngine engine, ServerLevel level, BlockPos o) throws IOException {
		Path backup = backupFile(level);
		if (Files.exists(backup)) {
			return "A demo set already exists — run /btb demo remove first";
		}
		BlockPos min = o.offset(-R, -3, -R), max = o.offset(R, H, R);
		BlockSnapshot.capture(level, min, max).save(backup);
		Files.writeString(originFile(level), level.dimension().identifier() + " " + o.getX() + " " + o.getY() + " " + o.getZ());

		int f = Block.UPDATE_CLIENTS;
		// clear the air space and lay a scorched nether floor
		fill(level, o.offset(-R, 0, -R), o.offset(R, H, R), Blocks.AIR.defaultBlockState(), f);
		fill(level, o.offset(-R, -3, -R), o.offset(R, -1, R), Blocks.NETHERRACK.defaultBlockState(), f);
		for (BlockPos p : BlockPos.betweenClosed(o.offset(-R, -1, -R), o.offset(R, -1, R))) {
			int h = hash(p.getX(), p.getZ()) % 13;
			if (h == 0) {
				level.setBlock(p, Blocks.CRIMSON_NYLIUM.defaultBlockState(), f);
			} else if (h == 1) {
				level.setBlock(p, Blocks.SOUL_SOIL.defaultBlockState(), f);
			} else if (h == 2) {
				level.setBlock(p, Blocks.MAGMA_BLOCK.defaultBlockState(), f);
			}
		}
		buildTower(level, o, f);
		// approach path from the south with two walls that burst open when someone walks past
		fill(level, o.offset(-3, -1, 19), o.offset(3, -1, R), Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState(), f);
		for (int side : new int[] {-1, 1}) {
			int x = side * 6;
			fill(level, o.offset(x, 0, 22), o.offset(x, 5, 32), Blocks.NETHER_BRICKS.defaultBlockState(), f);
			fill(level, o.offset(x + side, 0, 22), o.offset(x + side, 5, 32), Blocks.BLACKSTONE.defaultBlockState(), f);
			fill(level, o.offset(x, 1, 25 + (side > 0 ? 3 : 0)), o.offset(x, 3, 27 + (side > 0 ? 3 : 0)),
					Blocks.CRACKED_NETHER_BRICKS.defaultBlockState(), f);
		}
		// ruined watchtower for collapse shots
		BlockPos rt = o.offset(27, 0, -26);
		fill(level, rt, rt.offset(4, 13, 4), Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState(), f);
		fill(level, rt.offset(1, 0, 1), rt.offset(3, 12, 3), Blocks.AIR.defaultBlockState(), f);
		fill(level, rt.offset(0, 14, 0), rt.offset(4, 14, 4), BtbBlocks.HEARTSTONE_BRICKS.defaultBlockState(), f);

		// --- matching configuration -------------------------------------------------------
		double ax = o.getX() + 0.5, ay = o.getY() + 2.5, az = o.getZ() + 0.5;
		JsonObject sc = new JsonObject();
		sc.addProperty("item", "btb_cinematic:heart_of_the_nether");
		sc.add("pos", arr(ax, ay, az));
		sc.addProperty("dimension", level.dimension().identifier().toString());
		sc.addProperty("scale", 1.8);
		sc.addProperty("animation", "showcase");
		sc.addProperty("glow", "#FF5A00");
		sc.addProperty("light", true);
		sc.addProperty("particles", "minecraft:flame");
		sc.addProperty("background", "ring");
		JsonArray sats = new JsonArray();
		JsonObject sat = new JsonObject();
		sat.addProperty("item", "btb_cinematic:nether_crystal");
		sat.addProperty("count", 4);
		sat.addProperty("radius", 1.5);
		sat.addProperty("height", 0.1);
		sat.addProperty("speed", 1.6);
		sat.addProperty("scale", 0.55);
		sats.add(sat);
		sc.add("satellites", sats);
		JsonObject cam = new JsonObject();
		cam.addProperty("radius", 4.5);
		cam.addProperty("radius_end", 3.2);
		cam.addProperty("height", 1.2);
		cam.addProperty("height_end", 0.4);
		cam.addProperty("degrees", 420);
		cam.addProperty("seconds", 22);
		cam.addProperty("players", "all");
		sc.add("camera", cam);
		sc.addProperty("name_card", "heart_title");
		engine.module(ShowcaseManager.class).orElseThrow().putDefinition("heart", sc);

		// fly-over: far and high above the wasteland → down to the bridge → through the gate → the heart
		double cx = o.getX() + 0.5, cz = o.getZ() + 0.5;
		double[][] pts = {
				{cx + 52, o.getY() + 74, cz + 78, cx, o.getY() + 52, cz},
				{cx + 28, o.getY() + 40, cz + 52, cx, o.getY() + 30, cz},
				{cx + 2, o.getY() + 10, cz + 38, cx, o.getY() + 7, cz + 12},
				{cx, o.getY() + 5, cz + 22, cx, o.getY() + 5, cz},
				{cx, o.getY() + 4, cz + 12, ax, ay, az},
				{cx - 2.6, o.getY() + 3.2, cz + 3.6, ax, ay, az}};
		int[] ticks = {70, 55, 45, 35, 30, 1};
		float[] rolls = {0, -7, 4, 0, 0, 0};
		List<CameraPoint> points = new java.util.ArrayList<>();
		for (int i = 0; i < pts.length; i++) {
			CameraPose look = new CameraPose(pts[i][0], pts[i][1], pts[i][2], 0, 0, 0, 0).lookingAt(pts[i][3], pts[i][4], pts[i][5]);
			points.add(new CameraPoint(pts[i][0], pts[i][1], pts[i][2], look.yaw(), look.pitch(), rolls[i],
					i == pts.length - 1 ? 55 : 0, ticks[i], Easing.LINEAR, null, null));
		}
		engine.module(CameraManager.class).orElseThrow()
				.putPreset(new CameraPath("heart_flyover", points, CameraPath.Mode.SMOOTH, Easing.EASE_IN_OUT, 1.0));

		DestructionManager dm = engine.module(DestructionManager.class).orElseThrow();
		String dim = level.dimension().identifier().toString();
		// No lava walls at the tower any more (they belong to the bridge part of the film set).
		JsonObject tower = new JsonObject();
		tower.addProperty("dimension", dim);
		tower.add("from", arr(o.getX() + 27, o.getY(), o.getZ() - 26));
		tower.add("to", arr(o.getX() + 31, o.getY() + 14, o.getZ() - 22));
		tower.addProperty("effect", "collapse");
		tower.addProperty("duration", 70);
		tower.addProperty("debris", 220);
		dm.putDefinition("ruin_tower", tower);

		// the tower lives: deep heartbeat with echo, rising souls, falling ash, faint tremor
		JsonObject amb = new JsonObject();
		amb.addProperty("dimension", dim);
		amb.add("pos", arr(cx, o.getY() + 6, cz));
		amb.addProperty("radius", 110);
		amb.addProperty("sound", "btb_cinematic:heart_pulse");
		amb.addProperty("sound_interval", 40); // heartbeat = 2 s, like the heart animation
		amb.addProperty("sound_volume", 3.0);
		amb.addProperty("sound_pitch", 0.6);
		amb.addProperty("flash_color", "#801008");
		amb.addProperty("flash_interval", 40);
		JsonObject echo = new JsonObject();
		echo.addProperty("count", 2);
		echo.addProperty("delay", 9);
		echo.addProperty("decay", 0.45);
		amb.add("echo", echo);
		JsonArray parts = new JsonArray();
		parts.add(emitter("minecraft:soul", 2, 7, 3, 7, 0.02, -4));
		parts.add(emitter("minecraft:ash", 16, 26, 20, 26, 0.01, 14));
		parts.add(emitter("dust:#C01008", 6, 0.25, 22, 0.25, 0, 98));
		parts.add(emitter("minecraft:lava", 2, 3, 1, 3, 0.1, 74));
		parts.add(emitter("minecraft:crimson_spore", 4, 9, 6, 9, 0.01, 2));
		amb.add("particles", parts);
		JsonArray orbit = new JsonArray();
		JsonObject rubble = new JsonObject();
		rubble.addProperty("block", "minecraft:blackstone");
		rubble.addProperty("count", 14);
		rubble.addProperty("radius", 13);
		rubble.addProperty("height", 72);
		rubble.addProperty("speed", 0.45);
		rubble.addProperty("scale", 1.6);
		orbit.add(rubble);
		JsonObject shards = new JsonObject();
		shards.addProperty("block", "btb_cinematic:heartstone_bricks");
		shards.addProperty("count", 10);
		shards.addProperty("radius", 18);
		shards.addProperty("height", 58);
		shards.addProperty("speed", -0.3);
		shards.addProperty("scale", 1.1);
		orbit.add(shards);
		JsonObject magma = new JsonObject();
		magma.addProperty("block", "minecraft:magma_block");
		magma.addProperty("count", 6);
		magma.addProperty("radius", 9);
		magma.addProperty("height", 86);
		magma.addProperty("speed", 0.7);
		magma.addProperty("scale", 0.9);
		orbit.add(magma);
		amb.add("orbit_blocks", orbit);
		amb.addProperty("shake_intensity", 0.1);
		amb.addProperty("shake_interval", 40);
		engine.module(AmbienceManager.class).orElseThrow().putDefinition("heart_tower", amb);
		return null;
	}

	static String remove(CinematicEngine engine, ServerLevel level) throws IOException {
		Path backup = backupFile(level);
		if (!Files.exists(backup)) {
			return "No demo set to remove";
		}
		engine.module(DestructionManager.class).ifPresent(dm -> {
			dm.restore("lava_wall_west");
			dm.restore("lava_wall_east");
			dm.restore("ruin_tower");
		});
		engine.module(AmbienceManager.class).ifPresent(a -> a.remove("heart_tower"));
		engine.module(ShowcaseManager.class).ifPresent(ShowcaseManager::stopAll);
		BlockSnapshot.load(level, backup).restore(level);
		Files.delete(backup);
		Files.deleteIfExists(originFile(level));
		return null;
	}

	private static JsonObject wallBurst(String dim, BlockPos a, BlockPos b, String direction) {
		JsonObject w = new JsonObject();
		w.addProperty("dimension", dim);
		w.add("from", arr(a.getX(), a.getY(), a.getZ()));
		w.add("to", arr(b.getX(), b.getY(), b.getZ()));
		w.addProperty("effect", "wall_burst");
		w.addProperty("duration", 90);
		w.addProperty("direction", direction);
		w.addProperty("proximity", 5.5);
		w.addProperty("debris", 50);
		return w;
	}

	private static JsonObject emitter(String particle, int count, double sx, double sy, double sz, double speed, double y) {
		JsonObject e = new JsonObject();
		e.addProperty("particle", particle);
		e.addProperty("count", count);
		e.add("spread", arr(sx, sy, sz));
		e.addProperty("speed", speed);
		e.addProperty("y", y);
		return e;
	}

	/**
	 * The Heart Tower: a tapering, 46-block spire of heartstone and blackstone with glowing veins,
	 * buttresses, a crown of spikes, light slits and a rune-lined gate; inside a tall chamber with
	 * the heart altar. A lava moat with a bridge surrounds it.
	 */
	static void buildTower(ServerLevel level, BlockPos o, int f) {
		int top = 80;
		for (int y = 0; y <= top; y++) {
			double rOut = 13.5 - y * 0.11;
			double rIn = rOut - 1.4;
			for (int x = -15; x <= 15; x++) {
				for (int z = -15; z <= 15; z++) {
					double d = Math.sqrt(x * x + z * z);
					BlockPos p = o.offset(x, y, z);
					if (d <= rOut && d >= rIn) {
						double ang = Math.toDegrees(Math.atan2(z, x));
						boolean vein = false;
						for (int k = 0; k < 6; k++) {
							double va = 30 + k * 60 + Math.sin(y * 0.35 + k) * 9;
							if (Math.abs(wrap(ang - va)) < 5.5) {
								vein = true;
							}
						}
						boolean gate = z > 0 && Math.abs(x) <= 3 && y <= 12;
						boolean slit = (y >= 22 && y <= 30 || y >= 48 && y <= 54) && (Math.abs(wrap(ang - 0)) < 3 || Math.abs(wrap(ang - 180)) < 3
								|| Math.abs(wrap(ang + 90)) < 3);
						if (gate || slit) {
							continue;
						}
						boolean gateFrame = z > 0 && ((Math.abs(x) == 4 && y <= 13) || (Math.abs(x) <= 4 && y == 13));
						BlockState s;
						if (gateFrame) {
							s = BtbBlocks.PORTAL_FRAME.defaultBlockState().setValue(PortalFrameBlock.LIT, true);
						} else if (vein) {
							s = BtbBlocks.NETHER_VEIN.defaultBlockState();
						} else {
							int h = hash(x * 31 + y, z * 17 - y) % 20;
							s = h < 11 ? BtbBlocks.HEARTSTONE_BRICKS.defaultBlockState()
									: h < 15 ? Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState()
									: h < 18 ? Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS.defaultBlockState()
									: Blocks.BLACKSTONE.defaultBlockState();
						}
						level.setBlock(p, s, f);
					}
				}
			}
			// buttresses in 8 directions, tapering outwards near the ground
			if (y < 34) {
				double len = 5.5 * (1 - y / 34.0);
				for (int k = 0; k < 8; k++) {
					double a = Math.toRadians(22.5 + k * 45);
					for (double r = rOut; r <= rOut + len; r += 0.5) {
						BlockPos p = o.offset((int) Math.round(Math.cos(a) * r), y, (int) Math.round(Math.sin(a) * r));
						if (!(p.getZ() - o.getZ() > 0 && Math.abs(p.getX() - o.getX()) <= 4 && y <= 13)) {
							level.setBlock(p, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState(), f);
						}
					}
				}
			}
		}
		// crown of spikes
		for (int k = 0; k < 8; k++) {
			double a = Math.toRadians(k * 45);
			int h = 7 + (k % 2) * 5;
			for (int i = 0; i <= h; i++) {
				double r = 4.6 + i * 0.35;
				BlockPos p = o.offset((int) Math.round(Math.cos(a) * r), top + 1 + i, (int) Math.round(Math.sin(a) * r));
				level.setBlock(p, i == h ? Blocks.GILDED_BLACKSTONE.defaultBlockState() : BtbBlocks.HEARTSTONE_BRICKS.defaultBlockState(), f);
			}
		}
		fill(level, o.offset(-3, top, -3), o.offset(3, top, 3), Blocks.MAGMA_BLOCK.defaultBlockState(), f);
		fill(level, o.offset(-1, top + 1, -1), o.offset(1, top + 1, 1), BtbBlocks.NETHER_VEIN.defaultBlockState(), f);
		level.setBlock(o.offset(0, top + 2, 0), Blocks.SOUL_CAMPFIRE.defaultBlockState(), f);
		// chamber: floor, altar, glowing inner pillars, soul lanterns
		for (int x = -12; x <= 12; x++) {
			for (int z = -12; z <= 12; z++) {
				double d = Math.sqrt(x * x + z * z);
				if (d < 12.1) {
					level.setBlock(o.offset(x, -1, z), (d > 2.5 && d < 3.5) || (d > 7.5 && d < 8.3) ? Blocks.MAGMA_BLOCK.defaultBlockState()
							: Blocks.POLISHED_BLACKSTONE.defaultBlockState(), f);
				}
			}
		}
		level.setBlock(o, BtbBlocks.HEART_ALTAR.defaultBlockState(), f);
		for (int k = 0; k < 6; k++) {
			double a = Math.toRadians(30 + k * 60);
			BlockPos base = o.offset((int) Math.round(Math.cos(a) * 8), 0, (int) Math.round(Math.sin(a) * 8));
			fill(level, base, base.above(30), BtbBlocks.NETHER_VEIN.defaultBlockState(), f);
		}
		// hanging chains with soul lanterns from the dark above
		for (int k = 0; k < 6; k++) {
			double a = Math.toRadians(k * 60);
			BlockPos hang = o.offset((int) Math.round(Math.cos(a) * 5), 0, (int) Math.round(Math.sin(a) * 5));
			int len = 6 + (k % 3) * 3;
			fill(level, hang.above(34 - len), hang.above(34), Blocks.IRON_CHAIN.defaultBlockState(), f);
			level.setBlock(hang.above(33 - len), Blocks.SOUL_LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true), f);
		}
		// eight curved bone spikes rising from the ground towards the tower
		for (int k = 0; k < 8; k++) {
			double a = Math.toRadians(k * 45 + 22.5);
			for (int i = 0; i <= 26; i++) {
				double t = i / 26.0;
				double r = 26 - 8 * t * t;
				int y = (int) Math.round(30 * Math.sin(t * Math.PI / 2));
				BlockPos p = o.offset((int) Math.round(Math.cos(a) * r), y, (int) Math.round(Math.sin(a) * r));
				BlockState bone = t > 0.85 ? Blocks.GILDED_BLACKSTONE.defaultBlockState() : Blocks.BONE_BLOCK.defaultBlockState();
				level.setBlock(p, bone, f);
				if (t < 0.5) {
					level.setBlock(p.above(), bone, f);
				}
			}
		}
		// lava moat with a bridge to the gate
		for (int x = -20; x <= 20; x++) {
			for (int z = -20; z <= 20; z++) {
				double d = Math.sqrt(x * x + z * z);
				if (d >= 16 && d < 19.2) {
					boolean bridge = z > 0 && Math.abs(x) <= 3;
					level.setBlock(o.offset(x, -1, z), bridge ? Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState()
							: Blocks.LAVA.defaultBlockState(), f);
				}
			}
		}
	}

	private static double wrap(double deg) {
		double d = deg % 360;
		if (d > 180) {
			d -= 360;
		}
		if (d < -180) {
			d += 360;
		}
		return d;
	}

	private static int hash(int a, int b) {
		int h = a * 374761393 + b * 668265263;
		h = (h ^ (h >>> 13)) * 1274126177;
		return (h ^ (h >>> 16)) & 0x7FFFFFFF;
	}

	private static void fill(ServerLevel level, BlockPos a, BlockPos b, BlockState s, int flags) {
		for (BlockPos p : BlockPos.betweenClosed(a, b)) {
			level.setBlock(p, s, flags);
		}
	}

	private static JsonArray arr(double... v) {
		JsonArray a = new JsonArray();
		for (double d : v) {
			a.add(d);
		}
		return a;
	}
}
