package de.behindtheblocks.cinematic.portal;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import de.behindtheblocks.cinematic.BtbCinematic;
import de.behindtheblocks.cinematic.camera.CameraManager;
import de.behindtheblocks.cinematic.command.CommandContributor;
import de.behindtheblocks.cinematic.command.Feedback;
import de.behindtheblocks.cinematic.content.BtbBlocks;
import de.behindtheblocks.cinematic.content.KeystoneStage;
import de.behindtheblocks.cinematic.content.PortalFrameBlock;
import de.behindtheblocks.cinematic.content.PortalKeystoneBlock;
import de.behindtheblocks.cinematic.content.RiftPortalBlock;
import de.behindtheblocks.cinematic.core.CinematicEngine;
import de.behindtheblocks.cinematic.core.CinematicModule;
import de.behindtheblocks.cinematic.core.bus.SceneMessages;
import de.behindtheblocks.cinematic.effects.EffectManager;
import de.behindtheblocks.cinematic.effects.EffectSpec;
import de.behindtheblocks.cinematic.event.builtin.BuiltinEvents;
import de.behindtheblocks.cinematic.event.builtin.PlayerTargets;
import de.behindtheblocks.cinematic.json.JsonParamException;
import de.behindtheblocks.cinematic.scene.SceneLoader;
import de.behindtheblocks.cinematic.sound.BtbSounds;
import de.behindtheblocks.cinematic.sound.SoundManager;
import de.behindtheblocks.cinematic.timeline.TimelineManager;
import de.behindtheblocks.cinematic.util.Colors;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
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
import java.util.LinkedHashMap;
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
 * Portal Reality: cinematic portals with a key check, a staged activation sequence and a seamless
 * transfer between two portals.
 *
 * <h2>Seamless transfer</h2>
 * A player walking into an ENTERABLE portal gets a fade to the transition color. Only when the
 * screen is completely covered is the player moved, out of the linked portal's surface and facing
 * away from it, with a gentle forward push so they appear to step out. The destination portal is
 * opened silently at that moment and its chunks are kept loaded while the source portal is open,
 * so nothing needs to stream in. The fade then holds briefly and opens up on the new scene
 * ({@code arrival_scene}). Cross-dimension transfers also hide Minecraft's loading screen behind
 * the fade (client mixin).
 */
public final class PortalManager implements CinematicModule, CommandContributor {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/portal");
	private static final int KEY_DETECTED_TICKS = 30;
	private static final int OPEN_SPREAD_MIN = 12;
	private static final int OPEN_SPREAD_MAX = 60;
	private static final int ENTER_COOLDOWN = 60;

	private final Map<String, JsonObject> definitions = new TreeMap<>();
	private final Map<String, Runtime> runtimes = new HashMap<>();
	private final Map<UUID, Integer> cooldown = new HashMap<>();
	private CinematicEngine engine;
	private MinecraftServer server;

	/** Live state of one portal. */
	private static final class Runtime {
		final PortalDefinition def;
		final ServerLevel level;
		PortalState state = PortalState.INACTIVE;
		String sceneId = TimelineManager.MANUAL;
		int ticksInState;
		final Map<BlockPos, BlockState> replaced = new LinkedHashMap<>();
		final List<BlockPos> frame = new ArrayList<>();
		/** Surface cells sorted by distance from the centre (precomputed once; large portals open outward). */
		List<BlockPos> openOrder;
		/** Ticks the surface needs to open: grows with portal size so huge gates open majestically. */
		final int spreadTicks;

		Runtime(PortalDefinition def, ServerLevel level) {
			this.def = def;
			this.level = level;
			this.spreadTicks = (int) Math.max(OPEN_SPREAD_MIN, Math.min(OPEN_SPREAD_MAX, def.radius() * 2.5));
		}

		List<BlockPos> openOrder() {
			if (openOrder == null) {
				Vec3 c = new Vec3(def.centerX(), def.centerY(), def.centerZ());
				List<BlockPos> cells = new ArrayList<>();
				cells.addAll(def.cells());
				cells.sort(Comparator.comparingDouble(p -> Vec3.atCenterOf(p).distanceToSqr(c)));
				openOrder = cells;
			}
			return openOrder;
		}
	}

	@Override
	public String id() {
		return "portal";
	}

	public static Path directory() {
		return FabricLoader.getInstance().getConfigDir().resolve(BtbCinematic.MOD_ID).resolve("portals");
	}

	@Override
	public void initialize(CinematicEngine engine) {
		this.engine = engine;
		engine.bus().subscribe(SceneMessages.Reset.class, m -> runtimes.values().stream()
				.filter(r -> r.sceneId.equals(m.sceneId())).map(r -> r.def.id()).toList().forEach(this::deactivate));
		engine.events().register(BuiltinEvents.simple("portal_activate",
				"Runs a portal's activation sequence (skips the key). {id, instant?}",
				p -> {
					String id = p.requireString("id");
					boolean instant = p.getBoolean("instant", false);
					return ctx -> {
						String err = activate(id, instant, ctx.sceneId());
						if (err != null) {
							throw new IllegalStateException(err);
						}
					};
				}));
		engine.events().register(BuiltinEvents.simple("portal_deactivate",
				"Closes a portal and restores its surface. {id? (omit = all)}",
				p -> {
					String id = p.has("id") ? p.requireString("id") : null;
					return ctx -> {
						if (id == null) {
							List.copyOf(runtimes.keySet()).forEach(this::deactivate);
						} else {
							deactivate(id);
						}
					};
				}));
		engine.events().register(BuiltinEvents.simple("portal_transfer",
				"Transfers players through a portal without walking in (scripted cut). {id, players?}",
				p -> {
					String id = p.requireString("id");
					PlayerTargets t = PlayerTargets.parse(p);
					return ctx -> {
						Runtime r = runtime(id);
						if (r == null) {
							throw new IllegalStateException("unknown portal '" + id + "'");
						}
						for (ServerPlayer player : t.resolve(ctx.server())) {
							startTransfer(r, player, ctx.sceneId());
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
		List.copyOf(runtimes.keySet()).forEach(this::deactivate);
		runtimes.clear();
		this.server = null;
	}

	// ------------------------------------------------------------------ definitions

	public int reload() {
		definitions.clear();
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
						PortalDefinition.parse(id, json);
						definitions.put(id, json);
					} catch (RuntimeException e) {
						LOG.warn("Portal {} invalid: {}", name, e.getMessage());
					}
				}
			}
		} catch (IOException e) {
			LOG.error("Cannot read portal directory", e);
		}
		LOG.info("Loaded {} portal(s)", definitions.size());
		return definitions.size();
	}

	private void save(String id) {
		try {
			Files.createDirectories(directory());
			Files.writeString(directory().resolve(id + ".json"),
					new GsonBuilder().setPrettyPrinting().create().toJson(definitions.get(id)), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOG.error("Could not save portal {}", id, e);
		}
	}

	/** Returns (creating if needed) the runtime for a portal, or null if unknown/invalid. */
	private Runtime runtime(String id) {
		Runtime r = runtimes.get(id);
		if (r != null) {
			return r;
		}
		JsonObject json = definitions.get(id);
		if (json == null || server == null) {
			return null;
		}
		PortalDefinition def;
		try {
			def = PortalDefinition.parse(id, json);
		} catch (JsonParamException e) {
			LOG.warn("Portal {} invalid: {}", id, e.getMessage());
			return null;
		}
		ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, def.dimension()));
		if (level == null) {
			return null;
		}
		r = new Runtime(def, level);
		collectFrame(r);
		runtimes.put(id, r);
		return r;
	}

	/**
	 * Frame = portal frame blocks (both styles) that touch the surface within its plane (incl.
	 * diagonals), ordered bottom → top for the ignite effect. Works for any surface shape.
	 */
	private static void collectFrame(Runtime r) {
		PortalDefinition d = r.def;
		java.util.Set<BlockPos> found = new java.util.LinkedHashSet<>();
		for (BlockPos c : d.cells()) {
			for (int u = -1; u <= 1; u++) {
				for (int v = -1; v <= 1; v++) {
					BlockPos n = switch (d.axis()) {
						case X -> c.offset(u, v, 0);
						case Z -> c.offset(0, v, u);
						case Y -> c.offset(u, 0, v);
					};
					if (!d.contains(n) && isFrame(r.level.getBlockState(n))) {
						found.add(n.immutable());
					}
				}
			}
		}
		r.frame.addAll(found);
		double cx = d.centerX(), cz = d.centerZ();
		r.frame.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY)
				.thenComparingDouble(p -> -Math.abs(p.getX() + 0.5 - cx) - Math.abs(p.getZ() + 0.5 - cz)));
	}

	private static boolean isFrame(BlockState s) {
		return s.getBlock() instanceof PortalFrameBlock;
	}

	/**
	 * Flood-fills the open area enclosed by a frame, starting at {@code seed}. Tries the three
	 * planes through the seed and returns the smallest closed one (any shape), or null.
	 */
	static java.util.Map.Entry<Direction.Axis, java.util.List<BlockPos>> floodFill(ServerLevel level, BlockPos seed) {
		java.util.Map.Entry<Direction.Axis, java.util.List<BlockPos>> best = null;
		for (Direction.Axis axis : Direction.Axis.values()) {
			java.util.List<BlockPos> cells = new ArrayList<>();
			java.util.Set<BlockPos> seen = new java.util.HashSet<>();
			java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
			if (!open(level.getBlockState(seed))) {
				return null;
			}
			queue.add(seed);
			seen.add(seed);
			boolean closed = true;
			while (!queue.isEmpty()) {
				BlockPos p = queue.poll();
				cells.add(p);
				if (cells.size() > PortalDefinition.MAX_SURFACE || p.distManhattan(seed) > 192) {
					closed = false;
					break;
				}
				BlockPos[] next = switch (axis) {
					case X -> new BlockPos[] {p.east(), p.west(), p.above(), p.below()};
					case Z -> new BlockPos[] {p.north(), p.south(), p.above(), p.below()};
					case Y -> new BlockPos[] {p.east(), p.west(), p.north(), p.south()};
				};
				for (BlockPos n : next) {
					if (seen.add(n) && open(level.getBlockState(n))) {
						queue.add(n);
					}
				}
			}
			if (closed && (best == null || cells.size() < best.getValue().size())) {
				best = java.util.Map.entry(axis, cells);
			}
		}
		return best;
	}

	private static boolean open(BlockState s) {
		return (s.isAir() || s.canBeReplaced()) && !s.is(BtbBlocks.RIFT_PORTAL) && s.getFluidState().isEmpty();
	}

	public java.util.Collection<String> ids() {
		return definitions.keySet();
	}

	public PortalState state(String id) {
		Runtime r = runtimes.get(id);
		return r == null ? PortalState.INACTIVE : r.state;
	}

	// ------------------------------------------------------------------ activation

	/** Key used on a keystone: start the owning portal if the key requirement is met. */
	public void onKeyUsed(ServerLevel level, BlockPos pos, Player player) {
		for (String id : definitions.keySet()) {
			Runtime r = runtime(id);
			if (r == null || r.level != level || r.def.keystone() == null || !r.def.keystone().equals(pos)) {
				continue;
			}
			if (r.state != PortalState.INACTIVE) {
				player.sendSystemMessage(Component.literal("The portal is already awake.").withStyle(ChatFormatting.GRAY));
				return;
			}
			LOG.info("Nether Key accepted by portal '{}' ({})", id, player.getName().getString());
			begin(r, false, TimelineManager.MANUAL);
			return;
		}
		player.sendSystemMessage(Component.literal("This keystone belongs to no portal.").withStyle(ChatFormatting.GRAY));
	}

	/** @return null on success, otherwise an error message */
	public String activate(String id, boolean instant, String sceneId) {
		Runtime r = runtime(id);
		if (r == null) {
			return "Unknown or invalid portal '" + id + "'";
		}
		if (r.state != PortalState.INACTIVE && !instant) {
			return "Portal '" + id + "' is already " + r.state;
		}
		begin(r, instant, sceneId);
		return null;
	}

	private void begin(Runtime r, boolean instant, String sceneId) {
		r.sceneId = sceneId;
		if (instant) {
			setState(r, PortalState.ACTIVE);
			r.frame.forEach(p -> setFrameLit(r, p, true));
			setKeystone(r, KeystoneStage.ACTIVE);
			openSurface(r, true);
			setState(r, PortalState.ENTERABLE);
			return;
		}
		setState(r, PortalState.KEY_DETECTED);
		setKeystone(r, KeystoneStage.KEYED);
		Vec3 ks = r.def.keystone() != null ? Vec3.atCenterOf(r.def.keystone()) : center(r);
		sound().play(sceneId, 0, BtbSounds.KEY_INSERT.location(), ks, 1.4f, 1f);
		r.level.sendParticles(ParticleTypes.ENCHANT, ks.x, ks.y + 0.6, ks.z, 30, 0.3, 0.3, 0.3, 0.6);
		if (!r.def.activationScene().isEmpty()) {
			engine.scenes().restart(r.def.activationScene());
		}
	}

	private void setState(Runtime r, PortalState s) {
		r.state = s;
		r.ticksInState = 0;
		LOG.debug("Portal {} → {}", r.def.id(), s);
	}

	@Override
	public void tick(MinecraftServer server) {
		cooldown.replaceAll((k, v) -> v - 1);
		cooldown.values().removeIf(v -> v <= 0);
		for (Runtime r : runtimes.values()) {
			if (r.state == PortalState.INACTIVE) {
				continue;
			}
			r.ticksInState++;
			switch (r.state) {
				case KEY_DETECTED -> {
					if (r.ticksInState >= KEY_DETECTED_TICKS) {
						setState(r, PortalState.CHARGING);
						setKeystone(r, KeystoneStage.CHARGING);
						sound().play(r.sceneId, 0, BtbSounds.PORTAL_CHARGE.location(), center(r), 2f, 1f);
						sound().play(r.sceneId, 0, BtbSounds.RUMBLE.location(), center(r), 1f, 0.8f);
					}
				}
				case CHARGING -> tickCharging(r);
				case ACTIVE -> {
					if (r.ticksInState <= r.spreadTicks) {
						openSurfaceStep(r, r.ticksInState);
					}
					if (r.ticksInState >= Math.max(r.spreadTicks, r.def.settleTicks())) {
						setState(r, PortalState.ENTERABLE);
					}
					ambient(r);
				}
				case ENTERABLE -> {
					ambient(r);
					keepDestinationLoaded(r);
				}
				default -> { }
			}
		}
	}

	private void tickCharging(Runtime r) {
		int n = r.frame.size();
		int charge = Math.max(1, r.def.chargeTicks());
		int lit = Math.min(n, (int) Math.ceil(n * Math.min(1.0, (r.ticksInState + 1) / (double) charge)));
		for (int i = 0; i < lit; i++) {
			BlockPos p = r.frame.get(i);
			if (!r.level.getBlockState(p).getValue(PortalFrameBlock.LIT)) {
				setFrameLit(r, p, true);
				r.level.sendParticles(white(r) ? ParticleTypes.END_ROD : ParticleTypes.FLAME, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5,
						6, 0.3, 0.3, 0.3, 0.01);
			}
		}
		// Energy spirals into the surface centre, getting denser as it charges.
		Vec3 c = center(r);
		double prog = r.ticksInState / (double) charge;
		int streams = 1 + (int) (prog * Math.min(16, 4 + r.def.radius()));
		for (int i = 0; i < streams; i++) {
			double a = r.ticksInState * 0.35 + i * 2.1;
			double rad = Math.max(2.4, r.def.radius() * 0.9) * (1 - prog * 0.6);
			double ox = r.def.axis() != Direction.Axis.Z ? Math.cos(a) * rad : 0;
			double oz = r.def.axis() != Direction.Axis.X ? (r.def.axis() == Direction.Axis.Y ? Math.sin(a) : Math.cos(a)) * rad : 0;
			double oy = r.def.axis() == Direction.Axis.Y ? 0.3 : Math.sin(a) * rad;
			r.level.sendParticles(white(r) ? ParticleTypes.END_ROD : ParticleTypes.REVERSE_PORTAL, c.x + ox, c.y + oy, c.z + oz, 0,
					-ox * 0.08, -oy * 0.08, -oz * 0.08, 1);
		}
		if (r.ticksInState % 30 == 0) {
			camera().ifPresent(cam -> cam.shake(r.sceneId, 0, (float) (0.15 + prog * 0.5), 30, 0.8f, nearby(r, 40 + r.def.radius() * 2)));
		}
		if (r.ticksInState >= charge) {
			setState(r, PortalState.ACTIVE);
			setKeystone(r, KeystoneStage.ACTIVE);
			sound().play(r.sceneId, 0, BtbSounds.PORTAL_OPEN.location(), c, 3f, white(r) ? 1.25f : 1f);
			if (white(r)) {
				sound().play(r.sceneId, 0, BtbSounds.CRYSTAL_SHIMMER.location(), c, 3f, 1f);
				sound().play(r.sceneId, 0, BtbSounds.HEART_REVEAL.location(), c, 2f, 1.3f);
			}
			effects().ifPresent(fx -> fx.send(r.sceneId, 0, new EffectSpec(EffectSpec.Kind.FLASH, 0xFFFFFFFF, 0xFFFFFFFF,
					white(r) ? 1f : 0.85f, 1, white(r) ? 6 : 2, white(r) ? 40 : 18, ""), nearby(r, 48 + r.def.radius() * 2)));
			camera().ifPresent(cam -> cam.shake(r.sceneId, 0, 1.2f, 25, 1.2f, nearby(r, 48 + r.def.radius() * 2)));
			r.level.sendParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 3, 0.6, 0.6, 0.6, 0);
			if (white(r)) {
				r.level.sendParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 160, 0.5, 0.8, 0.5, 0.35);
				r.level.sendParticles(ParticleTypes.FIREWORK, c.x, c.y, c.z, 80, 0.6, 0.9, 0.6, 0.25);
			} else {
				r.level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, c.x, c.y, c.z, 80, 0.4, 0.8, 0.4, 0.25);
			}
		}
	}

	private static boolean white(Runtime r) {
		return r.def.style() == de.behindtheblocks.cinematic.content.PortalStyle.WHITE;
	}

	/** White portals send shafts of light up from their frame for a while after opening ("god rays"). */
	private void lightShafts(Runtime r) {
		if (!white(r) || r.ticksInState > 160 || r.ticksInState % 2 != 0 || r.frame.isEmpty()) {
			return;
		}
		for (int i = 0; i < 4; i++) {
			BlockPos f = r.frame.get((r.ticksInState * 7 + i * 13) % r.frame.size());
			r.level.sendParticles(ParticleTypes.END_ROD, f.getX() + 0.5, f.getY() + 1.0, f.getZ() + 0.5, 0, 0, 0.9, 0, 1);
		}
	}

	/** Fills the surface from the centre outwards; each tick only places the next ring of cells. */
	private void openSurfaceStep(Runtime r, int step) {
		List<BlockPos> order = r.openOrder();
		int from = (int) ((long) order.size() * (step - 1) / r.spreadTicks);
		int to = (int) Math.min(order.size(), (long) order.size() * step / r.spreadTicks);
		for (int i = Math.max(0, from); i < to; i++) {
			placeRift(r, order.get(i));
		}
	}

	private void openSurface(Runtime r, boolean silent) {
		for (BlockPos p : r.def.cells()) {
			placeRift(r, p);
		}
		if (!silent) {
			sound().play(r.sceneId, 0, BtbSounds.PORTAL_OPEN.location(), center(r), 3f, 1f);
		}
	}

	private void placeRift(Runtime r, BlockPos p) {
		BlockState current = r.level.getBlockState(p);
		if (current.is(BtbBlocks.RIFT_PORTAL)) {
			return;
		}
		if (!current.isAir() && !current.canBeReplaced()) {
			return; // never destroy builds: occupied cells stay as they are
		}
		r.replaced.putIfAbsent(p, current);
		r.level.setBlock(p, BtbBlocks.RIFT_PORTAL.defaultBlockState().setValue(RiftPortalBlock.AXIS, r.def.axis())
				.setValue(RiftPortalBlock.STYLE, r.def.style()), 2 | 16);
	}

	private void ambient(Runtime r) {
		if (r.ticksInState % 3 != 0) {
			return;
		}
		Vec3 c = center(r);
		double w = (r.def.max().getX() - r.def.min().getX() + 1) / 2.0, h = (r.def.max().getY() - r.def.min().getY() + 1) / 2.0,
				d = (r.def.max().getZ() - r.def.min().getZ() + 1) / 2.0;
		int count = (int) Math.min(48, 4 + r.def.surfaceBlocks() / 24);
		lightShafts(r);
		if (white(r)) {
			r.level.sendParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, Math.max(2, count / 3), w * 0.8, h * 0.8, d * 0.8, 0.02);
			r.level.sendParticles(ParticleTypes.WHITE_ASH, c.x, c.y, c.z, count, w, h, d, 0.02);
			return;
		}
		r.level.sendParticles(ParticleTypes.PORTAL, c.x, c.y, c.z, count, w * 0.8, h * 0.8, d * 0.8, 0.3);
		if (r.ticksInState % 12 == 0) {
			r.level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, c.x, r.def.min().getY() + 0.1, c.z, 3, w * 0.6, 0.05, d * 0.6, 0.01);
		}
	}

	/** Keeps the destination chunks loaded while this portal can be entered (portal tickets expire on their own). */
	private void keepDestinationLoaded(Runtime r) {
		if (r.ticksInState % 200 != 1) {
			return;
		}
		Destination dest = destination(r);
		if (dest != null) {
			dest.level.getChunkSource().addTicketWithRadius(TicketType.PORTAL, ChunkPos.containing(BlockPos.containing(dest.pos)),
					chunkRadius(dest));
		}
	}

	/** Chunk radius to keep loaded around a destination: bigger exit portals need more surroundings. */
	private static int chunkRadius(Destination dest) {
		return dest.exitPortal == null ? 3 : (int) Math.min(8, 3 + dest.exitPortal.def.radius() / 16);
	}

	public void deactivate(String id) {
		Runtime r = runtimes.get(id);
		if (r == null) {
			return;
		}
		for (Map.Entry<BlockPos, BlockState> e : r.replaced.entrySet()) {
			if (r.level.getBlockState(e.getKey()).is(BtbBlocks.RIFT_PORTAL)) {
				r.level.setBlock(e.getKey(), e.getValue(), 2 | 16);
			}
		}
		r.replaced.clear();
		r.frame.forEach(p -> setFrameLit(r, p, false));
		setKeystone(r, KeystoneStage.IDLE);
		setState(r, PortalState.INACTIVE);
		runtimes.remove(id); // re-created fresh on next use (frame may have been rebuilt)
	}

	private void setFrameLit(Runtime r, BlockPos p, boolean lit) {
		BlockState s = r.level.getBlockState(p);
		if (isFrame(s) && s.getValue(PortalFrameBlock.LIT) != lit) {
			r.level.setBlock(p, s.setValue(PortalFrameBlock.LIT, lit), 3);
		}
	}

	private void setKeystone(Runtime r, KeystoneStage stage) {
		if (r.def.keystone() == null) {
			return;
		}
		BlockState s = r.level.getBlockState(r.def.keystone());
		if (s.is(BtbBlocks.PORTAL_KEYSTONE)) {
			r.level.setBlock(r.def.keystone(), s.setValue(PortalKeystoneBlock.STAGE, stage), 3);
		}
	}

	// ------------------------------------------------------------------ transfer

	private record Destination(ServerLevel level, Vec3 pos, float yaw, float pitch, Vec3 push, Runtime exitPortal) {
	}

	private Destination destination(Runtime r) {
		if (!r.def.link().isEmpty()) {
			Runtime target = runtime(r.def.link());
			if (target == null) {
				return null;
			}
			Direction f = target.def.facing();
			if (target.def.axis() == Direction.Axis.Y) {
				// Floor/ceiling portal: rise out of (or drop from) the surface centre.
				double y = f == Direction.UP ? target.def.min().getY() + 0.2 : target.def.min().getY() - 2.0;
				Vec3 pos = new Vec3(target.def.centerX(), y, target.def.centerZ());
				return new Destination(target.level, pos, 0, 0, new Vec3(0, f == Direction.UP ? 0.62 : -0.1, 0), target);
			}
			Vec3 pos = new Vec3(target.def.centerX() + f.getStepX() * 1.1, target.def.min().getY(), target.def.centerZ() + f.getStepZ() * 1.1);
			Vec3 push = new Vec3(f.getStepX() * 0.32, 0.08, f.getStepZ() * 0.32);
			return new Destination(target.level, pos, f.toYRot(), 0, push, target);
		}
		if (r.def.exit() != null && server != null) {
			PortalDefinition.Exit e = r.def.exit();
			ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, e.dimension()));
			return level == null ? null : new Destination(level, new Vec3(e.x(), e.y(), e.z()), e.yaw(), e.pitch(), Vec3.ZERO, null);
		}
		return null;
	}

	/** Called by the rift block for every player inside it. */
	public void onPlayerInside(ServerLevel level, BlockPos pos, ServerPlayer player) {
		if (cooldown.containsKey(player.getUUID())) {
			return;
		}
		for (Runtime r : runtimes.values()) {
			if (r.level == level && r.state == PortalState.ENTERABLE && r.def.contains(pos)) {
				startTransfer(r, player, r.sceneId);
				return;
			}
		}
	}

	private void startTransfer(Runtime r, ServerPlayer player, String sceneId) {
		Destination dest = destination(r);
		if (dest == null) {
			player.sendSystemMessage(Component.literal("[BTB] Portal '" + r.def.id() + "' has no destination (link or exit)")
					.withStyle(ChatFormatting.RED));
			cooldown.put(player.getUUID(), ENTER_COOLDOWN);
			return;
		}
		PortalDefinition d = r.def;
		int total = d.fadeIn() + d.fadeHold() + d.fadeOut() + ENTER_COOLDOWN;
		cooldown.put(player.getUUID(), total);
		List<ServerPlayer> only = List.of(player);
		effects().ifPresent(fx -> fx.send(sceneId, 0,
				new EffectSpec(EffectSpec.Kind.FADE, d.fadeColor(), d.fadeColor(), 1f, d.fadeIn(), -1, 0, ""), only));
		engine.cues().queue(sceneId, 0, "sound_play", soundCue(BtbSounds.PORTAL_ENTER.location(), 1.2f), only);
		camera().ifPresent(cam -> cam.shake(sceneId, 0, 0.6f, d.fadeIn() + 4, 1.5f, only));
		dest.level.getChunkSource().addTicketWithRadius(TicketType.PORTAL, ChunkPos.containing(BlockPos.containing(dest.pos)), chunkRadius(dest));

		// Teleport one tick after the fade is fully opaque.
		engine.timeline().schedule(TimelineManager.MANUAL, "portal transfer", d.fadeIn() + 1, () -> {
			if (player.isRemoved()) {
				return;
			}
			if (dest.exitPortal != null && dest.exitPortal.state == PortalState.INACTIVE) {
				Runtime exit = dest.exitPortal;
				exit.sceneId = sceneId;
				exit.frame.forEach(p -> setFrameLit(exit, p, true));
				setKeystone(exit, KeystoneStage.ACTIVE);
				openSurface(exit, true);
				setState(exit, PortalState.ENTERABLE);
			}
			player.teleportTo(dest.level, dest.pos.x, dest.pos.y, dest.pos.z, Set.of(), dest.yaw, dest.pitch, true);
			player.resetFallDistance();
			if (dest.push.lengthSqr() > 0) {
				player.setDeltaMovement(dest.push);
				player.connection.send(new ClientboundSetEntityMotionPacket(player));
			}
			if (!d.arrivalScene().isEmpty()) {
				engine.scenes().restart(d.arrivalScene());
			}
			LOG.info("{} transferred through portal '{}'", player.getName().getString(), d.id());
		});
		// Open the image again on the new place.
		engine.timeline().schedule(TimelineManager.MANUAL, "portal reveal", d.fadeIn() + 1 + d.fadeHold(), () ->
				effects().ifPresent(fx -> fx.send(TimelineManager.MANUAL, 0,
						new EffectSpec(EffectSpec.Kind.CLEAR, 0, 0, 1, d.fadeOut(), 0, 0, "fade"), only)));
	}

	private static JsonObject soundCue(Identifier id, float volume) {
		JsonObject o = new JsonObject();
		o.addProperty("sound", id.toString());
		o.addProperty("volume", volume);
		o.addProperty("pitch", 1f);
		return o;
	}

	// ------------------------------------------------------------------ helpers

	private static Vec3 center(Runtime r) {
		return new Vec3(r.def.centerX(), r.def.centerY(), r.def.centerZ());
	}

	private List<ServerPlayer> nearby(Runtime r, double radius) {
		Vec3 c = center(r);
		return r.level.players().stream().filter(p -> p.position().distanceTo(c) <= radius).toList();
	}

	private SoundManager sound() {
		return engine.module(SoundManager.class).orElseThrow();
	}

	private java.util.Optional<EffectManager> effects() {
		return engine.module(EffectManager.class);
	}

	private java.util.Optional<CameraManager> camera() {
		return engine.module(CameraManager.class);
	}

	// ------------------------------------------------------------------ construction

	/** Builds a frame (style block) around the surface of any shape plus a keystone below/next to it. */
	private String build(String id) {
		JsonObject json = definitions.get(id);
		if (json == null) {
			return "Unknown portal";
		}
		PortalDefinition d = PortalDefinition.parse(id, json);
		ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, d.dimension()));
		if (level == null) {
			return "Dimension not found";
		}
		deactivate(id);
		BlockState frame = (d.style() == de.behindtheblocks.cinematic.content.PortalStyle.WHITE ? BtbBlocks.RADIANT_FRAME
				: BtbBlocks.PORTAL_FRAME).defaultBlockState();
		for (BlockPos c : d.cells()) {
			if (!level.getBlockState(c).isAir()) {
				level.setBlock(c, Blocks.AIR.defaultBlockState(), 3);
			}
			for (int u = -1; u <= 1; u++) {
				for (int v = -1; v <= 1; v++) {
					BlockPos n = switch (d.axis()) {
						case X -> c.offset(u, v, 0);
						case Z -> c.offset(0, v, u);
						case Y -> c.offset(u, 0, v);
					};
					if (!d.contains(n)) {
						level.setBlock(n, frame, 3);
					}
				}
			}
		}
		BlockPos key = d.axis() == Direction.Axis.Y
				? BlockPos.containing(d.centerX(), d.min().getY(), d.min().getZ() - 2)
				: BlockPos.containing(d.centerX(), d.min().getY() - 1, d.centerZ());
		level.setBlock(key, BtbBlocks.PORTAL_KEYSTONE.defaultBlockState(), 3);
		json.add("keystone", PortalDefinition.arr(key));
		save(id);
		return null;
	}

	// ------------------------------------------------------------------ commands

	@Override
	public void contributeCommands(LiteralArgumentBuilder<CommandSourceStack> root, CommandBuildContext buildContext) {
		SuggestionProvider<CommandSourceStack> ids = (c, b) -> SharedSuggestionProvider.suggest(definitions.keySet(), b);
		LiteralArgumentBuilder<CommandSourceStack> pt = literal("portal");
		pt.then(literal("list").executes(c -> {
			Feedback.ok(c.getSource(), definitions.size() + " portal(s):");
			definitions.keySet().forEach(id -> Feedback.info(c.getSource(), " • " + id + " — " + state(id)));
			return definitions.size();
		}));
		pt.then(literal("reload").executes(c -> Feedback.ok(c.getSource(), "Loaded " + reload() + " portal(s)")));
		pt.then(literal("create").then(argument("id", StringArgumentType.word())
				.then(argument("from", BlockPosArgument.blockPos()).then(argument("to", BlockPosArgument.blockPos()).executes(c -> {
					String id = StringArgumentType.getString(c, "id");
					if (!SceneLoader.ID_PATTERN.matcher(id).matches()) {
						return Feedback.fail(c.getSource(), "Id must match [a-z0-9_]");
					}
					JsonObject json = PortalDefinition.template(c.getSource().getLevel().dimension().identifier().toString(),
							BlockPosArgument.getBlockPos(c, "from"), BlockPosArgument.getBlockPos(c, "to"));
					try {
						PortalDefinition.parse(id, json);
					} catch (JsonParamException e) {
						return Feedback.fail(c.getSource(), e.getMessage());
					}
					deactivate(id);
					definitions.put(id, json);
					save(id);
					return Feedback.ok(c.getSource(), "Portal '" + id + "' created. Build a frame: /btb portal build " + id);
				})))));
		pt.then(literal("fill").then(argument("id", StringArgumentType.word())
				.then(argument("inside", BlockPosArgument.blockPos()).executes(c -> {
					// Any shape: click a point inside an existing frame, the surface is flood-filled.
					String id = StringArgumentType.getString(c, "id");
					if (!SceneLoader.ID_PATTERN.matcher(id).matches()) {
						return Feedback.fail(c.getSource(), "Id must match [a-z0-9_]");
					}
					var fill = floodFill(c.getSource().getLevel(), BlockPosArgument.getBlockPos(c, "inside"));
					if (fill == null) {
						return Feedback.fail(c.getSource(), "No closed frame found around that point (max " + PortalDefinition.MAX_SURFACE
								+ " blocks). The point must be air inside the frame.");
					}
					JsonObject json = PortalDefinition.template(c.getSource().getLevel().dimension().identifier().toString(),
							fill.getValue(), fill.getKey());
					deactivate(id);
					definitions.put(id, json);
					save(id);
					return Feedback.ok(c.getSource(), "Portal '" + id + "' created from the frame: " + fill.getValue().size()
							+ " blocks, plane " + fill.getKey().getName() + ". Set its keystone: /btb portal keystone " + id + " <pos>");
				}))));
		pt.then(literal("style").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(literal("nether").executes(c -> edit(c, o -> o.addProperty("style", "nether"))))
				.then(literal("white").executes(c -> edit(c, o -> o.addProperty("style", "white"))))));
		pt.then(literal("build").then(argument("id", StringArgumentType.word()).suggests(ids).executes(c -> {
			String err = build(StringArgumentType.getString(c, "id"));
			return err == null ? Feedback.ok(c.getSource(), "Frame and keystone built") : Feedback.fail(c.getSource(), err);
		})));
		pt.then(literal("keystone").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("pos", BlockPosArgument.blockPos()).executes(c ->
						edit(c, o -> o.add("keystone", PortalDefinition.arr(BlockPosArgument.getBlockPos(c, "pos"))))))));
		pt.then(literal("link").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("target", StringArgumentType.word()).suggests(ids)
						.executes(c -> edit(c, o -> {
							o.addProperty("link", StringArgumentType.getString(c, "target"));
							o.remove("exit");
						}))
						.then(literal("both").executes(c -> {
							// Two-way: each portal leads to the other.
							String a = StringArgumentType.getString(c, "id"), b = StringArgumentType.getString(c, "target");
							JsonObject other = definitions.get(b);
							if (other == null) {
								return Feedback.fail(c.getSource(), "Unknown portal '" + b + "'");
							}
							other.addProperty("link", a);
							other.remove("exit");
							deactivate(b);
							save(b);
							return edit(c, o -> {
								o.addProperty("link", b);
								o.remove("exit");
							});
						})))));
		pt.then(literal("exit").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("pos", Vec3Argument.vec3()).then(argument("yaw", FloatArgumentType.floatArg(-180, 180)).executes(c -> edit(c, o -> {
					Vec3 v = Vec3Argument.getVec3(c, "pos");
					JsonObject e = new JsonObject();
					e.addProperty("dimension", c.getSource().getLevel().dimension().identifier().toString());
					var a = new com.google.gson.JsonArray();
					a.add(v.x);
					a.add(v.y);
					a.add(v.z);
					e.add("pos", a);
					e.addProperty("yaw", FloatArgumentType.getFloat(c, "yaw"));
					o.add("exit", e);
					o.remove("link");
				}))))));
		pt.then(literal("facing").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("dir", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("north", "south", "east", "west", "up", "down"), b))
						.executes(c -> edit(c, o -> o.addProperty("facing", StringArgumentType.getString(c, "dir")))))));
		pt.then(literal("requirekey").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(literal("on").executes(c -> edit(c, o -> o.addProperty("require_key", true))))
				.then(literal("off").executes(c -> edit(c, o -> o.addProperty("require_key", false))))));
		pt.then(literal("arrival").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("scene", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(Stream.concat(Stream.of("none"), engine.scenes().sceneIds().stream()), b))
						.executes(c -> edit(c, o -> {
							String s = StringArgumentType.getString(c, "scene");
							o.addProperty("arrival_scene", s.equals("none") ? "" : s);
						})))));
		pt.then(literal("activationscene").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("scene", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(Stream.concat(Stream.of("none"), engine.scenes().sceneIds().stream()), b))
						.executes(c -> edit(c, o -> {
							String s = StringArgumentType.getString(c, "scene");
							o.addProperty("activation_scene", s.equals("none") ? "" : s);
						})))));
		pt.then(literal("transition").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("color", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(Colors.names(), b))
						.then(argument("in", DoubleArgumentType.doubleArg(0.05, 20)).then(argument("hold", DoubleArgumentType.doubleArg(0, 20))
								.then(argument("out", DoubleArgumentType.doubleArg(0.05, 20)).executes(c -> edit(c, o -> {
									JsonObject t = new JsonObject();
									t.addProperty("color", StringArgumentType.getString(c, "color"));
									t.addProperty("in", (int) Math.round(DoubleArgumentType.getDouble(c, "in") * 20));
									t.addProperty("hold", (int) Math.round(DoubleArgumentType.getDouble(c, "hold") * 20));
									t.addProperty("out", (int) Math.round(DoubleArgumentType.getDouble(c, "out") * 20));
									o.add("transition", t);
								}))))))));
		pt.then(literal("activate")
				.executes(c -> {
					String nearest = nearest(c.getSource());
					if (nearest == null) {
						return Feedback.fail(c.getSource(), "No portal defined in this dimension");
					}
					String err = activate(nearest, false, TimelineManager.MANUAL);
					return err == null ? Feedback.ok(c.getSource(), "Activating portal '" + nearest + "'") : Feedback.fail(c.getSource(), err);
				})
				.then(argument("id", StringArgumentType.word()).suggests(ids)
						.executes(c -> activateCmd(c, false))
						.then(literal("instant").executes(c -> activateCmd(c, true)))));
		pt.then(literal("deactivate")
				.executes(c -> {
					int n = runtimes.size();
					List.copyOf(runtimes.keySet()).forEach(this::deactivate);
					return Feedback.ok(c.getSource(), "Closed " + n + " portal(s)");
				})
				.then(argument("id", StringArgumentType.word()).suggests(ids).executes(c -> {
					deactivate(StringArgumentType.getString(c, "id"));
					return Feedback.ok(c.getSource(), "Portal closed");
				})));
		pt.then(literal("state").then(argument("id", StringArgumentType.word()).suggests(ids).executes(c ->
				Feedback.ok(c.getSource(), StringArgumentType.getString(c, "id") + ": " + state(StringArgumentType.getString(c, "id"))))));
		pt.then(literal("info").then(argument("id", StringArgumentType.word()).suggests(ids).executes(c -> {
			JsonObject o = definitions.get(StringArgumentType.getString(c, "id"));
			return o == null ? Feedback.fail(c.getSource(), "Unknown portal") : Feedback.ok(c.getSource(), o.toString());
		})));
		pt.then(literal("delete").then(argument("id", StringArgumentType.word()).suggests(ids).executes(c -> {
			String id = StringArgumentType.getString(c, "id");
			deactivate(id);
			if (definitions.remove(id) == null) {
				return Feedback.fail(c.getSource(), "Unknown portal");
			}
			try {
				Files.deleteIfExists(directory().resolve(id + ".json"));
			} catch (IOException e) {
				LOG.error("Could not delete portal file", e);
			}
			return Feedback.ok(c.getSource(), "Portal '" + id + "' deleted (frame blocks stay)");
		})));
		root.then(pt);
	}

	private int activateCmd(CommandContext<CommandSourceStack> c, boolean instant) {
		String err = activate(StringArgumentType.getString(c, "id"), instant, TimelineManager.MANUAL);
		return err == null ? Feedback.ok(c.getSource(), instant ? "Portal opened" : "Portal activation started")
				: Feedback.fail(c.getSource(), err);
	}

	private String nearest(CommandSourceStack source) {
		String best = null;
		double bestD = Double.MAX_VALUE;
		String dim = source.getLevel().dimension().identifier().toString();
		for (Map.Entry<String, JsonObject> e : definitions.entrySet()) {
			try {
				PortalDefinition d = PortalDefinition.parse(e.getKey(), e.getValue());
				if (!d.dimension().toString().equals(dim)) {
					continue;
				}
				double dist = source.getPosition().distanceTo(new Vec3(d.centerX(), d.centerY(), d.centerZ()));
				if (dist < bestD) {
					bestD = dist;
					best = e.getKey();
				}
			} catch (JsonParamException ignored) {
				// invalid definitions are reported on reload
			}
		}
		return best;
	}

	private int edit(CommandContext<CommandSourceStack> c, Consumer<JsonObject> op) {
		String id = StringArgumentType.getString(c, "id");
		JsonObject current = definitions.get(id);
		if (current == null) {
			return Feedback.fail(c.getSource(), "Unknown portal '" + id + "'");
		}
		JsonObject copy = current.deepCopy();
		op.accept(copy);
		try {
			PortalDefinition.parse(id, copy);
		} catch (JsonParamException e) {
			return Feedback.fail(c.getSource(), e.getMessage());
		}
		deactivate(id);
		definitions.put(id, copy);
		save(id);
		return Feedback.ok(c.getSource(), "Portal '" + id + "' updated");
	}
}
