package de.behindtheblocks.cinematic.director;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.behindtheblocks.cinematic.BtbCinematic;
import de.behindtheblocks.cinematic.ambience.AmbienceManager;
import de.behindtheblocks.cinematic.camera.CameraManager;
import de.behindtheblocks.cinematic.camera.CameraPath;
import de.behindtheblocks.cinematic.camera.CameraPoint;
import de.behindtheblocks.cinematic.camera.CameraPose;
import de.behindtheblocks.cinematic.camera.Easing;
import de.behindtheblocks.cinematic.content.BtbBlocks;
import de.behindtheblocks.cinematic.core.CinematicEngine;
import de.behindtheblocks.cinematic.showcase.ShowcaseManager;
import de.behindtheblocks.cinematic.world.BlockSnapshot;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The complete film set for the storyboard, built in a line along +X from the player:
 *
 * <pre>
 *   x   0– 60  Bastion: hall (arrival), corridor + book room, open courtyard (beat drop), corridor with lava walls
 *   x  61–111  Burning bridges over a lava lake (fire on both edges, bridge collapses behind the actors)
 *   x 112–200  Lava desert: soul sand fields, basalt, ghasts at the horizon, jump'n'run over a lava river
 *   x 207–283  Fortress around the (taller, glowing) Heart Tower: waves, boss with the key, locked gate
 *   x 332–380  Portal hall with the half-built white portal and the keystone
 *   x 390–450  Exit: the second portal in a wasteland full of enemies
 * </pre>
 * Every trigger, collapse, portal, camera path and scene (film_01 … film_07 and film_vorschau)
 * is created through the normal /btb commands, so all of it can be changed in the Director afterwards.
 */
final class FilmSet {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/filmset");
	static final int TX = 245;

	private final CinematicEngine engine;
	private final ServerLevel level;
	private BlockPos o;
	private final int f = Block.UPDATE_CLIENTS;
	private int failed;
	/** Ground height (relative to the origin) per section: bastion, desert, fortress, hall, exit. */
	private int b1, b3, b4, b6, b7;
	/** Extra distance (blocks) inserted before a section: bastion|bridges, desert|fortress, fortress|gorge, hall|exit. */
	private int g1 = 30, g3 = 44, g4 = 24, g5 = 90;
	/** Sideways swing of the route (z): the desert and the gorge curve, so the set is not one straight line. */
	private static final int D2 = -28, D3 = -10;
	/** Site footprints (logical): bastion, bridges, desert, fortress, gorge, hall, exit. */
	private static final int[][] SITES = {
			{0, -18, 60, 18}, {61, -22, 111, 22}, {112, -32, 200, 32}, {201, -38, 283, 38},
			{284, -14, 331, 14}, {332, -18, 384, 18}, {388, -32, 452, 32}};
	private static final int[] SITE_HEIGHT = {20, 14, 16, 118, 22, 30, 20};
	/** Blend ring around each site: the terrain slopes from the set floor back to the natural ground. */
	private static final int RING = 12;
	private final List<String> failures = new ArrayList<>();

	private Direction facing = Direction.EAST;
	/** Sections to build (indices into SITES); all for the full set. */
	private final java.util.BitSet parts = new java.util.BitSet();

	/** Single pieces that can be placed on their own: name → section index. */
	static final java.util.Map<String, Integer> PIECES = new java.util.LinkedHashMap<>();

	static {
		PIECES.put("bastion", 0);
		PIECES.put("bruecken", 1);
		PIECES.put("wueste", 2);
		PIECES.put("festung", 3);
		PIECES.put("schlucht", 4);
		PIECES.put("portalhalle", 5);
		PIECES.put("ausgang", 6);
	}

	private FilmSet(CinematicEngine engine, ServerLevel level, BlockPos o) {
		this.engine = engine;
		this.level = level;
		this.o = o;
	}

	private boolean has(int i) {
		return parts.get(i);
	}

	private boolean full() {
		return parts.cardinality() == SITES.length;
	}

	static Path dir(ServerLevel level) {
		return level.getServer().getWorldPath(LevelResource.ROOT).resolve(BtbCinematic.MOD_ID);
	}


	// ------------------------------------------------------------------ build / remove

	/** What each section creates (to remove a single piece cleanly): triggers, destructions, portals, parkours, ambiences, scenes. */
	private static final String[][][] IDS = {
			{{"buch", "ausbruch"}, {"lava_wand_1", "lava_wand_2", "decke_gang"}, {}, {}, {"bastion"}, {"film_01_bastion", "film_02_buch", "film_03_ausbruch"}},
			{{"bruecke", "bruecke_ende"}, {"bruecke_feuer_n", "bruecke_feuer_s", "bruecke_einsturz"}, {}, {}, {"lavasee"}, {}},
			{{"wueste", "festung_blick"}, {}, {}, {"felsen"}, {"wueste", "herzschlag"}, {"film_04_wueste"}},
			{{"festung", "festung_blick2", "turm_boss", "tor", "herz"}, {"festungstor", "turm_einsturz"}, {}, {}, {"herzturm"},
					{"film_05_festung", "film_05_herz", "film_turm_einsturz"}},
			{{}, {"saeule_1", "saeule_2"}, {}, {}, {}, {}},
			{{}, {}, {"film_tor_a"}, {}, {"portalhalle"}, {"film_06_portal"}},
			{{}, {}, {"film_tor_b"}, {}, {"ausgang"}, {"film_07_ende"}}};

	/** File prefix: "film" for the whole set, "film_<piece>" for a single piece. */
	private static String key(String piece) {
		return piece == null ? "film" : "film_" + piece;
	}

	static Path originFile(ServerLevel level, String piece) {
		return dir(level).resolve(piece == null ? "film_origin.txt" : key(piece) + "_origin.txt");
	}

	/** Builds the whole set (piece = null) or one piece, starting at the origin and running in the given direction. */
	static String build(CinematicEngine engine, ServerLevel level, BlockPos origin, Direction facing, String piece) throws IOException {
		if (Files.exists(originFile(level, piece))) {
			return piece == null ? "Das Film-Set steht schon – erst /btb film remove" : "„" + piece + "“ steht schon – erst /btb film remove " + piece;
		}
		Integer idx = piece == null ? null : PIECES.get(piece);
		if (piece != null && idx == null) {
			return "Unbekanntes Teil: " + piece + " (" + String.join(", ", PIECES.keySet()) + ")";
		}
		FilmSet s = new FilmSet(engine, level, origin);
		s.facing = facing.getAxis().isHorizontal() ? facing : Direction.EAST;
		if (idx == null) {
			s.parts.set(0, SITES.length);
		} else {
			s.parts.set(idx);
			// a single piece starts where the player stands: its entrance is at the origin
			int x1 = SITES[idx][0];
			s.g1 = s.g3 = s.g4 = s.g5 = 0;
			s.o = origin;
			BlockPos shift = s.raw(x1 + s.gap(x1), 0, bend(x1));
			s.o = origin.offset(origin.getX() - shift.getX(), 0, origin.getZ() - shift.getZ());
		}
		Files.createDirectories(dir(level));
		s.measure();
		List<BlockPos[]> boxes = s.boxes();
		String k = key(piece);
		// keep the set loaded: lighting, triggers, ambience and mobs work everywhere, nothing pops in late
		setChunks(level, k, boxes, true);
		for (int i = 0; i < boxes.size(); i++) {
			BlockSnapshot.capture(level, boxes.get(i)[0], boxes.get(i)[1]).save(dir(level).resolve(k + "_backup_" + i + ".nbt"));
		}
		Files.writeString(originFile(level, piece), level.dimension().identifier() + " " + s.o.getX() + " " + s.o.getY() + " " + s.o.getZ()
				+ " " + s.facing.getName() + " " + s.parts.stream().mapToObj(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
		if (s.has(0)) s.bastion();
		if (s.has(1)) s.bridges();
		if (s.has(2)) s.desert();
		if (s.has(3)) s.fortress();
		if (s.has(4)) s.gorgePath();
		if (s.has(5)) s.portalHall();
		if (s.has(6)) s.exitArena();
		if (s.full()) {
			for (Trail t : s.trails()) {
				s.trail(t);
			}
		}
		s.dressing();
		s.wire();
		LOG.info("Film set {} built at {} facing {} ({} setup command(s) failed{})", k, s.o, s.facing, s.failed,
				s.failures.isEmpty() ? "" : ": " + s.failures);
		return s.failed == 0 ? null : "Gebaut, aber " + s.failed + " Einrichtungs-Befehl(e) schlugen fehl – siehe Log";
	}

	static String remove(CinematicEngine engine, ServerLevel level, String piece) throws IOException {
		if (!Files.exists(originFile(level, piece))) {
			return piece == null ? "Kein komplettes Film-Set vorhanden (einzelne Teile: /btb film remove <teil>)" : "„" + piece + "“ ist nicht gebaut";
		}
		String[] parts = Files.readString(originFile(level, piece)).trim().split(" ");
		BlockPos origin = new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
		FilmSet s = new FilmSet(engine, level, origin);
		java.util.BitSet which = new java.util.BitSet();
		if (parts.length >= 6) {
			for (String n : parts[5].split(",")) {
				which.set(Integer.parseInt(n));
			}
		} else {
			which.set(0, SITES.length);
		}
		s.quiet("btb resetall");
		s.quiet("btb spawn clear");
		for (int i = which.nextSetBit(0); i >= 0; i = which.nextSetBit(i + 1)) {
			String[][] ids = IDS[i];
			for (String t : ids[0]) s.quiet("btb trigger delete " + t);
			for (String d : ids[1]) s.quiet("btb destruction delete " + d);
			for (String p : ids[2]) s.quiet("btb portal delete " + p);
			for (String p : ids[3]) s.quiet("btb parkour delete " + p);
			for (String a : ids[4]) engine.module(AmbienceManager.class).ifPresent(am -> am.remove(a));
			for (String sc : ids[5]) s.quiet("btb scene delete " + sc);
			if (i == 3) engine.module(ShowcaseManager.class).ifPresent(ShowcaseManager::stopAll);
		}
		if (which.cardinality() == SITES.length) {
			s.quiet("btb scene delete film_vorschau");
		}
		for (String m : List.of("film_start", "film_buch", "film_tor", "film_schluessel")) {
			s.quiet("btb mark remove " + m);
		}
		String k = key(piece);
		// restore in reverse order: overlapping boxes were all captured before building, so any order is fine
		for (int i = 63; i >= 0; i--) {
			Path p = dir(level).resolve(k + "_backup_" + i + ".nbt");
			if (Files.exists(p)) {
				BlockSnapshot.load(level, p).restore(level);
				Files.delete(p);
			}
		}
		chunks(level, k, false);
		Files.deleteIfExists(dir(level).resolve(k + "_chunks.txt"));
		Files.deleteIfExists(originFile(level, piece));
		return null;
	}

	/** Names of everything that is built right now ("komplett" and/or pieces). */
	static List<String> built(ServerLevel level) {
		List<String> out = new ArrayList<>();
		if (Files.exists(originFile(level, null))) {
			out.add("komplett");
		}
		for (String pc : PIECES.keySet()) {
			if (Files.exists(originFile(level, pc))) {
				out.add(pc);
			}
		}
		return out;
	}

	/** Force-loading on/off for everything that is built. */
	static int chunksAll(ServerLevel level, boolean on) throws IOException {
		int n = chunks(level, "film", on);
		for (String pc : PIECES.keySet()) {
			n += chunks(level, key(pc), on);
		}
		return n;
	}

	// ------------------------------------------------------------------ chunks

	/** Force-loads (or releases) every chunk under the given boxes and remembers them in film_chunks.txt. */
	private static void setChunks(ServerLevel level, String k, List<BlockPos[]> boxes, boolean on) throws IOException {
		java.util.Set<Long> set = new java.util.LinkedHashSet<>();
		for (BlockPos[] b : boxes) {
			for (int cx = b[0].getX() >> 4; cx <= b[1].getX() >> 4; cx++) {
				for (int cz = b[0].getZ() >> 4; cz <= b[1].getZ() >> 4; cz++) {
					set.add(net.minecraft.world.level.ChunkPos.pack(cx, cz));
				}
			}
		}
		StringBuilder sb = new StringBuilder();
		for (long c : set) {
			sb.append(net.minecraft.world.level.ChunkPos.getX(c)).append(' ').append(net.minecraft.world.level.ChunkPos.getZ(c)).append('\n');
		}
		Files.writeString(dir(level).resolve(k + "_chunks.txt"), sb.toString());
		chunks(level, k, on);
	}

	/** Switches the force-loading of the remembered set chunks; returns how many chunks that were. */
	static int chunks(ServerLevel level, String k, boolean on) throws IOException {
		Path f = dir(level).resolve(k + "_chunks.txt");
		if (!Files.exists(f)) {
			return 0;
		}
		int n = 0;
		for (String line : Files.readAllLines(f)) {
			String[] p = line.trim().split(" ");
			if (p.length == 2) {
				level.setChunkForced(Integer.parseInt(p[0]), Integer.parseInt(p[1]), on);
				n++;
			}
		}
		return n;
	}

	// ------------------------------------------------------------------ helpers

	private BlockPos p(int x, int y, int z) {
		return raw(x + gap(x), y + dy(x), z + bend(x));
	}

	/** World block for set-space coordinates (x along the route incl. gaps, z sideways incl. bends), rotated to the facing. */
	private BlockPos raw(int lx, int y, int lz) {
		return switch (facing) {
			case SOUTH -> new BlockPos(o.getX() - lz, o.getY() + y, o.getZ() + lx);
			case WEST -> new BlockPos(o.getX() - lx, o.getY() + y, o.getZ() - lz);
			case NORTH -> new BlockPos(o.getX() + lz, o.getY() + y, o.getZ() - lx);
			default -> new BlockPos(o.getX() + lx, o.getY() + y, o.getZ() + lz);
		};
	}

	private Vec3 rawVec(double lx, double y, double lz) {
		return switch (facing) {
			case SOUTH -> new Vec3(o.getX() - lz + 1, o.getY() + y, o.getZ() + lx);
			case WEST -> new Vec3(o.getX() - lx + 1, o.getY() + y, o.getZ() - lz + 1);
			case NORTH -> new Vec3(o.getX() + lz, o.getY() + y, o.getZ() - lx + 1);
			default -> new Vec3(o.getX() + lx, o.getY() + y, o.getZ() + lz);
		};
	}

	/** Minecraft yaw of the route direction (logical +x). */
	private float routeYaw() {
		return switch (facing) {
			case SOUTH -> 0;
			case WEST -> 90;
			case NORTH -> 180;
			default -> -90;
		};
	}

	private net.minecraft.world.level.block.Rotation rotation() {
		return switch (facing) {
			case SOUTH -> net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
			case WEST -> net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
			case NORTH -> net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
			default -> net.minecraft.world.level.block.Rotation.NONE;
		};
	}

	/** A compass word of the unrotated set (east = along the route) turned to the facing. */
	private String dir(String d) {
		Direction in = switch (d) {
			case "north", "-z" -> Direction.NORTH;
			case "south", "+z" -> Direction.SOUTH;
			case "west", "-x" -> Direction.WEST;
			default -> Direction.EAST;
		};
		Direction out = rotation().rotate(in);
		if (d.startsWith("+") || d.startsWith("-")) {
			return switch (out) {
				case NORTH -> "-z";
				case SOUTH -> "+z";
				case WEST -> "-x";
				default -> "+x";
			};
		}
		return out.getName();
	}

	/** Distance inserted before the section containing x (sections move apart, trails fill the space). */
	private int gap(double x) {
		if (x < 61) {
			return 0;
		}
		if (x < 201) {
			return g1;
		}
		if (x < 284) {
			return g1 + g3;
		}
		if (x < 386) {
			return g1 + g3 + g4;
		}
		return g1 + g3 + g4 + g5;
	}

	/** Sideways offset of the route at x; constant across buildings, curving in the open sections. */
	private static int bend(double x) {
		if (x < 112) {
			return 0;
		}
		if (x < 201) {
			return (int) Math.round(D2 * smooth((x - 112) / 88.0));
		}
		if (x < 284) {
			return D2;
		}
		if (x < 332) {
			return (int) Math.round(D2 + (D3 - D2) * smooth((x - 284) / 47.0));
		}
		return D3;
	}

	private static double smooth(double t) {
		t = Math.max(0, Math.min(1, t));
		return t * t * (3 - 2 * t);
	}

	/** Height offset of the set at x: each section sits on the terrain, bridges/paths ramp in between. */
	private int dy(double x) {
		if (x < 61) {
			return b1;
		}
		if (x < 112) {
			return ramp(x, 60, 111, b1, b3);
		}
		if (x < 201) {
			return b3;
		}
		if (x < 284) {
			return b4;
		}
		if (x < 332) {
			return ramp(x, 284, 332, b4, b6);
		}
		return x < 386 ? b6 : b7;
	}

	private static int ramp(double x, double x0, double x1, int y0, int y1) {
		double t = Math.max(0, Math.min(1, (x - x0) / (x1 - x0)));
		return (int) Math.round(y0 + (y1 - y0) * t);
	}

	/** Measures the existing ground under each section (lava/water count as surface). */
	private void measure() {
		if (!full()) {
			// a single piece: one height for all of it, measured under the piece itself
			int i = parts.nextSetBit(0);
			int v = groundDy((SITES[i][0] + SITES[i][2]) / 2);
			b1 = b3 = b4 = b6 = b7 = v;
			LOG.info("Film piece {} height (relative): {}", i, v);
			return;
		}
		b1 = groundDy(30);
		b3 = groundDy(156);
		b4 = groundDy(TX);
		b6 = groundDy(356);
		b7 = groundDy(420);
		// the way from the desert up/down to the fortress must stay walkable (≤ 1 block per 3)
		int need = Math.max(44, 3 * Math.abs(b4 - b3));
		if (need != g3) {
			g3 = need;
			b4 = groundDy(TX);
			b6 = groundDy(356);
			b7 = groundDy(420);
		}
		LOG.info("Film set heights (relative): bastion {}, desert {}, fortress {}, hall {}, exit {}", b1, b3, b4, b6, b7);
	}

	private int groundDy(int x) {
		List<Integer> hs = new ArrayList<>();
		for (int z : new int[] {-10, 0, 10}) {
			for (int dx : new int[] {-12, 0, 12}) {
				BlockPos base = raw(x + dx + gap(x), 0, z + bend(x));
				BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(base.getX(), Math.min(level.getMaxY() - 2, o.getY() + 32), base.getZ());
				int found = 0;
				for (int k = 0; k < 72; k++, m.move(0, -1, 0)) {
					BlockState st = level.getBlockState(m), above = level.getBlockState(m.above());
					boolean ground = !st.isAir() && (st.isSolid() || !st.getFluidState().isEmpty()) && !st.is(net.minecraft.tags.BlockTags.LEAVES);
					if (ground && (above.isAir() || above.canBeReplaced())) {
						found = m.getY() + 1 - o.getY();
						break;
					}
				}
				hs.add(found);
			}
		}
		hs.sort(Integer::compare);
		return Math.max(-20, Math.min(20, hs.get(hs.size() / 2)));
	}

	/** Where a walkway ramps up or down by a block, a stair so actors can walk instead of jump. */
	private void stairsOnRamp(int x1, int x2, int z1, int z2, Block stairs) {
		for (int x = x1; x < x2; x++) {
			int a = dy(x), bnext = dy(x + 1);
			for (int z = z1; z <= z2; z++) {
				if (bnext > a) {
					level.setBlock(p(x, 0, z), stairs.defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.EAST).rotate(rotation()), f);
				} else if (bnext < a) {
					level.setBlock(p(x + 1, 0, z), stairs.defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.WEST).rotate(rotation()), f);
				}
			}
		}
	}

	/** Backup boxes covering the set incl. foundations, sized to the measured heights. */
	private List<BlockPos[]> boxes() {
		List<BlockPos[]> out = new ArrayList<>();
		for (int i = parts.nextSetBit(0); i >= 0; i = parts.nextSetBit(i + 1)) {
			int[] st = SITES[i];
			int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
			for (int x = st[0]; x <= st[2]; x++) {
				minY = Math.min(minY, dy(x));
				maxY = Math.max(maxY, dy(x));
				minZ = Math.min(minZ, bend(x));
				maxZ = Math.max(maxZ, bend(x));
			}
			int gx = gap(st[0]);
			out.add(box(raw(gx + st[0] - RING - 1, minY - 46, st[1] + minZ - RING - 1),
					raw(gx + st[2] + RING + 1, maxY + Math.max(SITE_HEIGHT[i], 48) + 2, st[3] + maxZ + RING + 1)));
		}
		if (full()) {
			for (Trail t : trails()) {
				int sw = (int) Math.ceil(Math.abs(t.swing())) + TRAIL_SIDE + 2;
				out.add(box(raw(t.xa(), Math.min(t.ya(), t.yb()) - 48, Math.min(t.za(), t.zb()) - sw),
						raw(t.xb(), Math.max(t.ya(), t.yb()) + 34, Math.max(t.za(), t.zb()) + sw)));
			}
		}
		return out;
	}

	/** Axis-aligned world box from two (rotated) corners, clamped to the build height. */
	private BlockPos[] box(BlockPos a, BlockPos b) {
		int lo = level.getMinY(), hi = level.getMaxY() - 1;
		return new BlockPos[] {
				new BlockPos(Math.min(a.getX(), b.getX()), Math.max(lo, Math.min(a.getY(), b.getY())), Math.min(a.getZ(), b.getZ())),
				new BlockPos(Math.max(a.getX(), b.getX()), Math.min(hi, Math.max(a.getY(), b.getY())), Math.max(a.getZ(), b.getZ()))};
	}

	// ------------------------------------------------------------------ trails between the sections

	/** A path in set space from (xa, za, ya) to (xb, zb, yb); swing = sideways bulge in the middle. */
	private record Trail(int xa, int xb, int za, int zb, int ya, int yb, double swing) {
	}

	private static final int TRAIL_SIDE = 8;

	private List<Trail> trails() {
		List<Trail> out = new ArrayList<>();
		int[][] links = {{60, 61, 1}, {200, 201, -1}, {283, 284, 1}};
		for (int[] l : links) {
			int xa = l[0] + gap(l[0]), xb = l[1] + gap(l[1]);
			if (xb - xa < 4) {
				continue;
			}
			out.add(new Trail(xa, xb, bend(l[0]), bend(l[1]), dy(l[0]), dy(l[1]), l[2] * Math.min(12, (xb - xa) / 4.0)));
		}
		return out;
	}

	/**
	 * Builds a winding path across open terrain: a ruined road with a gentle S-curve, embankments where the
	 * ground drops, cut slopes where it rises, a bridge on pillars over deep dips, posts with soul lanterns,
	 * basalt spikes and roots. Heights ease from one section to the next, with stairs on every step.
	 */
	private void trail(Trail t) {
		int len = t.xb() - t.xa();
		for (int lx = t.xa() + 1; lx < t.xb(); lx++) {
			double s = (lx - t.xa()) / (double) len;
			double zc = t.za() + (t.zb() - t.za()) * smooth(s) + t.swing() * Math.pow(Math.sin(Math.PI * s), 2);
			int yf = (int) Math.round(t.ya() + (t.yb() - t.ya()) * smooth(s));
			double sn = (lx + 1 - t.xa()) / (double) len;
			int yNext = (int) Math.round(t.ya() + (t.yb() - t.ya()) * smooth(sn));
			int zi = (int) Math.round(zc);
			BlockPos centre = raw(lx, yf, zi);
			int gc = scanTop(centre.getX(), centre.getZ(), centre.getY() + 30, centre.getY() - 45);
			boolean bridge = gc < centre.getY() - 12;
			for (int dz = -(2 + TRAIL_SIDE); dz <= 2 + TRAIL_SIDE; dz++) {
				int d = Math.abs(dz);
				int w = 2 + (hash(lx, 21, dz) % 4 == 0 ? 1 : 0);
				BlockPos c = raw(lx, yf, zi + dz);
				int wx = c.getX(), wz = c.getZ(), y0 = c.getY();
				if (onFootprint(c)) {
					continue;
				}
				int g = scanTop(wx, wz, y0 + 30, y0 - 45);
				BlockState top = surface(level.getBlockState(new BlockPos(wx, g, wz)));
				if (d <= w) {
					// the road itself
					int hm = hash(lx, 5, dz) % 100;
					BlockState road = hm < 30 ? b(Blocks.SOUL_SOIL) : hm < 55 ? b(Blocks.BLACKSTONE) : hm < 68 ? b(Blocks.NETHERRACK)
							: hm < 84 ? b(Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS) : hm < 97 ? b(Blocks.POLISHED_BLACKSTONE_BRICKS) : b(Blocks.GILDED_BLACKSTONE);
					level.setBlock(new BlockPos(wx, y0 - 1, wz), road, f);
					if (bridge) {
						if (dz == 0 && lx % 6 == 0) {
							for (int y = y0 - 2; y > Math.max(g, y0 - 46); y--) {
								level.setBlock(new BlockPos(wx, y, wz), b(Blocks.POLISHED_BASALT), f);
							}
						}
						if (d == w && lx % 2 == 0) {
							level.setBlock(new BlockPos(wx, y0, wz), b(Blocks.NETHER_BRICK_FENCE), f);
						}
					} else {
						for (int y = Math.max(g + 1, y0 - 40); y <= y0 - 2; y++) {
							level.setBlock(new BlockPos(wx, y, wz), y > y0 - 4 ? b(Blocks.NETHERRACK) : b(Blocks.BLACKSTONE), f);
						}
					}
					// headroom; through a high hill a short tunnel instead of a canyon
					int clearTo = g > y0 + 22 ? y0 + 4 : Math.max(g, y0 + 4);
					for (int y = y0; y <= clearTo; y++) {
						if (!(bridge && d == w && y == y0 && lx % 2 == 0)) {
							level.setBlock(new BlockPos(wx, y, wz), b(Blocks.AIR), f);
						}
					}
					if (yNext != yf && !bridge) {
						Direction up = yNext > yf ? Direction.EAST : Direction.WEST;
						BlockPos at = yNext > yf ? new BlockPos(wx, y0, wz) : raw(lx + 1, yNext, zi + dz);
						level.setBlock(at, Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS.defaultBlockState()
								.setValue(net.minecraft.world.level.block.StairBlock.FACING, up).rotate(rotation()), f);
					}
				} else if (!bridge) {
					// shoulders: embankment down to lower ground, cut slope up into higher ground
					int k = d - w;
					int emb = y0 - 1 - k;
					if (g < emb) {
						BlockState below = under(top);
						for (int y = Math.max(g + 1, emb - 40); y <= emb; y++) {
							level.setBlock(new BlockPos(wx, y, wz), y == emb ? top : below, f);
						}
						g = emb;
					}
					int cutLine = y0 - 1 + (int) (k * 1.3) + hash(lx, 9, dz) % 2;
					if (g > cutLine) {
						for (int y = cutLine + 1; y <= Math.min(g, cutLine + 40); y++) {
							level.setBlock(new BlockPos(wx, y, wz), b(Blocks.AIR), f);
						}
						level.setBlock(new BlockPos(wx, cutLine, wz), top, f);
						g = cutLine;
					}
					// dressing on the verge
					int r = hash(lx, 31, dz) % 100;
					BlockPos on = new BlockPos(wx, g + 1, wz);
					if (k == 1 && lx % 11 == 0 && (dz > 0) == ((lx / 11) % 2 == 0)) {
						level.setBlock(on, b(Blocks.POLISHED_BLACKSTONE_WALL), f);
						level.setBlock(on.above(), b(Blocks.POLISHED_BLACKSTONE_WALL), f);
						level.setBlock(on.above(2), b(Blocks.SOUL_LANTERN), f);
					} else if (k >= 3 && r < 3) {
						int hh = 2 + hash(lx, 33, dz) % 5;
						for (int y = 0; y < hh; y++) {
							level.setBlock(on.above(y), y == hh - 1 ? b(Blocks.BLACKSTONE) : b(Blocks.BASALT), f);
						}
					} else if (k >= 2 && r < 12 && level.getBlockState(on).isAir()) {
						Block g0 = level.getBlockState(on.below()).getBlock();
						if (g0 == Blocks.CRIMSON_NYLIUM || g0 == Blocks.NETHERRACK) {
							level.setBlock(on, r < 8 ? b(Blocks.CRIMSON_ROOTS) : b(Blocks.FIRE), f);
						} else if (g0 == Blocks.SOUL_SOIL || g0 == Blocks.SOUL_SAND) {
							level.setBlock(on, b(Blocks.SOUL_FIRE), f);
						} else if (g0 == Blocks.WARPED_NYLIUM) {
							level.setBlock(on, b(Blocks.WARPED_ROOTS), f);
						}
					}
				}
			}
		}
	}

	private BlockPos[] clampBox(BlockPos a, BlockPos b) {
		int lo = level.getMinY(), hi = level.getMaxY() - 1;
		return new BlockPos[] {new BlockPos(a.getX(), Math.max(lo, a.getY()), a.getZ()), new BlockPos(b.getX(), Math.min(hi, b.getY()), b.getZ())};
	}

	/**
	 * Prepares a footprint on the existing terrain: clears it up to {@code height}, lays a nether floor
	 * and fills a foundation down to the ground, so the set stands on hills and over dips alike.
	 */
	private void site(int x1, int z1, int x2, int z2, int height) {
		for (int x = x1; x <= x2; x++) {
			for (int z = z1; z <= z2; z++) {
				// clear the footprint – up to the natural top if a hill stands here, so nothing hangs over the set
				BlockPos col = p(x, 0, z);
				int top = scanTop(col.getX(), col.getZ(), col.getY() + 44, col.getY() - 40);
				int clear = Math.max(height, Math.min(top - col.getY(), 44));
				for (int y = 0; y <= clear; y++) {
					set(x, y, z, b(Blocks.AIR));
				}
				int h = hash(x, 7, z) % 23;
				set(x, -1, z, h == 0 ? b(Blocks.MAGMA_BLOCK) : h == 1 ? b(Blocks.SOUL_SOIL) : h == 2 ? b(Blocks.CRIMSON_NYLIUM)
						: h == 3 ? b(Blocks.BLACKSTONE) : b(Blocks.NETHERRACK));
				for (int y = -2; y >= -34; y--) {
					BlockState st = level.getBlockState(p(x, y, z));
					if (!st.isAir() && st.getFluidState().isEmpty() && st.isSolid()) {
						break;
					}
					set(x, y, z, y > -4 ? b(Blocks.NETHERRACK) : b(Blocks.BLACKSTONE));
				}
			}
		}
		blendRing(x1, z1, x2, z2);
	}

	/** World columns covered by the footprints of everything being built (rings and trails stay off them). */
	private java.util.Set<Long> footprint;

	private boolean onFootprint(BlockPos w) {
		if (footprint == null) {
			footprint = new java.util.HashSet<>();
			for (int i = parts.nextSetBit(0); i >= 0; i = parts.nextSetBit(i + 1)) {
				int[] st = SITES[i];
				for (int x = st[0]; x <= st[2]; x++) {
					for (int z = st[1]; z <= st[3]; z++) {
						BlockPos q = p(x, 0, z);
						footprint.add(BlockPos.asLong(q.getX(), 0, q.getZ()));
					}
				}
			}
		}
		for (Direction d : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP}) {
			BlockPos q = d == Direction.UP ? w : w.relative(d);
			if (footprint.contains(BlockPos.asLong(q.getX(), 0, q.getZ()))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Around a footprint the ground is eased from the set floor back to the natural terrain: hills are cut
	 * into slopes, dips are filled with the local ground material. The edge is irregular, so no square shows.
	 */
	private void blendRing(int x1, int z1, int x2, int z2) {
		for (int x = x1 - RING; x <= x2 + RING; x++) {
			int xc = Math.max(x1, Math.min(x2, x));
			for (int z = z1 - RING; z <= z2 + RING; z++) {
				if (x >= x1 && x <= x2 && z >= z1 && z <= z2) {
					continue;
				}
				double ddx = Math.max(0, Math.max(x1 - x, x - x2)), ddz = Math.max(0, Math.max(z1 - z, z - z2));
				double d = Math.sqrt(ddx * ddx + ddz * ddz) + (hash(x, 11, z) % 5 - 2) * 0.7;
				if (d > RING || d < 0.5) {
					continue;
				}
				BlockPos w = raw(x + gap(xc), dy(xc) - 1, z + bend(xc));
				if (onFootprint(w)) {
					continue;
				}
				int floor = w.getY();
				int g = scanTop(w.getX(), w.getZ(), floor + 40, floor - 40);
				BlockState top = surface(level.getBlockState(new BlockPos(w.getX(), g, w.getZ())));
				int h = (int) Math.round(floor + (g - floor) * smooth(d / RING));
				if (g < h) {
					BlockState below = under(top);
					for (int y = Math.max(g + 1, h - 40); y <= h; y++) {
						level.setBlock(new BlockPos(w.getX(), y, w.getZ()), y == h ? top : below, f);
					}
				} else if (g > h) {
					for (int y = h + 1; y <= Math.min(g, h + 48); y++) {
						level.setBlock(new BlockPos(w.getX(), y, w.getZ()), b(Blocks.AIR), f);
					}
					level.setBlock(new BlockPos(w.getX(), h, w.getZ()), top, f);
				}
			}
		}
	}

	/** Highest ground block (solid or fluid with air above) in a column between yHi and yLo; yLo if none. */
	private int scanTop(int wx, int wz, int yHi, int yLo) {
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(wx, Math.min(level.getMaxY() - 2, yHi), wz);
		for (; m.getY() > Math.max(level.getMinY(), yLo); m.move(0, -1, 0)) {
			BlockState st = level.getBlockState(m), above = level.getBlockState(m.above());
			boolean ground = !st.isAir() && (st.isSolid() || !st.getFluidState().isEmpty()) && !st.is(net.minecraft.tags.BlockTags.LEAVES);
			if (ground && (above.isAir() || above.canBeReplaced())) {
				return m.getY();
			}
		}
		return yLo;
	}

	/** The local ground material to continue with (any dimension), falling back to netherrack. */
	private static BlockState surface(BlockState st) {
		if (!st.getFluidState().isEmpty() || !st.isSolid() || st.hasBlockEntity() || !st.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
			return b(Blocks.NETHERRACK);
		}
		return st.getBlock().defaultBlockState();
	}

	private static BlockState under(BlockState top) {
		Block t = top.getBlock();
		if (t == Blocks.GRASS_BLOCK || t == Blocks.PODZOL || t == Blocks.MYCELIUM || t == Blocks.DIRT_PATH) {
			return b(Blocks.DIRT);
		}
		if (t == Blocks.SOUL_SAND) {
			return b(Blocks.SOUL_SOIL);
		}
		if (t == Blocks.CRIMSON_NYLIUM || t == Blocks.WARPED_NYLIUM || t == Blocks.MAGMA_BLOCK) {
			return b(Blocks.NETHERRACK);
		}
		return top;
	}

	private void set(int x, int y, int z, BlockState s) {
		level.setBlock(p(x, y, z), s.rotate(rotation()), f);
	}

	private void fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockState s) {
		// per column, so boxes over a height ramp follow the ramp
		for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
			for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
				for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
					set(x, y, z, s);
				}
			}
		}
	}

	private static int hash(int a, int b, int c) {
		int h = a * 73856093 ^ b * 19349663 ^ c * 83492791;
		h ^= h >>> 13;
		h *= 0x5bd1e995;
		return (h ^ h >>> 15) & 0x7fffffff;
	}

	private static BlockState b(Block block) {
		return block.defaultBlockState();
	}

	/** Weathered blackstone wall mix (bastion). */
	private BlockState wall(int x, int y, int z) {
		int h = hash(x, y, z) % 100;
		if (h < 52) {
			return b(Blocks.POLISHED_BLACKSTONE_BRICKS);
		}
		if (h < 78) {
			return b(Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS);
		}
		if (h < 92) {
			return b(Blocks.BLACKSTONE);
		}
		return h < 97 ? b(Blocks.GILDED_BLACKSTONE) : b(Blocks.POLISHED_BLACKSTONE);
	}

	private String abs(double x, double y, double z) {
		Vec3 v = at(x, y, z);
		return String.format(Locale.ROOT, "%.1f %.1f %.1f", v.x, v.y, v.z);
	}

	private String blk(int x, int y, int z) {
		BlockPos b = p(x, y, z);
		return b.getX() + " " + b.getY() + " " + b.getZ();
	}

	/** Runs a /btb command as the server (optionally positioned); failures are counted, not fatal. */
	private void cmd(String command) {
		cmdAt(command, null);
	}

	/** Clean-up before (re)creating: "doesn't exist yet" is not an error. */
	private void quiet(String command) {
		int before = failed;
		cmdAt(command, null);
		failures.subList(Math.min(failures.size(), failures.size() - (failed - before)), failures.size()).clear();
		failed = before;
	}

	private void cmdAt(String command, Vec3 at) {
		CommandSourceStack src = level.getServer().createCommandSourceStack().withLevel(level).withSuppressedOutput()
				.withCallback((ok, r) -> {
					if (!ok) {
						failed++;
						failures.add(command);
					}
				});
		if (at != null) {
			src = src.withPosition(at);
		}
		try {
			level.getServer().getCommands().performPrefixedCommand(src, command);
		} catch (RuntimeException e) {
			failed++;
			failures.add(command + " (" + e.getMessage() + ")");
		}
	}

	private Vec3 at(double x, double y, double z) {
		return rawVec(x + gap(x), y + dy(x), z + bend(x));
	}

	/** Adds a step to a scene (seconds). */
	private void step(String scene, double second, String command) {
		cmd("btb scene add " + scene + " " + String.format(Locale.ROOT, "%.2f", second) + " " + command);
	}

	private void scene(String id, double seconds) {
		quiet("btb scene delete " + id);
		cmd("btb scene create " + id + " " + seconds);
	}

	// ------------------------------------------------------------------ terrain

	// ------------------------------------------------------------------ 1 bastion (0:00 – 1:32)

	private void bastion() {
		site(0, -18, 60, 18, 20);
		int x1 = 6, x2 = 60, z1 = -14, z2 = 14, h = 15;
		fill(x1, -1, z1, x2, -1, z2, b(Blocks.POLISHED_BLACKSTONE_BRICKS));
		for (int x = x1; x <= x2; x++) {
			for (int z = z1; z <= z2; z++) {
				if (hash(x, -1, z) % 6 == 0) {
					set(x, -1, z, b(Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS));
				}
			}
		}
		// outer walls, eroded at the top and with a few holes
		for (int y = 0; y <= h; y++) {
			for (int x = x1; x <= x2; x++) {
				for (int z = z1; z <= z2; z++) {
					boolean shell = x == x1 || x == x2 || z == z1 || z == z2;
					if (!shell) {
						continue;
					}
					boolean eroded = y >= 11 && hash(x, y, z) % 6 < y - 10;
					boolean hole = (x == 30 && z == z1 && y >= 4 && y <= 7) || (x == 52 && z == z2 && y >= 5 && y <= 9);
					if (!eroded && !hole) {
						set(x, y, z, wall(x, y, z));
					}
				}
			}
		}
		// interior walls with doorways: hall | corridor A | courtyard | corridor B
		for (int wx : new int[] {20, 34, 48}) {
			for (int y = 0; y <= (wx == 34 || wx == 48 ? 10 : h); y++) {
				for (int z = z1 + 1; z <= z2 - 1; z++) {
					if (!(Math.abs(z) <= 2 && y <= 4)) {
						set(wx, y, z, wall(wx, y, z));
					}
				}
			}
		}
		// corridors A (21..33) and B (49..59): side walls and low ceiling
		for (int[] c : new int[][] {{21, 33}, {49, 59}}) {
			for (int x = c[0]; x <= c[1]; x++) {
				for (int y = 0; y <= 7; y++) {
					set(x, y, -4, wall(x, y, -4));
					set(x, y, 4, wall(x, y, 4));
				}
				for (int z = -3; z <= 3; z++) {
					set(x, 8, z, b(Blocks.POLISHED_BLACKSTONE_BRICKS));
				}
			}
		}
		// book room (north of corridor A) with its own ceiling, door from the corridor
		for (int x = 24; x <= 32; x++) {
			for (int y = 0; y <= 8; y++) {
				set(x, y, 13, wall(x, y, 13));
			}
			for (int z = 5; z <= 13; z++) {
				set(x, 8, z, b(Blocks.POLISHED_BLACKSTONE_BRICKS));
			}
		}
		for (int z = 5; z <= 13; z++) {
			for (int y = 0; y <= 8; y++) {
				set(24, y, z, wall(24, y, z));
				set(32, y, z, wall(32, y, z));
			}
		}
		fill(27, 0, 4, 29, 3, 4, b(Blocks.AIR));
		set(28, 7, 9, b(Blocks.SHROOMLIGHT));
		set(28, 0, 10, b(Blocks.CHISELED_POLISHED_BLACKSTONE));
		set(28, 1, 10, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.HAS_BOOK, true)
				.setValue(LecternBlock.FACING, Direction.NORTH));
		for (int[] g : new int[][] {{25, 12}, {31, 12}, {25, 6}, {31, 6}}) {
			set(g[0], 0, g[1], b(Blocks.GOLD_BLOCK));
			set(g[0], 1, g[1], b(Blocks.SOUL_LANTERN));
		}
		// the other side room: rubble
		for (int x = 22; x <= 32; x++) {
			for (int z = -13; z <= -5; z++) {
				int hh = hash(x, 3, z) % 4;
				for (int y = 0; y < hh; y++) {
					set(x, y, z, hash(x, y, z) % 3 == 0 ? b(Blocks.GILDED_BLACKSTONE) : b(Blocks.BLACKSTONE));
				}
			}
		}
		// roof over hall and corridor A (with a big hole for the camera above the hall)
		for (int x = x1 + 1; x <= 33; x++) {
			for (int z = z1 + 1; z <= z2 - 1; z++) {
				boolean holeHall = (x - 13) * (x - 13) + z * z <= 16;
				if (!holeHall && hash(x, h, z) % 9 != 0) {
					set(x, h, z, b(Blocks.POLISHED_BLACKSTONE_BRICKS));
				}
			}
		}
		// hanging soul lanterns in the hall, cobwebs, braziers
		for (int[] l : new int[][] {{9, -8}, {17, -8}, {9, 8}, {17, 8}}) {
			for (int y = 11; y <= 14; y++) {
				set(l[0], y, l[1], b(Blocks.IRON_CHAIN));
			}
			set(l[0], 10, l[1], Blocks.SOUL_LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
		}
		for (int[] w : new int[][] {{7, 14, -13}, {7, 14, 13}, {19, 13, -13}, {21, 7, -3}, {33, 7, 3}, {49, 7, -3}}) {
			set(w[0], w[1], w[2], b(Blocks.COBWEB));
		}
		for (int[] br : new int[][] {{10, 0}, {16, 0}, {36, -12}, {46, 12}}) {
			set(br[0], 0, br[1] == 0 ? -12 : br[1], b(Blocks.NETHERRACK));
			set(br[0], 1, br[1] == 0 ? -12 : br[1], b(Blocks.SOUL_FIRE));
		}
		// courtyard: open sky, rubble mounds and gold
		for (int[] m : new int[][] {{38, -9}, {44, 8}, {41, 11}}) {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					int hh = 3 - Math.max(Math.abs(dx), Math.abs(dz));
					for (int y = 0; y < hh; y++) {
						set(m[0] + dx, y, m[1] + dz, hash(dx, y, dz) % 4 == 0 ? b(Blocks.GOLD_BLOCK) : b(Blocks.BLACKSTONE));
					}
				}
			}
		}
		// corridor B: magma behind the two walls that will burst
		fill(51, 1, -5, 53, 3, -5, b(Blocks.MAGMA_BLOCK));
		fill(55, 1, 5, 57, 3, 5, b(Blocks.MAGMA_BLOCK));
		fill(51, 1, -4, 53, 3, -4, b(Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS));
		fill(55, 1, 4, 57, 3, 4, b(Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS));
		// exit
		fill(x2, 0, -2, x2, 4, 2, b(Blocks.AIR));
	}

	// ------------------------------------------------------------------ 2 burning bridges (1:33 – 2:18)

	private void bridges() {
		site(61, -22, 111, 22, 14);
		int x1 = 61, x2 = 111;
		fill(x1, -4, -22, x2, -4, 22, b(Blocks.NETHERRACK));
		fill(x1 + 2, -3, -22, x2 - 2, -2, 22, b(Blocks.LAVA));
		fill(x1 + 2, -1, -22, x2 - 2, -1, 22, b(Blocks.AIR));
		// main bridge: walk z -1..1, burning edges z ±2, fences z ±3
		fill(x1, -1, -3, x2, -1, 3, b(Blocks.NETHER_BRICKS));
		for (int x = x1; x <= x2; x++) {
			if (x % 2 == 0) {
				set(x, 0, -3, b(Blocks.NETHER_BRICK_FENCE));
				set(x, 0, 3, b(Blocks.NETHER_BRICK_FENCE));
			}
			if ((x - x1) % 8 == 4) {
				fill(x, -3, -3, x, -2, -3, b(Blocks.NETHER_BRICKS));
				fill(x, -3, 3, x, -2, 3, b(Blocks.NETHER_BRICKS));
			}
		}
		stairsOnRamp(x1, x2, -2, 2, Blocks.NETHER_BRICK_STAIRS);
		// a ruined second bridge in the background
		for (int x = x1; x <= x2; x++) {
			if (hash(x, 1, 9) % 5 != 0 && !(x > 80 && x < 90)) {
				fill(x, -1, -12, x, -1, -10, b(Blocks.RED_NETHER_BRICKS));
				if (x % 3 == 0) {
					set(x, 0, -12, b(Blocks.NETHER_BRICK_FENCE));
				}
			}
		}
	}

	// ------------------------------------------------------------------ 3 lava desert + jump'n'run (2:19 – 3:05)

	private void desert() {
		site(112, -32, 200, 32, 16);
		int x1 = 112, x2 = 200;
		for (int x = x1; x <= x2; x++) {
			for (int z = -32; z <= 32; z++) {
				int h = hash(x, 2, z) % 100;
				BlockState s;
				if (x >= 118 && x <= 146 && hash(x / 5, 3, z / 5) % 3 != 0) {
					s = h < 70 ? b(Blocks.SOUL_SAND) : b(Blocks.SOUL_SOIL);
				} else {
					s = h < 60 ? b(Blocks.NETHERRACK) : h < 72 ? b(Blocks.MAGMA_BLOCK) : h < 86 ? b(Blocks.BLACKSTONE) : b(Blocks.BASALT);
				}
				set(x, -1, z, s);
			}
		}
		// basalt pillars (not on the path)
		for (int i = 0; i < 40; i++) {
			int x = x1 + hash(i, 4, 1) % (x2 - x1), z = hash(i, 5, 2) % 61 - 30;
			if (Math.abs(z) < 6 || (x > 148 && x < 180)) {
				continue;
			}
			int hh = 3 + hash(i, 6, 3) % 9;
			for (int y = 0; y < hh; y++) {
				set(x, y, z, y == hh - 1 ? b(Blocks.BLACKSTONE) : b(Blocks.POLISHED_BASALT));
				if (hh > 6 && y < hh - 3) {
					set(x + 1, y, z, b(Blocks.BASALT));
				}
			}
		}
		// small lava pools
		for (int[] pl : new int[][] {{124, -18}, {138, 20}, {190, -16}, {196, 18}}) {
			for (int dx = -3; dx <= 3; dx++) {
				for (int dz = -3; dz <= 3; dz++) {
					if (dx * dx + dz * dz <= 8) {
						set(pl[0] + dx, -1, pl[1] + dz, b(Blocks.LAVA));
					}
				}
			}
		}
		// lava river with stepping stones (jump'n'run)
		fill(152, -3, -14, 178, -3, 14, b(Blocks.NETHERRACK));
		fill(152, -2, -14, 178, -1, 14, b(Blocks.LAVA));
		int[][] stones = {{155, -1, 0}, {160, 2, 1}, {165, -2, 2}, {170, 1, 1}, {175, 0, 0}};
		for (int[] s : stones) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					for (int y = -2; y <= s[2]; y++) {
						set(s[0] + dx, y, s[1] + dz, y == s[2] ? b(Blocks.BLACKSTONE) : b(Blocks.BASALT));
					}
				}
			}
		}
		// path to the fortress
		fill(196, -1, -2, 200, -1, 2, b(Blocks.POLISHED_BLACKSTONE_BRICKS));
	}

	// ------------------------------------------------------------------ 4 fortress + heart tower (3:06 – 3:52)

	private void fortress() {
		site(201, -38, 283, 38, 118);
		fill(201, -1, -2, 216, -1, 2, b(Blocks.POLISHED_BLACKSTONE_BRICKS));
		BlockPos t = p(TX, 0, 0);
		DemoSet.buildTower(level, t, f);
		turnTower(t);
		// taller and brighter: a glowing spire with a shroomlight crown
		for (int y = 78; y <= 104; y++) {
			double r = Math.max(0.6, 4.5 - (y - 78) * 0.16);
			for (int dx = -5; dx <= 5; dx++) {
				for (int dz = -5; dz <= 5; dz++) {
					double d = Math.sqrt(dx * dx + dz * dz);
					if (d <= r) {
						double ang = Math.atan2(dz, dx) + y * 0.35;
						boolean vein = d > r - 1.2 && Math.abs(Math.sin(ang * 3)) > 0.85;
						set(TX + dx, y, dz, d < 1 ? b(Blocks.SHROOMLIGHT) : vein ? b(BtbBlocks.NETHER_VEIN) : b(BtbBlocks.HEARTSTONE_BRICKS));
					}
				}
			}
		}
		set(TX, 105, 0, b(Blocks.CRYING_OBSIDIAN));
		set(TX, 106, 0, b(Blocks.SHROOMLIGHT));
		set(TX, 107, 0, b(Blocks.END_ROD));
		// glowing bands around the tower body
		for (int y : new int[] {18, 36, 54, 70}) {
			double r = 13.5 - y * 0.11;
			for (int a = 0; a < 360; a += 3) {
				int dx = (int) Math.round(Math.cos(Math.toRadians(a)) * r), dz = (int) Math.round(Math.sin(Math.toRadians(a)) * r);
				if (!level.getBlockState(p(TX + dx, y, dz)).isAir()) {
					set(TX + dx, y, dz, b(BtbBlocks.NETHER_VEIN));
				}
			}
		}
		// fortress ring (relative to the tower), entrance west, locked gate east
		int r = 28;
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
					continue;
				}
				for (int y = 0; y <= 9; y++) {
					set(TX + dx, y, dz, hash(dx, y, dz) % 7 == 0 ? b(Blocks.RED_NETHER_BRICKS)
							: hash(dx, y, dz) % 9 == 0 ? b(Blocks.CRACKED_NETHER_BRICKS) : b(Blocks.NETHER_BRICKS));
				}
				if ((dx + dz) % 2 == 0) {
					set(TX + dx, 10, dz, b(Blocks.NETHER_BRICKS));
				}
			}
		}
		for (int[] c : new int[][] {{-r, -r}, {-r, r}, {r, -r}, {r, r}}) {
			fill(TX + c[0] - 2, 0, c[1] - 2, TX + c[0] + 2, 14, c[1] + 2, b(Blocks.NETHER_BRICKS));
			set(TX + c[0], 15, c[1], b(Blocks.SHROOMLIGHT));
		}
		// broken entrance in the west wall
		fill(TX - r, 0, -2, TX - r, 6, 2, b(Blocks.AIR));
		set(TX - r, 7, -1, b(Blocks.AIR));
		// path inside: west entrance → around to the tower bridge in the south
		fill(TX - r + 1, -1, -2, TX - 20, -1, 2, b(Blocks.POLISHED_BLACKSTONE_BRICKS));
		// the locked gate in the east wall: iron bars that rise (gate effect) + the lock
		// gatehouse: two towers, a high lintel and an open slot the portcullis slides up into
		for (int side : new int[] {-1, 1}) {
			fill(TX + r - 1, 0, side * 4, TX + r + 1, 17, side * 6, b(Blocks.POLISHED_BLACKSTONE_BRICKS));
			set(TX + r + 1, 12, side * 5, b(Blocks.SHROOMLIGHT));
		}
		fill(TX + r, 0, -3, TX + r, 15, 3, b(Blocks.AIR));
		fill(TX + r - 1, 16, -4, TX + r + 1, 17, 4, b(Blocks.POLISHED_BLACKSTONE_BRICKS));
		for (int z = -3; z <= 3; z++) {
			set(TX + r - 1, 15, z, b(Blocks.IRON_CHAIN));
			set(TX + r + 1, 15, z, b(Blocks.IRON_CHAIN));
		}
		fill(TX + r, 0, -3, TX + r, 6, 3, b(Blocks.IRON_BARS));
		set(TX + r - 2, 0, -5, b(Blocks.POLISHED_BLACKSTONE));
		set(TX + r - 2, 1, -5, b(BtbBlocks.PORTAL_KEYSTONE));
		// landing in front of the gate; from here the gorge path leads to the portal hall
		fill(TX + r + 1, -1, -3, 283, -1, 3, b(Blocks.POLISHED_BLACKSTONE_BRICKS));
	}

	// ------------------------------------------------------------------ 4b the way: through the lava gorge to the portal hall

	/**
	 * Leaving the fortress through the gate, the actors cross a lava gorge on a narrow, broken bridge:
	 * cliffs on both sides, lava falls, arches with hanging lanterns, and two pillars that topple into
	 * the gorge as they pass.
	 */
	private void gorgePath() {
		int x1 = 284, x2 = 331;
		site(x1, -14, x2, 14, 22);
		// the gorge: deep, with a lava river at the bottom
		for (int x = x1 + 2; x <= x2 - 2; x++) {
			for (int z = -9; z <= 9; z++) {
				for (int y = -9; y <= -1; y++) {
					set(x, y, z, y <= -8 ? b(Blocks.LAVA) : b(Blocks.AIR));
				}
				set(x, -10, z, b(Blocks.BLACKSTONE));
			}
		}
		// cliffs left and right with jagged tops
		for (int x = x1; x <= x2; x++) {
			for (int side : new int[] {-1, 1}) {
				for (int w = 10; w <= 13; w++) {
					int top = 6 + hash(x, w, side) % 9;
					for (int y = -10; y <= top; y++) {
						set(x, y, side * w, hash(x, y, w) % 5 == 0 ? b(Blocks.BASALT) : hash(x, y, w) % 4 == 0 ? b(Blocks.MAGMA_BLOCK) : b(Blocks.BLACKSTONE));
					}
				}
			}
		}
		// lava falls pouring out of the cliff faces into the gorge
		for (int[] lf : new int[][] {{292, -9}, {305, 9}, {318, -9}, {326, 9}}) {
			set(lf[0], 6, lf[1], b(Blocks.LAVA));
		}
		// the narrow bridge (walk z -1..1), a few cracked and missing edge blocks
		for (int x = x1; x <= x2; x++) {
			for (int z = -1; z <= 1; z++) {
				if (!(Math.abs(z) == 1 && hash(x, 3, z) % 7 == 0)) {
					set(x, -1, z, hash(x, 2, z) % 4 == 0 ? b(Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS) : b(Blocks.POLISHED_BLACKSTONE_BRICKS));
				}
			}
			if (x % 6 == 0) {
				fill(x, -9, 0, x, -2, 0, b(Blocks.POLISHED_BASALT)); // supports down into the lava
			}
		}
		stairsOnRamp(284, 332, -1, 1, Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS);
		// arches with hanging soul lanterns
		for (int x = x1 + 6; x <= x2 - 4; x += 11) {
			for (int side : new int[] {-1, 1}) {
				fill(x, -9, side * 3, x, 6, side * 3, b(Blocks.POLISHED_BASALT));
			}
			fill(x, 7, -3, x, 7, 3, b(Blocks.POLISHED_BLACKSTONE_BRICKS));
			set(x, 8, 0, b(Blocks.GILDED_BLACKSTONE));
			for (int y = 5; y <= 6; y++) {
				set(x, y, 0, b(Blocks.IRON_CHAIN));
			}
			set(x, 4, 0, Blocks.SOUL_LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
		}
		// two tall pillars beside the bridge – they topple into the gorge when the actors pass
		for (int[] pl : new int[][] {{299, -5}, {316, 5}}) {
			for (int y = -9; y <= 13; y++) {
				for (int dx = 0; dx <= 1; dx++) {
					for (int dz = 0; dz <= 1; dz++) {
						set(pl[0] + dx, y, pl[1] + dz, y % 5 == 0 ? b(Blocks.CHISELED_POLISHED_BLACKSTONE) : b(Blocks.POLISHED_BLACKSTONE_BRICKS));
					}
				}
			}
		}
	}

	// ------------------------------------------------------------------ 5 portal hall (3:53 – 4:09)

	private void portalHall() {
		site(332, -18, 384, 18, 30);
		int x1 = 332, x2 = 380, z1 = -16, z2 = 16, h = 26;
		fill(x1, -1, z1, x2, -1, z2, b(Blocks.POLISHED_BLACKSTONE_BRICKS));
		for (int y = 0; y <= h; y++) {
			for (int x = x1; x <= x2; x++) {
				for (int z = z1; z <= z2; z++) {
					boolean shell = x == x1 || x == x2 || z == z1 || z == z2;
					if (shell && !(y >= 20 && hash(x, y, z) % 5 < y - 19)) {
						set(x, y, z, (x - x1) % 8 == 0 || (z - z1) % 8 == 0 ? b(Blocks.POLISHED_BASALT) : wall(x, y, z));
					}
				}
			}
		}
		fill(x1, 0, -3, x1, 6, 3, b(Blocks.AIR)); // entrance from the fortress
		// roof only over the front half – the back is open to the burning sky
		for (int x = x1; x <= 354; x++) {
			for (int z = z1; z <= z2; z++) {
				if (hash(x, h, z) % 7 != 0) {
					set(x, h, z, b(Blocks.POLISHED_BLACKSTONE_BRICKS));
				}
			}
		}
		// the big, half-finished white portal (plane x = 367, inside z -5..5, y 1..16)
		radiantFrame(367, -6, 6, -1, 16, true); // bottom bar in the floor: walk straight in
		// scaffolding on top: still under construction
		for (int z = -6; z <= 6; z += 3) {
			fill(366, 17, z, 366, 20, z, b(Blocks.SCAFFOLDING));
		}
		// keystone pedestal in front
		set(359, 0, 0, b(Blocks.CRYING_OBSIDIAN));
		set(359, 1, 0, b(BtbBlocks.PORTAL_KEYSTONE));
		for (int[] c : new int[][] {{361, -8}, {361, 8}, {373, -8}, {373, 8}}) {
			for (int y = 0; y <= 12; y++) {
				set(c[0], y, c[1], y % 4 == 3 ? b(Blocks.SHROOMLIGHT) : b(Blocks.POLISHED_BASALT));
			}
		}
		for (int[] l : new int[][] {{340, -8}, {340, 8}, {348, -8}, {348, 8}}) {
			for (int y = 19; y <= 25; y++) {
				set(l[0], y, l[1], b(Blocks.IRON_CHAIN));
			}
			set(l[0], 18, l[1], Blocks.SOUL_LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
		}
	}

	/**
	 * Light and atmosphere for every section: chandeliers, braziers, beams of light through the roof
	 * openings, glowing cracks, ground fog and soul crystals. Only placed into air (nothing gets overwritten).
	 */
	private void dressing() {
		if (has(0)) {
			for (int[] c : new int[][] {{10, 10, -5}, {16, 10, 5}, {10, 10, 5}, {16, 10, -5}}) {
				air(c[0], c[1], c[2], BtbBlocks.CHANDELIER);
				for (int y = c[1] + 1; y < 15; y++) {
					air(c[0], y, c[2], Blocks.IRON_CHAIN);
				}
			}
			for (int y = 0; y < 15; y++) { // beam through the roof hole onto the arrival spot
				air(13, y, 0, BtbBlocks.LIGHT_SHAFT);
				if (y < 12) {
					air(12, y, 1, BtbBlocks.LIGHT_SHAFT);
				}
			}
			for (int[] b : new int[][] {{36, -6}, {46, -6}, {36, 6}, {46, 3}}) {
				air(b[0], 0, b[1], BtbBlocks.BRAZIER);
			}
			for (int x = 50; x <= 58; x += 2) {
				solid(x, -1, x % 4 == 0 ? -2 : 2, BtbBlocks.EMBER_CRACK);
			}
			for (int[] v : new int[][] {{24, 0}, {30, -2}, {52, 0}, {57, 1}}) {
				solid(v[0], -1, v[1], BtbBlocks.FOG_VENT, true);
			}
			air(26, 0, 12, BtbBlocks.SOUL_CRYSTAL_LAMP);
			air(30, 0, 12, BtbBlocks.SOUL_CRYSTAL_LAMP);
		}
		if (has(1)) {
			for (int x : new int[] {62, 110}) {
				air(x, 0, -4, BtbBlocks.BRAZIER);
				air(x, 0, 4, BtbBlocks.BRAZIER);
			}
		}
		if (has(2)) {
			for (int i = 0; i < 18; i++) {
				int x = 114 + hash(i, 40, 1) % 84, z = hash(i, 41, 2) % 50 - 25;
				if (Math.abs(z) > 4 && (x < 150 || x > 180)) {
					solid(x, -1, z, BtbBlocks.EMBER_CRACK);
				}
			}
			air(196, 0, -4, BtbBlocks.BRAZIER);
			air(196, 0, 4, BtbBlocks.BRAZIER);
		}
		if (has(3)) {
			for (int[] b : new int[][] {{TX - 29, -4}, {TX - 29, 4}, {TX + 30, -8}, {TX + 30, 8}, {TX - 14, 0}}) {
				air(b[0], 0, b[1], BtbBlocks.BRAZIER);
			}
			for (int a = 0; a < 360; a += 45) { // soul crystals around the altar, glowing cracks in between
				int x = (int) Math.round(Math.cos(Math.toRadians(a)) * 6), z = (int) Math.round(Math.sin(Math.toRadians(a)) * 6);
				air(TX + x, 0, z, BtbBlocks.SOUL_CRYSTAL_LAMP);
				int x2 = (int) Math.round(Math.cos(Math.toRadians(a + 22)) * 4), z2 = (int) Math.round(Math.sin(Math.toRadians(a + 22)) * 4);
				solid(TX + x2, -1, z2, BtbBlocks.EMBER_CRACK);
			}
		}
		if (has(4)) {
			for (int x = 288; x <= 328; x += 8) {
				solid(x, -1, x % 16 == 0 ? -1 : 1, BtbBlocks.FOG_VENT, true);
			}
		}
		if (has(5)) {
			for (int[] c : new int[][] {{340, 0}, {348, 0}}) {
				air(c[0], 20, c[1], BtbBlocks.CHANDELIER);
				for (int y = 21; y < 26; y++) {
					air(c[0], y, c[1], Blocks.IRON_CHAIN);
				}
			}
			for (int[] b : new int[][] {{358, -6}, {364, 9}, {372, -10}, {376, 6}}) { // beams through the open roof
				for (int y = 0; y < 26; y++) {
					air(b[0], y, b[1], BtbBlocks.LIGHT_SHAFT);
				}
			}
			for (int[] c : new int[][] {{361, -6}, {361, 6}, {365, -4}, {365, 4}}) {
				air(c[0], 0, c[1], BtbBlocks.SOUL_CRYSTAL_LAMP);
			}
			for (int[] v : new int[][] {{362, -2}, {362, 2}, {356, 0}}) {
				solid(v[0], -1, v[1], BtbBlocks.FOG_VENT, true);
			}
			// portal foci beside the frame, rune pillars along the walls, a glyph circle around the keystone
			air(365, 0, -8, BtbBlocks.PORTAL_FOCUS);
			air(365, 0, 8, BtbBlocks.PORTAL_FOCUS);
			for (int x = 336; x <= 376; x += 8) {
				for (int side : new int[] {-1, 1}) {
					for (int y = 0; y < 7; y++) {
						air(x, y, side * 15, BtbBlocks.RUNE_PILLAR);
					}
				}
			}
			for (int a = 0; a < 360; a += 30) {
				int gx = 359 + (int) Math.round(Math.cos(Math.toRadians(a)) * 3), gz = (int) Math.round(Math.sin(Math.toRadians(a)) * 3);
				solid(gx, -1, gz, BtbBlocks.GLYPH_PLATE);
			}
			for (int i = 0; i < 4; i++) {
				cmdAt("summon btb_cinematic:seelenwisp ~ ~ ~ {PersistenceRequired:1b,Tags:[\"film_wisp\"]}", at(344 + i * 8, 6 + i % 2 * 3, i % 2 == 0 ? -6 : 6));
			}
		}
		if (has(6)) {
			for (int i = 0; i < 12; i++) {
				int x = 392 + hash(i, 50, 3) % 56, z = hash(i, 51, 4) % 56 - 28;
				solid(x, -1, z, BtbBlocks.EMBER_CRACK);
			}
			air(400, 0, -8, BtbBlocks.BRAZIER);
			air(400, 0, 8, BtbBlocks.BRAZIER);
		}
	}

	/** Places a block only where there is air (dressing never breaks the set). */
	private void air(int x, int y, int z, Block block) {
		if (level.getBlockState(p(x, y, z)).isAir()) {
			set(x, y, z, b(block));
		}
	}

	/** Swaps a floor block (keeps air, lava and special blocks). */
	private void solid(int x, int y, int z, Block block) {
		solid(x, y, z, block, false);
	}

	private void solid(int x, int y, int z, Block block, boolean onTop) {
		BlockState st = level.getBlockState(p(x, y, z));
		if (st.isAir() || !st.getFluidState().isEmpty() || st.hasBlockEntity()) {
			return;
		}
		if (onTop) {
			if (level.getBlockState(p(x, y + 1, z)).isAir()) {
				set(x, y + 1, z, b(block));
			}
		} else {
			set(x, y, z, b(block));
		}
	}

	/** The tower is built facing east; for another facing it is turned in place around its axis. */
	private void turnTower(BlockPos t) {
		if (facing == Direction.EAST) {
			return;
		}
		int r = 20, y0 = -2, y1 = 96;
		BlockState[][][] copy = new BlockState[2 * r + 1][y1 - y0 + 1][2 * r + 1];
		for (int dx = -r; dx <= r; dx++) {
			for (int y = y0; y <= y1; y++) {
				for (int dz = -r; dz <= r; dz++) {
					copy[dx + r][y - y0][dz + r] = level.getBlockState(t.offset(dx, y, dz));
				}
			}
		}
		for (int dx = -r; dx <= r; dx++) {
			for (int y = y0; y <= y1; y++) {
				for (int dz = -r; dz <= r; dz++) {
					int nx, nz;
					switch (facing) {
						case SOUTH -> { nx = -dz; nz = dx; }
						case WEST -> { nx = -dx; nz = -dz; }
						default -> { nx = dz; nz = -dx; }
					}
					level.setBlock(t.offset(nx, y, nz), copy[dx + r][y - y0][dz + r].rotate(rotation()), f);
				}
			}
		}
	}

	/** Radiant frame in the plane x = px; with {@code unfinished} a few top blocks are missing. */
	private void radiantFrame(int px, int z1, int z2, int y1, int y2, boolean unfinished) {
		for (int y = y1; y <= y2; y++) {
			for (int z = z1; z <= z2; z++) {
				boolean border = y == y1 || y == y2 || z == z1 || z == z2;
				if (border && !(unfinished && y == y2 && hash(px, y, z) % 3 == 0)) {
					set(px, y, z, b(BtbBlocks.RADIANT_FRAME));
				}
			}
		}
	}

	// ------------------------------------------------------------------ 6 exit (4:10 – end)

	private void exitArena() {
		site(388, -32, 452, 32, 20);
		for (int x = 390; x <= 450; x++) {
			for (int z = -30; z <= 30; z++) {
				int h = hash(x, 9, z) % 100;
				set(x, -1, z, h < 45 ? b(Blocks.CRIMSON_NYLIUM) : h < 70 ? b(Blocks.NETHERRACK) : h < 85 ? b(Blocks.SOUL_SOIL) : b(Blocks.BLACKSTONE));
				if (h > 96) {
					set(x, 0, z, b(Blocks.FIRE));
				}
			}
		}
		for (int i = 0; i < 16; i++) {
			int x = 393 + hash(i, 1, 9) % 56, z = hash(i, 2, 9) % 57 - 28;
			if (Math.abs(z) < 9 && x < 417) {
				continue;
			}
			int hh = 2 + hash(i, 3, 9) % 7;
			for (int y = 0; y < hh; y++) {
				set(x, y, z, b(Blocks.BASALT));
			}
		}
		radiantFrame(405, -6, 6, -1, 16, false);
	}

	// ------------------------------------------------------------------ wiring: areas, triggers, portals, cameras, scenes

	private void wire() {
		String dim = level.dimension().identifier().toString();

		if (has(0)) {
			// lava walls in corridor B (burst when the actors run past) – world effect only, no screen shake/flash
			for (String[] lw : new String[][] {{"lava_wand_1", "51", "-4", "53", "south"}, {"lava_wand_2", "55", "4", "57", "north"}}) {
				int xa = Integer.parseInt(lw[1]), z = Integer.parseInt(lw[2]), xb = Integer.parseInt(lw[3]);
				quiet("btb destruction delete " + lw[0]);
				cmd("btb destruction create " + lw[0] + " " + blk(xa, 1, z) + " " + blk(xb, 3, z));
				cmd("btb destruction effect " + lw[0] + " wall_burst");
				cmd("btb destruction direction " + lw[0] + " " + dir(lw[4]));
				cmd("btb destruction proximity " + lw[0] + " 4");
				cmd("btb destruction screenfx " + lw[0] + " off");
			}
			// part of the corridor ceiling comes down after the book – dust trickles first as a warning
			quiet("btb destruction delete decke_gang");
			cmd("btb destruction create decke_gang " + blk(30, 8, -3) + " " + blk(33, 8, -1));
			cmd("btb destruction effect decke_gang collapse");
			cmd("btb destruction duration decke_gang 1.5");
			cmd("btb destruction proximity decke_gang 3");
			cmd("btb destruction warn decke_gang 1.5");
			// floor marks for the actors (crew only)
			cmdAt("btb mark add film_start green", at(13, 0, 0));
			cmdAt("btb mark add film_buch yellow", at(28, 0, 8));
			quiet("btb trigger delete buch");
			cmd("btb trigger interact buch " + blk(28, 1, 10));
			cmd("btb trigger action buch btb scene restart film_02_buch");
			zone("ausbruch", 41, 0, 3, "btb scene restart film_03_ausbruch");
		}
		if (has(1)) {
			// burning bridge edges + collapse behind the actors
			for (String[] bf : new String[][] {{"bruecke_feuer_n", "-2"}, {"bruecke_feuer_s", "2"}}) {
				quiet("btb destruction delete " + bf[0]);
				cmd("btb destruction create " + bf[0] + " " + blk(64, 0, Integer.parseInt(bf[1])) + " " + blk(108, 0, Integer.parseInt(bf[1])));
				cmd("btb destruction effect " + bf[0] + " fire");
				cmd("btb destruction duration " + bf[0] + " 4");
			}
			quiet("btb destruction delete bruecke_einsturz");
			cmd("btb destruction create bruecke_einsturz " + blk(68, -1, -3) + " " + blk(100, 0, 3));
			cmd("btb destruction effect bruecke_einsturz collapse");
			cmd("btb destruction duration bruecke_einsturz 4");
			zone("bruecke", 64, 0, 3, "btb destruction trigger bruecke_feuer_n", "btb destruction trigger bruecke_feuer_s",
					"btb sound play rumble 2 0.8");
			zone("bruecke_ende", 109, 0, 3, "btb destruction trigger bruecke_einsturz");
		}
		if (has(2)) {
			zone("wueste", 118, 0, 5, "btb scene restart film_04_wueste");
			if (has(3)) {
				zone("festung_blick", 198, 0, 5, "btb scene restart film_05_festung");
			}
			// jump'n'run over the lava river
			quiet("btb parkour delete felsen");
			cmdAt("btb parkour create felsen", at(150, 0, 0));
			cmdAt("btb parkour checkpoint felsen 2", at(150, 0, 0));
			int[][] stones = {{155, -1, 0}, {160, 2, 1}, {165, -2, 2}, {170, 1, 1}, {175, 0, 0}};
			for (int[] st : stones) {
				cmdAt("btb parkour checkpoint felsen 1.6", at(st[0] + 0.5, st[2] + 1, st[1] + 0.5));
			}
			cmdAt("btb parkour checkpoint felsen 2.5", at(181, 0, 0));
			cmd("btb parkour camera felsen 2 side 9 2 " + dir("+z"));
			cmd("btb parkour camera felsen 3 behind 6 3");
			cmd("btb parkour camera felsen 4 top 14 0");
			cmd("btb parkour camera felsen 5 side 9 2 " + dir("-z"));
			cmd("btb parkour camera felsen 6 behind 6 2");
			cmd("btb parkour camera felsen 7 none");
			cmd("btb parkour fally felsen " + (o.getY() + dy(150) - 1));
		}
		if (has(3)) {
			// the Heart Tower can really come down: its own scene film_turm_einsturz
			quiet("btb destruction delete turm_einsturz");
			cmd("btb destruction create turm_einsturz " + blk(TX - 15, 0, -15) + " " + blk(TX + 15, 107, 15));
			cmd("btb destruction effect turm_einsturz collapse");
			cmd("btb destruction duration turm_einsturz 16");
			cmd("btb destruction seed turm_einsturz 7");
			// the fortress gate (rises with the key into the gatehouse)
			quiet("btb destruction delete festungstor");
			cmd("btb destruction create festungstor " + blk(TX + 28, 0, -3) + " " + blk(TX + 28, 6, 3));
			cmd("btb destruction effect festungstor gate");
			cmd("btb destruction duration festungstor 4");
			cmd("btb destruction screenfx festungstor off");
			if (!has(2)) {
				// on its own: the reveal starts at the west entrance
				zone("festung_blick2", TX - 34, 0, 5, "btb scene restart film_05_festung");
			}
			zone("festung", TX - 24, 0, 3,
					"btb spawn wither_skeleton 4 " + abs(TX - 14, 0, 18) + " 4",
					"btb spawn piglin_brute 3 " + abs(TX - 14, 0, -18) + " 4");
			// at the tower gate: the heart is revealed, then its guardian wakes up (film_05_herz)
			zone("turm_boss", TX, 0, 15, "btb scene restart film_05_herz");
			cmdAt("btb mark add film_tor gold", at(TX + 25, 0, -4));
			quiet("btb trigger delete tor");
			cmd("btb trigger interact tor " + blk(TX + 26, 1, -5));
			cmd("btb trigger needitem tor btb_cinematic:nether_key");
			cmd("btb trigger action tor btb destruction trigger festungstor");
		}
		if (has(4)) {
			// the gorge: two pillars topple as the actors pass
			for (String[] pl : new String[][] {{"saeule_1", "299", "-5"}, {"saeule_2", "316", "5"}}) {
				int x = Integer.parseInt(pl[1]), z = Integer.parseInt(pl[2]);
				quiet("btb destruction delete " + pl[0]);
				cmd("btb destruction create " + pl[0] + " " + blk(x, -9, z) + " " + blk(x + 1, 13, z + 1));
				cmd("btb destruction effect " + pl[0] + " collapse");
				cmd("btb destruction duration " + pl[0] + " 3");
				cmd("btb destruction proximity " + pl[0] + " 6");
			}
		}
		if (has(5)) {
			// half-finished white portal in the hall
			quiet("btb portal delete film_tor_a");
			cmd("btb portal create film_tor_a " + blk(367, 0, -5) + " " + blk(367, 15, 5));
			cmd("btb portal style film_tor_a white");
			cmd("btb portal keystone film_tor_a " + blk(359, 1, 0));
			cmd("btb portal facing film_tor_a " + dir("east"));
			cmd("btb portal activationscene film_tor_a film_06_portal");
			cmdAt("btb mark add film_schluessel cyan", at(357, 0, 0));
		}
		if (has(6)) {
			// exit portal in the wasteland
			quiet("btb portal delete film_tor_b");
			cmd("btb portal create film_tor_b " + blk(405, 0, -5) + " " + blk(405, 15, 5));
			cmd("btb portal style film_tor_b white");
			cmd("btb portal requirekey film_tor_b off");
			cmd("btb portal facing film_tor_b " + dir("east"));
		}
		// pieces built one by one still find each other: hall → exit as soon as both exist
		quiet("btb portal link film_tor_a film_tor_b");
		quiet("btb portal arrival film_tor_a film_07_ende");

		if (has(3)) {
			// heart showcase on the altar: appears in film_05_herz and stays for all later scenes
			JsonObject sc = new JsonObject();
			sc.addProperty("item", "btb_cinematic:heart_of_the_nether");
			Vec3 hp0 = at(TX + 0.5, 2.5, 0.5);
			sc.add("pos", arr(hp0.x, hp0.y, hp0.z));
			sc.addProperty("dimension", dim);
			sc.addProperty("scale", 1.8);
			sc.addProperty("animation", "pulse");
			sc.addProperty("light", true);
			sc.addProperty("particles", "minecraft:flame");
			sc.addProperty("background", "ring");
			sc.addProperty("glow", "#FF5A00");
			// four Nether crystals circle the heart (like on the demo tower)
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
			engine.module(ShowcaseManager.class).orElseThrow().putDefinition("herz", sc);
		}

		ambience(dim);
		cameras();
		scenes();
	}

	private void zone(String id, double x, double y, double z, String... actions) {
		quiet("btb trigger delete " + id);
		cmdAt("btb trigger zone " + id + " 4", at(x + 0.5, y, z + 0.5));
		for (String a : actions) {
			cmd("btb trigger action " + id + " " + a);
		}
	}

	private static JsonArray arr(double... v) {
		JsonArray a = new JsonArray();
		for (double d : v) {
			a.add(d);
		}
		return a;
	}

	private void ambience(String dim) {
		AmbienceManager am = engine.module(AmbienceManager.class).orElseThrow();
		// lava lake: bubbling, flames
		JsonObject lake = zoneJson(dim, 86, 0, 0, 45);
		JsonArray lp = new JsonArray();
		lp.add(emitter("minecraft:lava", 3, 20, 0.5, 18, 0.2, -1));
		lp.add(emitter("minecraft:flame", 4, 20, 2, 18, 0.01, 0));
		lp.add(emitter("minecraft:large_smoke", 1, 20, 2, 18, 0.02, 3));
		lp.add(emitter("minecraft:small_flame", 3, 18, 6, 18, 0.03, 4));
		lake.add("particles", lp);
		lake.addProperty("grade_color", "#5A2A08");
		lake.addProperty("grade_alpha", 0.10);
		lake.addProperty("haze", 0.45);
		if (has(1)) am.putDefinition("lavasee", lake);
		// desert: ash, souls over the soul sand, rumbling
		JsonObject des = zoneJson(dim, 156, 2, 0, 60);
		JsonArray dp = new JsonArray();
		dp.add(emitter("minecraft:ash", 18, 30, 12, 30, 0.01, 8));
		dp.add(emitter("minecraft:soul", 2, 14, 1, 14, 0.02, 0));
		dp.add(emitter("minecraft:white_ash", 6, 30, 10, 30, 0.01, 6));
		des.add("particles", dp);
		des.addProperty("sound", "btb_cinematic:rumble");
		des.addProperty("sound_interval", 240);
		des.addProperty("sound_volume", 1.2);
		des.addProperty("sound_pitch", 0.7);
		des.addProperty("enabled", false);
		des.addProperty("grade_color", "#6A4018");
		des.addProperty("grade_alpha", 0.12);
		des.addProperty("haze", 0.8);
		if (has(2)) am.putDefinition("wueste", des);
		// heart tower: heartbeat every 2 s (same as the heart animation), red pulse, light beam up the spire
		JsonObject ht = zoneJson(dim, TX, 6, 0, 120);
		ht.addProperty("sound", "btb_cinematic:heart_pulse");
		ht.addProperty("sound_interval", 40);
		ht.addProperty("beat_near", true);
		ht.addProperty("sound_volume", 3.0);
		ht.addProperty("sound_pitch", 0.6);
		ht.addProperty("flash_color", "#500804");
		ht.addProperty("flash_interval", 40);
		ht.addProperty("shake_intensity", 0.04);
		ht.addProperty("shake_interval", 40);
		JsonArray hp = new JsonArray();
		hp.add(emitter("minecraft:end_rod", 4, 1.5, 14, 1.5, 0.01, 96));
		hp.add(emitter("dust:#FF3A10", 8, 0.4, 30, 0.4, 0, 106));
		hp.add(emitter("minecraft:soul", 2, 7, 3, 7, 0.02, -4));
		hp.add(emitter("minecraft:ash", 14, 26, 20, 26, 0.01, 14));
		hp.add(emitter("minecraft:crimson_spore", 4, 9, 6, 9, 0.01, 2));
		hp.add(emitter("minecraft:small_flame", 6, 20, 12, 20, 0.04, 8));
		ht.add("particles", hp);
		ht.addProperty("grade_color", "#4A0606");
		ht.addProperty("grade_alpha", 0.13);
		ht.addProperty("haze", 0.3);
		JsonArray orbit = new JsonArray();
		orbit.add(orbitJson("minecraft:blackstone", 14, 14, 72, 0.45, 1.6));
		orbit.add(orbitJson("btb_cinematic:heartstone_bricks", 10, 19, 58, -0.3, 1.1));
		orbit.add(orbitJson("minecraft:shroomlight", 8, 7, 96, 0.8, 0.8));
		ht.add("orbit_blocks", orbit);
		if (has(3)) am.putDefinition("herzturm", ht);
		// bastion: cold, washed-out, dust in the air – the abandoned place
		JsonObject bas = zoneJson(dim, 30, 4, 0, 40);
		JsonArray bp = new JsonArray();
		bp.add(emitter("minecraft:white_ash", 4, 24, 6, 14, 0.005, 2));
		bas.add("particles", bp);
		bas.addProperty("grade_color", "#203040");
		bas.addProperty("grade_alpha", 0.12);
		if (has(0)) am.putDefinition("bastion", bas);
		// the heartbeat motif: far away in the desert, it grows towards the fortress (loud at the tower)
		JsonObject hb = zoneJson(dim, 192, 2, 0, 48);
		hb.addProperty("sound", "btb_cinematic:heart_pulse");
		hb.addProperty("sound_interval", 40);
		hb.addProperty("sound_volume", 0.9);
		hb.addProperty("sound_pitch", 0.6);
		if (has(2)) am.putDefinition("herzschlag", hb);
		// portal hall: pale, light shafts of dust falling through the open roof
		JsonObject ph = zoneJson(dim, 360, 4, 0, 30);
		JsonArray pp = new JsonArray();
		pp.add(emitter("minecraft:end_rod", 2, 8, 10, 8, 0.002, 6));
		pp.add(emitter("minecraft:white_ash", 6, 10, 10, 10, 0.004, 8));
		ph.add("particles", pp);
		ph.addProperty("grade_color", "#D8D8E8");
		ph.addProperty("grade_alpha", 0.07);
		if (has(5)) am.putDefinition("portalhalle", ph);
		// exit: dark red, hot air
		JsonObject ex = zoneJson(dim, 420, 3, 0, 45);
		JsonArray xp = new JsonArray();
		xp.add(emitter("minecraft:ash", 12, 30, 10, 30, 0.01, 6));
		xp.add(emitter("minecraft:small_flame", 4, 26, 4, 26, 0.03, 1));
		ex.add("particles", xp);
		ex.addProperty("grade_color", "#300808");
		ex.addProperty("grade_alpha", 0.12);
		ex.addProperty("haze", 0.5);
		if (has(6)) am.putDefinition("ausgang", ex);
	}

	private JsonObject zoneJson(String dim, double x, double y, double z, double radius) {
		JsonObject a = new JsonObject();
		a.addProperty("dimension", dim);
		Vec3 v = at(x + 0.5, y, z + 0.5);
		a.add("pos", arr(v.x, v.y, v.z));
		a.addProperty("radius", radius);
		a.addProperty("enabled", true);
		return a;
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

	private static JsonObject orbitJson(String block, int count, double radius, double height, double speed, double scale) {
		JsonObject b = new JsonObject();
		b.addProperty("block", block);
		b.addProperty("count", count);
		b.addProperty("radius", radius);
		b.addProperty("height", height);
		b.addProperty("speed", speed);
		b.addProperty("scale", scale);
		return b;
	}

	// ------------------------------------------------------------------ cameras

	/** {x, y, z, lookX, lookY, lookZ, ticks to the next point} relative to the origin. */
	// ------------------------------------------------------------------ trailer (2:05, cut to the song)

	private static final String TR = "trailer";

	/** Teleports the viewer next to the shot (so the world and the creatures there are loaded) and starts the camera. */
	private void cut(double t, double px, double pz, String camera) {
		step(TR, t, "tp @a " + abs(px + 0.5, 3, pz + 0.5));
		step(TR, t, camera + " @a");
	}

	/** The hero (mannequin with the skin of {@code HERO}) at a spot, looking in a direction (yaw), optionally with a gesture. */
	private void hero(double t, double x, double z, double yaw, String gesture) {
		step(TR, t, "execute rotated " + (int) (yaw + 180) + " 0 run btb statist " + HERO + " " + abs(x + 0.5, 0, z + 0.5));
		if (gesture != null) {
			step(TR, t + 0.3, "execute positioned " + abs(x + 0.5, 0, z + 0.5) + " run btb geste @e[tag=btb_extra,limit=1,sort=nearest,distance=..3] "
					+ gesture + " 12");
		}
	}

	private static final String HERO = "Nik2810";

	private String orbit(double cx, double cy, double cz, double radius, double height, double seconds, double degrees) {
		return "btb camera orbit " + abs(cx, cy, cz) + " " + radius + " " + height + " " + seconds + " " + degrees;
	}

	/** A camera move from one point to another (both looking at their own target). */
	private String move(String id, double[] from, double[] to, double seconds) {
		path(id, new double[][] {{from[0], from[1], from[2], from[3], from[4], from[5], Math.round(seconds * 20)},
				{to[0], to[1], to[2], to[3], to[4], to[5], 1}});
		return "btb camera play " + id;
	}

	/**
	 * Trailer to the song (config/btb_cinematic/music/trailer.*): 0–5.8 s intro from black, 5.8–29.1 s calm
	 * build-up, 29.09 s drop – every cut sits on a hit of the song (161.5 BPM), 52.4 / 76.7 / 100.0 s new phrases,
	 * 120.37 s last hit, 121.7 s silence → title. Start: /btb film trailer · export with the song: /btb film trailer export
	 */
	private void trailer() {
		scene(TR, 125.2);
		// texts: opening credits (Directed by / Feat. with Spotify icon) and the end card (Coming 2027 + socials)
		quiet("btb hud delete trailer_vorspann");
		cmd("btb hud credits trailer_vorspann new vorspann");
		cmd("btb hud credits trailer_vorspann add Directed by | JeNeS & NikoBellic2810");
		cmd("btb hud credits trailer_vorspann add Feat. | Netherite by medcalf | spotify");
		cmd("btb hud credits trailer_vorspann tempo 2.85");
		quiet("btb hud delete trailer_ende");
		cmd("btb hud credits trailer_ende new endkarte THE HEART OF THE NETHER");
		cmd("btb hud credits trailer_ende title THE HEART OF THE NETHER | Coming 2027");
		cmd("btb hud credits trailer_ende link instagram @btb.minecraft");
		cmd("btb hud credits trailer_ende link tiktok @btb.plus");
		double fwd = routeYaw(); // looking along the route (towards the tower)
		// ---- look: cinema format, grain, warm Nether grade; the song itself (played once)
		step(TR, 0, "btb effects fade black 0");
		step(TR, 0, "btb effects format 2.39 0.1");
		step(TR, 0, "btb effects grain 0.4 1");
		step(TR, 0, "btb effects lut nether 0.55 1");
		step(TR, 0, "btb sound music musik.trailer 0 once");
		step(TR, 0, "btb ambience on herzturm");
		step(TR, 0, "btb portal activationscene film_tor_a none");

		// ---- 0.0 – 5.8 intro: over the wasteland towards the tower, out of the dark
		cut(0, 150, -40, move("tr_01", new double[] {118, 46, -70, TX, 70, 0}, new double[] {150, 36, -46, TX, 66, 0}, 6));
		step(TR, 0.4, "btb effects fadeout 4.5");
		step(TR, 1.6, "btb hud text kapitel 3.8 BEHIND THE BLOCKS | presents");
		step(TR, 1.0, "btb effects flare " + abs(TX + 0.5, 110, 0.5) + " orange 0.7");

		// ---- 5.8 – 29.1 build-up: four calm shots, one every four bars
		step(TR, 5.8, "btb effects flash white 0.35");
		step(TR, 5.8, "btb effects flare off");
		cut(5.8, 13, 0, move("tr_02", new double[] {-46, 34, -36, 18, 4, 0}, new double[] {-14, 20, -16, 16, 2, 0}, 6.2));
		step(TR, 6.2, "btb hud text titel 5.0 THE HEART OF THE NETHER");
		step(TR, 7.0, "btb hud text untertitel 4.0 TRAILER");
		step(TR, 17.8, "btb hud show trailer_vorspann");
		step(TR, 23.4, "btb hud hide trailer_vorspann");
		hero(5.0, 13, 0, fwd, null);              // in the bastion hall
		hero(11.0, 28, 6, fwd + 90, null);        // at the lectern, reading
		hero(23.0, 193, 5, fwd, null);            // looking at the tower on the horizon
		hero(33.0, 41, 1, fwd, "abwehren");       // between the enemies in the courtyard
		hero(59.0, TX, 4, fwd - 90, "abwehren");  // facing the guardian
		hero(91.5, 361, 0, fwd, null);            // in front of the portal
		hero(99.5, 411, 1, fwd, "zielen");        // out of the second portal, still in the Nether
		// the book: slow push towards the lectern, the page appears, the heartbeat begins
		cut(11.74, 28, 8, orbit(28, 1.4, 8, 3.2, 1.1, 6.2, 28));
		step(TR, 11.74, "btb effects dof 2.5 0.6 1");
		step(TR, 17.6, "btb effects dof off 0.5");
		for (double t : new double[] {12.4, 14.0, 15.6, 17.2}) {
			step(TR, t, "btb sound play heart_pulse 0.45 0.6");
		}
		// the corridor with the glowing lava walls, still holding
		cut(17.68, 54, 0, move("tr_04", new double[] {44, 2, 0, 56, 2, 0}, new double[] {50, 2.2, 0, 60, 2, 0}, 6));
		step(TR, 17.68, "btb effects haze 0.5 1");
		// the tower on the horizon: the message
		cut(23.62, 196, 4, move("tr_05", new double[] {188, 3, 12, TX, 60, 0}, new double[] {196, 13, 3, TX, 74, 0}, 5.6));
		step(TR, 23.62, "btb effects haze off 1");
		step(TR, 23.62, "btb effects flare " + abs(TX + 0.5, 110, 0.5) + " red 0.9");
		step(TR, 28.3, "btb effects fade black 0.6");
		step(TR, 28.3, "btb effects flare off");

		// ---- 29.09 DROP: action, one cut per hit
		step(TR, 29.09, "btb effects fadeout 0.05");
		step(TR, 29.09, "btb effects flash orange 0.6");
		step(TR, 29.09, "btb camera handheld 0.9 @a");
		cut(29.09, 48, 0, move("tr_06", new double[] {47, 2.2, 1.5, 52, 2, -4}, new double[] {49.5, 2.4, 1, 52, 2, -4}, 3));
		step(TR, 29.09, "btb destruction trigger lava_wand_1");
		cut(32.02, 60, 0, move("tr_07", new double[] {61, 2, -1.5, 56, 2, 4}, new double[] {59, 2.3, -1, 56, 2, 4}, 1.9));
		step(TR, 32.02, "btb destruction trigger lava_wand_2");
		step(TR, 30.6, "btb spawn piglin_brute 3 " + abs(44, 0, -8) + " 3");
		step(TR, 30.6, "btb spawn wither_skeleton 3 " + abs(38, 0, 8) + " 3");
		step(TR, 30.6, "btb spawn btb_cinematic:aschekriecher 5 " + abs(46, 0, 0) + " 3");
		cut(33.83, 41, 0, orbit(41, 2, 0, 11, 3, 2.3, 60));
		step(TR, 34.4, "btb destruction trigger bruecke_feuer_n");
		step(TR, 34.4, "btb destruction trigger bruecke_feuer_s");
		cut(36.01, 86, 0, orbit(86, 0, 0, 26, 9, 1.9, 25));
		step(TR, 37.3, "btb destruction trigger bruecke_einsturz");
		cut(37.83, 84, -10, move("tr_10", new double[] {80, 1, -12, 86, -2, 0}, new double[] {88, 2, -13, 92, -3, 0}, 1.9));
		step(TR, 38.4, "btb destruction trigger decke_gang");
		cut(39.66, 31, -2, orbit(31.5, 2, -2, 5, 0.6, 2.2, -25));
		step(TR, 38.0, "btb ghastattack 3 " + abs(150, 34, -70));
		cut(41.82, 135, 0, orbit(135, 3, 0, 30, 5, 2.9, 30));
		step(TR, 43.2, "execute rotated " + (int) (routeYaw() + 35) + " 0 run btb ghastcrash " + abs(138, 42, -58));
		cut(44.74, 140, -45, "btb camera orbit entity @e[type=btb_cinematic:crash_ghast,limit=1,sort=nearest] 16 3 2.4 -30");
		step(TR, 46.9, "btb ausbruch " + abs(166, -1, 9) + " 9 1.2");
		cut(47.11, 166, 9, orbit(166, 3, 9, 14, 4, 2.9, 40));
		step(TR, 49.4, "btb spawn btb_cinematic:glutling 7 " + abs(160, 4, 0) + " 5");
		cut(50.04, 165, 0, orbit(165, 2, 0, 12, 5, 2.4, -50));
		step(TR, 52.38, "btb camera handheld off @a");

		// ---- 52.38 second phrase: the fortress and its guardian
		step(TR, 52.38, "btb effects flash red 0.4");
		step(TR, 52.38, "btb effects lut glut 0.6 0.5");
		cut(52.38, TX - 60, -30, move("tr_12", new double[] {TX - 95, 34, -60, TX, 45, 0}, new double[] {TX - 75, 26, -42, TX, 42, 0}, 4.1));
		step(TR, 52.38, "btb effects flare " + abs(TX + 0.5, 110, 0.5) + " orange 0.8");
		step(TR, 55.6, "btb spawn btb_cinematic:herzwaechter 1 " + abs(TX, 1, -6) + " 0 still");
		step(TR, 56.4, "btb effects flare off");
		cut(56.4, TX, 6, "execute positioned " + abs(TX, 2.5, 8) + " run btb camera dollyzoom " + abs(TX, 2.5, -6) + " 4 12 2.9");
		step(TR, 56.4, "btb effects anamorph 0.7 0.5");
		step(TR, 58.2, "btb showcase play herz reveal");
		cut(59.28, TX, 3, orbit(TX + 0.5, 2, 0.5, 6, 2.5, 2.4, 60));
		step(TR, 59.4, "btb sound play heart_pulse 1 0.6");
		step(TR, 61.0, "btb showcase animation herz pulse");
		step(TR, 60.45, "btb waechter angriff schlag");
		step(TR, 61.65, "btb camera handheld 1.1 @a");
		cut(61.65, TX, 2, orbit(TX, 1.5, -6, 9, 1.2, 2.9, 40));
		step(TR, 63.7, "btb waechter angriff bruellen");
		cut(64.57, TX, 0, orbit(TX, 3.2, -6, 5, 3.4, 3.4, -30));
		step(TR, 64.9, "btb effects shake 0.8 1.5");
		step(TR, 66.6, "btb waechter angriff saeulen");
		cut(68.01, TX, 6, orbit(TX, 2, -6, 15, 7, 1.8, 30));
		step(TR, 68.4, "btb waechter angriff wurf");
		cut(69.85, TX + 6, -6, orbit(TX, 3, -6, 10, 2, 1.8, -40));
		// the guardian falls – the key bursts out of its heart
		cut(71.66, TX, 4, orbit(TX, 2, -6, 8, 2.5, 5.1, 50));
		step(TR, 71.66, "kill @e[type=btb_cinematic:herzwaechter]");
		step(TR, 71.66, "btb effects flash white 0.5");
		step(TR, 72.2, "btb camera handheld off @a");
		step(TR, 75.95, "btb effects anamorph off 0.5");

		// ---- 76.74 third phrase: the whole tower comes down
		step(TR, 75.9, "btb destruction trigger turm_einsturz");
		step(TR, 76.74, "btb effects flash orange 0.7");
		step(TR, 76.74, "btb camera handheld 1.2 @a");
		cut(76.74, TX - 60, 40, move("tr_17", new double[] {TX - 82, 22, 58, TX, 40, 0}, new double[] {TX - 78, 24, 52, TX, 36, 0}, 1.7));
		cut(78.37, TX - 36, 30, move("tr_18", new double[] {TX - 42, 32, 36, TX, 30, 0}, new double[] {TX - 37, 27, 31, TX, 22, 0}, 3.1));
		cut(81.48, TX, 0, orbit(TX, 30, 0, 22, 85, 1.9, 45));
		cut(83.29, 205, 20, move("tr_19", new double[] {200, 8, 30, TX, 30, 0}, new double[] {204, 12, 26, TX, 20, 0}, 2.9));
		step(TR, 86.19, "btb camera handheld off @a");
		// the gate swings open, the way is free
		cut(86.19, TX + 38, 0, move("tr_20", new double[] {TX + 40, 3, 4, TX + 28, 3, 0}, new double[] {TX + 34, 2.5, 1, TX + 22, 3, 0}, 2.2));
		step(TR, 86.19, "btb destruction trigger festungstor");
		step(TR, 87.8, "btb destruction trigger saeule_1");
		step(TR, 88.2, "btb destruction trigger saeule_2");
		cut(88.35, 307, 0, orbit(307, -2, 0, 16, 9, 1.7, 30));
		step(TR, 89.2, "btb spawn btb_cinematic:glutling 8 " + abs(304, 4, 0) + " 4");
		step(TR, 90.02, "btb camera handheld 0.8 @a");
		cut(90.02, 302, 0, move("tr_22", new double[] {296, 2, -1, 310, 3, 0}, new double[] {302, 2.5, 1, 318, 3, 0}, 2.4));
		step(TR, 92.39, "btb camera handheld off @a");
		cut(92.39, 352, 0, orbit(359, 6, 0, 14, 8, 1.8, 40));
		// the portal ignites
		step(TR, 94.2, "btb effects anamorph 0.9 0.5");
		cut(94.2, 359, 0, orbit(359, 2, 0, 7, 3, 2.9, 120));
		step(TR, 94.2, "btb effects shake 0.5 2.8");
		step(TR, 95.5, "btb sound play rumble 2 0.7");
		step(TR, 97.11, "btb portal activate film_tor_a");
		step(TR, 97.11, "btb effects flash white 0.9");
		step(TR, 97.11, "btb effects shake 1.4 1.5");
		cut(97.11, 364, 0, move("tr_25", new double[] {352, 3, 0, 367, 6, 0}, new double[] {360, 4, 0, 367, 7, 0}, 2.9));

		// ---- 100.0 finale (half time): through the portal – and still in the Nether
		step(TR, 98.8, "btb spawn piglin_brute 6 " + abs(425, 0, 0) + " 14 still");
		step(TR, 98.8, "btb spawn wither_skeleton 6 " + abs(430, 0, -12) + " 12 still");
		step(TR, 98.8, "btb spawn zombified_piglin 8 " + abs(423, 0, 14) + " 14 still");
		step(TR, 98.8, "btb spawn hoglin 3 " + abs(440, 0, 6) + " 8 still");
		step(TR, 100.01, "btb effects fade white 0 0.15");
		step(TR, 100.2, "btb effects fadeout 0.8");
		step(TR, 100.01, "btb effects anamorph off 1");
		step(TR, 100.01, "btb effects lut nether 0.7 1");
		cut(100.01, 415, 0, move("tr_26", new double[] {410, 3, -9, 405, 7, 0}, new double[] {432, 18, -32, 405, 6, 0}, 6.55));
		step(TR, 101.5, "btb ghastattack 2 " + abs(445, 30, -40));
		step(TR, 102.3, "btb spawn btb_cinematic:glutling 8 " + abs(430, 4, 0) + " 10");
		// montage: one image per hit
		cut(106.56, TX - 50, 35, move("tr_27", new double[] {TX - 60, 20, 45, TX, 5, 0}, new double[] {TX - 52, 16, 38, TX, 4, 0}, 2.2));
		cut(108.74, 54, 0, move("tr_28", new double[] {48, 2.5, 0, 56, 1.5, 4}, new double[] {52, 2.5, 0, 60, 1.5, 4}, 1.8));
		step(TR, 110.2, "btb ausbruch " + abs(150, -1, -9) + " 8 1.0");
		cut(110.55, 150, -9, orbit(150, 3, -9, 12, 4, 1.8, 35));
		cut(112.38, TX + 36, 0, move("tr_29", new double[] {TX + 38, 2.5, 3, TX + 20, 4, 0}, new double[] {TX + 33, 2.5, 1, TX + 15, 4, 0}, 1.7));
		cut(114.03, 362, 0, orbit(367, 6, 0, 8, 2, 1.6, 30));
		cut(115.66, 425, 0, orbit(425, 2, 0, 9, 2, 1.8, -35));
		step(TR, 117.47, "btb effects flash orange 0.4");
		cut(117.47, TX, 0, orbit(TX, 10, 0, 28, 30, 2.9, 25));
		// last hit: black, the title
		step(TR, 120.37, "btb effects fade black 0.08");
		step(TR, 120.37, "btb camera stop @a");
		step(TR, 120.8, "btb hud show trailer_ende");
		step(TR, 125.0, "btb hud hide");
		step(TR, 125.0, "btb camera handheld off @a");
		step(TR, 125.05, "btb effects clear 0.6"); // everything the trailer switched on ends with it (also the black)
		step(TR, 125.0, "btb statist clear");
		step(TR, 125.0, "btb effects format off 0.1");
		step(TR, 125.0, "btb effects grain off 0.1");
		step(TR, 125.0, "btb effects lut off 0.1");
		step(TR, 125.0, "btb ambience off herzturm");
		step(TR, 125.0, "btb portal activationscene film_tor_a film_06_portal");
		step(TR, 125.1, "tp @a " + abs(TX + 34.5, 1, 0.5));
	}

	private void path(String id, double[][] pts) {
		List<CameraPoint> points = new ArrayList<>();
		for (int i = 0; i < pts.length; i++) {
			double[] q = pts[i];
			Vec3 c = at(q[0], q[1], q[2]), l = at(q[3], q[4], q[5]);
			double x = c.x, y = c.y, z = c.z;
			CameraPose look = new CameraPose(x, y, z, 0, 0, 0, 0).lookingAt(l.x, l.y, l.z);
			points.add(new CameraPoint(x, y, z, look.yaw(), look.pitch(), 0, 0, (int) q[6], Easing.LINEAR, null, null));
		}
		engine.module(CameraManager.class).orElseThrow().putPreset(new CameraPath(id, points, CameraPath.Mode.SMOOTH, Easing.EASE_IN_OUT, 1.0));
	}

	private void cameras() {
		// 0:00 approach: far over the wasteland → over the bastion → down through the roof hole → the actors in the hall
		path("film_anflug", new double[][] {
				{-80, 48, -70, 33, 6, 0, 170},
				{-30, 30, -36, 30, 4, 0, 150},
				{4, 24, -10, 13, 2, 0, 120},
				{13, 22, 0.5, 15, 0, 0.5, 110},
				{12, 6, 0.5, 18, 1, 0.5, 90},
				{11, 3, 0.5, 19, 1.5, 0.5, 1}});
		// 3:06 reveal: over the ridge the glowing tower appears in the distance
		path("film_turm_reveal", new double[][] {
				{190, 3, 10, TX, 60, 0, 120},
				{194, 9, 6, TX, 70, 0, 120},
				{200, 16, 2, TX, 80, 0, 1}});
		// 4:10 exit: side view of the exit portal, then pull out wide over the enemies
		path("film_ausgang", new double[][] {
				{410, 3, -9, 405, 7, 0, 140},
				{417, 8, -18, 405, 7, 0, 120},
				{437, 22, -38, 405, 6, 0, 100},
				{450, 30, -48, 407, 4, 0, 1}});
	}

	// ------------------------------------------------------------------ scenes (storyboard timings)

	private void scenes() {
		String a = abs(0, 0, 0);
		if (has(0)) {
			// 0:00 – 0:46 arrival at the abandoned bastion
			scene("film_01_bastion", 46);
			step("film_01_bastion", 0, "btb effects fade black 0");
			step("film_01_bastion", 0, "btb effects format 2.39 0.1");
			step("film_01_bastion", 0, "btb effects grain 0.5 2");
			step("film_01_bastion", 0, "btb sound music nether_drone 4");
			step("film_01_bastion", 0, "btb camera play film_anflug @a[tag=kamera]");
			step("film_01_bastion", 0.3, "btb effects fadeout 3");
			step("film_01_bastion", 33, "btb camera stop @a[tag=kamera]");
			step("film_01_bastion", 33.5, "execute positioned " + abs(13, 0, 0) + " run btb camera auto @p[tag=!kamera] 5 @a[tag=kamera]");
			step("film_01_bastion", 45.5, "btb camera stop @a[tag=kamera]");
			// 0:47 – 1:32 the book (right-click on the lectern): just the page and two calm camera angles, no effects
			scene("film_02_buch", 20);
			step("film_02_buch", 0.3, "btb hud show book_heart");
			step("film_02_buch", 0, "btb effects dof 2 0.6 1");
			step("film_02_buch", 14, "btb effects dof off 2");
			// the heartbeat motif starts here: barely audible while the text appears
			for (double t : new double[] {1.5, 3.5, 5.5, 7.5}) {
				step("film_02_buch", t, "btb sound play heart_pulse 0.35 0.6");
			}
			step("film_02_buch", 0, "execute positioned " + abs(28, 0, 8) + " run btb camera follow @p[tag=!kamera] front 3 0 " + dir("+z") + " @a[tag=kamera]");
			step("film_02_buch", 6, "execute positioned " + abs(28, 0, 8) + " run btb camera follow @p[tag=!kamera] shoulder 3 1 " + dir("+z") + " @a[tag=kamera]");
			step("film_02_buch", 15, "btb camera stop @a[tag=kamera]");
			// 1:33 – 2:18 the courtyard: enemies appear, the actors flee through the corridor – no screen effects
			scene("film_03_ausbruch", 45);
			step("film_03_ausbruch", 1, "execute positioned " + abs(41, 0, 0) + " run btb camera auto @p[tag=!kamera] 4 @a[tag=kamera]");
			step("film_03_ausbruch", 0, "btb camera handheld 1 @a[tag=kamera]");
			step("film_03_ausbruch", 44, "btb camera handheld off @a[tag=kamera]");
			step("film_03_ausbruch", 44, "btb camera stop @a[tag=kamera]");
			step("film_03_ausbruch", 0.3, "btb spawn piglin_brute 3 " + abs(44, 0, -9) + " 3");
			step("film_03_ausbruch", 0.3, "btb spawn wither_skeleton 3 " + abs(38, 0, 9) + " 3");
			step("film_03_ausbruch", 6, "btb spawn piglin_brute 2 " + abs(44, 0, 10) + " 3");
			step("film_03_ausbruch", 12, "btb spawn wither_skeleton 4 " + abs(36, 0, -10) + " 3");
			step("film_03_ausbruch", 4, "btb spawn btb_cinematic:aschekriecher 5 " + abs(52, 0, 0) + " 3");
		}
		if (has(2)) {
			// 2:19 – 3:05 lava desert, ghasts, jump'n'run
			scene("film_04_wueste", 46);
			step("film_04_wueste", 0, "btb ambience on wueste");
			step("film_04_wueste", 0, "btb sound reverb nether 0.6");
			step("film_04_wueste", 0, "btb parkour start felsen");
			step("film_04_wueste", 0, "btb ambience on herzschlag");
			step("film_04_wueste", 0, "btb camera handheld 0.5 @a[tag=kamera]");
			step("film_04_wueste", 45, "btb camera handheld off @a[tag=kamera]");
			step("film_04_wueste", 0.5, "execute positioned " + abs(125, 0, 0) + " run btb camera auto @p[tag=!kamera] 6 @a[tag=kamera]");
			step("film_04_wueste", 45, "btb camera stop @a[tag=kamera]");
			step("film_04_wueste", 9, "btb ghastattack 3 " + abs(150, 34, -70));
			step("film_04_wueste", 0, "btb wind " + (int) routeYaw() + " 0.5");
			step("film_04_wueste", 45, "btb wind off");
			// one of the ghasts is hit and comes down burning; later the lava river erupts next to the stones
			step("film_04_wueste", 18, "execute rotated " + (int) (routeYaw() + 35) + " 0 run btb ghastcrash " + abs(138, 42, -58));
			step("film_04_wueste", 30, "btb ausbruch " + abs(166, -1, 9) + " 9 1.2");
			step("film_04_wueste", 28, "btb ghastattack 2 " + abs(176, 30, 64));
		}
		if (has(3)) {
			// 3:06 the glowing building on the horizon (the heart itself is not visible yet)
			scene("film_05_festung", 30);
			if (has(2)) {
				step("film_05_festung", 0, "btb camera play film_turm_reveal @a[tag=kamera]");
				step("film_05_festung", 12, "btb camera stop @a[tag=kamera]");
				step("film_05_festung", 12.5, "execute positioned " + abs(200, 0, 0) + " run btb camera auto @p[tag=!kamera] 5 @a[tag=kamera]");
			} else {
				step("film_05_festung", 0, "btb camera orbit " + abs(TX, 40, 0) + " 70 10 12 50 @a[tag=kamera]");
				step("film_05_festung", 12, "btb camera stop @a[tag=kamera]");
				step("film_05_festung", 12.5, "execute positioned " + abs(TX - 34, 0, 0) + " run btb camera auto @p[tag=!kamera] 5 @a[tag=kamera]");
			}
			step("film_05_festung", 0, "btb hud show heart_message_ethereal");
			step("film_05_festung", 0, "btb effects focus 3 6");
			// the message returns like a memory
			step("film_05_festung", 0, "btb effects flashback 0.7 1.5");
			step("film_05_festung", 10, "btb effects flashback off 2");
			step("film_05_festung", 0, "btb effects flare " + abs(TX + 0.5, 110, 0.5) + " orange 0.9");
			step("film_05_festung", 0, "btb ambience on herzturm");
			step("film_05_festung", 16, "btb effects flare off");
			step("film_05_festung", 29.5, "btb camera stop @a[tag=kamera]");
			// at the tower gate: HEART REVEAL – the camera circles the altar, the heart rises and starts beating,
			// then the Herzwächter wakes up behind it (he drops the key)
			scene("film_05_herz", 40);
			step("film_05_herz", 0, "btb effects letterbox 0.12 1");
			step("film_05_herz", 0, "btb sound reverb cathedral 0.6");
			// silence before the moment, then the heart: dolly zoom from the gate, the world stretches behind it
			step("film_05_herz", 0, "btb sound volume 0.1");
			step("film_05_herz", 0, "btb effects dof auto 0.7 1");
			step("film_05_herz", 0, "btb effects anamorph 0.6 2");
			step("film_05_herz", 39, "btb effects dof off 2");
			step("film_05_herz", 39, "btb effects anamorph off 2");
			step("film_05_herz", 0, "execute positioned " + abs(TX + 0.5, 2, 14) + " run btb camera dollyzoom " + abs(TX + 0.5, 2.5, 0.5)
					+ " 4 12 4 @a[tag=kamera]");
			step("film_05_herz", 4, "btb camera orbit " + abs(TX + 0.5, 2, 0.5) + " 6 2.5 6 120 @a[tag=kamera]");
			step("film_05_herz", 1.2, "btb sound volume 1");
			step("film_05_herz", 1.2, "btb sound play heart_pulse 3 0.6");
			step("film_05_herz", 0.5, "btb showcase play herz reveal");
			step("film_05_herz", 3.5, "btb effects flare " + abs(TX + 0.5, 3, 0.5) + " red 0.6");
			step("film_05_herz", 6, "btb showcase animation herz pulse");
			step("film_05_herz", 9.5, "btb effects flare off");
			step("film_05_herz", 10, "btb camera stop @a[tag=kamera]");
			step("film_05_herz", 10, "btb sound play impact 2 0.6");
			step("film_05_herz", 10, "btb effects shake 0.8 1.5");
			step("film_05_herz", 10, "btb spawn btb_cinematic:herzwaechter 1 " + abs(TX, 1, -6) + " 0");
			step("film_05_herz", 10.5, "btb spawn wither_skeleton 3 " + abs(TX, 1, -2) + " 5");
			step("film_05_herz", 12, "btb slowmo 0.45 1.8");
			step("film_05_herz", 10.5, "btb camera handheld 0.8 @a[tag=kamera]");
			step("film_05_herz", 39, "btb camera handheld off @a[tag=kamera]");
			step("film_05_herz", 10.5, "execute positioned " + abs(TX, 0, 8) + " run btb camera auto @p[tag=!kamera] 4 @a[tag=kamera]");
			step("film_05_herz", 39, "btb camera stop @a[tag=kamera]");
			step("film_05_herz", 39, "btb effects letterbox off 1");
			// the whole tower collapses (whenever you want it: Director → Szenen, or a cue)
			scene("film_turm_einsturz", 32);
			step("film_turm_einsturz", 0, "btb effects letterbox 0.12 1");
			step("film_turm_einsturz", 0, "btb camera orbit " + abs(TX, 18, 0) + " 78 26 30 75 @a[tag=kamera]");
			step("film_turm_einsturz", 0, "btb sound play rumble 2 0.6");
			step("film_turm_einsturz", 0, "btb effects shake 0.3 3");
			step("film_turm_einsturz", 2.5, "btb destruction trigger turm_einsturz");
			step("film_turm_einsturz", 2.5, "btb sound play impact 2 0.7");
			step("film_turm_einsturz", 2.5, "btb effects flash orange 0.4");
			step("film_turm_einsturz", 0, "btb camera handheld 1.2 @a[tag=kamera]");
			step("film_turm_einsturz", 3.5, "btb effects muffle 7 0.85");
			step("film_turm_einsturz", 3.5, "btb sound play ear_ring 0.7 1");
			step("film_turm_einsturz", 4, "btb slowmo 0.4 2.5");
			step("film_turm_einsturz", 30, "btb camera handheld off @a[tag=kamera]");
			step("film_turm_einsturz", 7, "btb effects shake 0.9 2");
			step("film_turm_einsturz", 12, "btb effects shake 0.7 2");
			step("film_turm_einsturz", 16, "btb ambience off herzturm");
			step("film_turm_einsturz", 30, "btb camera stop @a[tag=kamera]");
			step("film_turm_einsturz", 30, "btb effects letterbox off 2");
		}
		if (has(5)) {
			// 3:53 – 4:09 portal activation (runs automatically when the key is inserted)
			scene("film_06_portal", 12);
			step("film_06_portal", 0, "btb effects letterbox 0.12 0.5");
			step("film_06_portal", 0, "btb effects anamorph 0.9 1");
			step("film_06_portal", 0, "btb camera orbit " + abs(359, 2, 0) + " 7 3 9 360 @a[tag=kamera]");
			step("film_06_portal", 0, "btb effects shake 0.4 6");
			step("film_06_portal", 3, "btb sound play rumble 2 0.7");
			step("film_06_portal", 5, "btb effects shake 1.6 3");
			step("film_06_portal", 6, "btb effects flash white 0.8");
			// the music falls away, a single white tone remains
			step("film_06_portal", 0, "btb sound music stop 4");
			step("film_06_portal", 5.5, "btb sound play white_tone 1.5 1");
			step("film_06_portal", 9.5, "btb camera stop @a[tag=kamera]");
		}
		if (has(6)) {
			// 4:10 – end: out of the other portal, still in the Nether, enemies everywhere, fade to black
			scene("film_07_ende", 26);
			step("film_07_ende", 0, "btb camera play film_ausgang @a[tag=kamera]");
			step("film_07_ende", 0, "btb spawn piglin_brute 6 " + abs(425, 0, 0) + " 14 still");
			step("film_07_ende", 0, "btb spawn wither_skeleton 6 " + abs(430, 0, -12) + " 12 still");
			step("film_07_ende", 0, "btb spawn zombified_piglin 8 " + abs(423, 0, 14) + " 14 still");
			step("film_07_ende", 0, "btb spawn hoglin 3 " + abs(440, 0, 6) + " 8 still");
			step("film_07_ende", 0, "btb ghastattack 2 " + abs(445, 30, -40));
			step("film_07_ende", 2, "btb spawn btb_cinematic:glutling 8 " + abs(430, 4, 0) + " 10");
			step("film_07_ende", 1, "btb sound music nether_drone 2");
			step("film_07_ende", 16, "btb effects fade black 3");
			step("film_07_ende", 0, "btb camera handheld 0.6 @a[tag=kamera]");
			step("film_07_ende", 19, "btb camera handheld off @a[tag=kamera]");
		}
		if (!full()) {
			return;
		}
		// preview: a tour of all sections with the storyboard timestamps. The viewer is teleported to each
		// section (Minecraft only loads the world around the player, not around a far-away camera).
		scene("film_vorschau", 116);
		step("film_vorschau", 0, "btb effects letterbox 0.12 0.5");
		Object[][] tour = {
				{13, 0, 0, 33, 4, 0, 40, 26, 90, "0:00 Bastion – Ankunft, alles wirkt verlassen"},
				{28, 0, 7, 28, 2, 10, 5, 2, 180, "0:47 Das Buch: If you want to leave this place …"},
				{41, 0, 0, 41, 2, 0, 15, 10, 120, "1:33 Der Beat setzt ein – Gegner im Innenhof"},
				{86, 0, 0, 86, 0, 0, 30, 18, 90, "Flucht über die brennenden Brücken"},
				{130, 0, 0, 135, 3, 0, 35, 20, 90, "2:19 Lavawüste, Seelensand, Ghasts"},
				{150, 0, 0, 165, 2, 0, 18, 10, 120, "Jump'n'Run über den Lavafluss"},
				{198, 0, 0, TX, 30, 0, 70, 40, 70, "3:06 Das leuchtende Gebäude – die Festung"},
				{TX, 0, 22, TX, 50, 0, 42, 62, 90, "Der Turm des Herzens – Boss und Schlüssel"},
				{298, 0, 0, 307, -2, 0, 16, 9, 140, "Durch die Lavaschlucht zur Portalhalle"},
				{345, 0, 0, 359, 6, 0, 14, 8, 160, "3:53 Portalhalle – Schlüssel einsetzen"},
				{417, 0, 0, 405, 6, 0, 26, 12, 100, "4:10 Ausgang – immer noch im Nether"}};
		for (int i = 0; i < tour.length; i++) {
			Object[] t = tour[i];
			double sec = i * 10;
			step("film_vorschau", sec, "tp @a " + abs(((Number) t[0]).doubleValue() + 0.5, ((Number) t[1]).doubleValue(), ((Number) t[2]).doubleValue() + 0.5));
			step("film_vorschau", sec + 1.5, "btb camera orbit " + abs(((Number) t[3]).doubleValue(), ((Number) t[4]).doubleValue(),
					((Number) t[5]).doubleValue()) + " " + t[6] + " " + t[7] + " 8.5 " + t[8] + " @a");
			step("film_vorschau", sec + 0.5, "btb hud text untertitel 9 " + t[9]);
		}
		step("film_vorschau", 110, "btb camera stop @a");
		trailer();
		step("film_vorschau", 110, "tp @a " + abs(13.5, 0, 0.5));
		step("film_vorschau", 110, "btb effects letterbox off 1");
		if (a.isEmpty()) {
			LOG.debug("origin {}", a);
		}
	}
}
