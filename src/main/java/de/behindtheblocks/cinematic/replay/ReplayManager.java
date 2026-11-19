package de.behindtheblocks.cinematic.replay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import de.behindtheblocks.cinematic.BtbCinematic;
import de.behindtheblocks.cinematic.command.CommandContributor;
import de.behindtheblocks.cinematic.command.Feedback;
import de.behindtheblocks.cinematic.core.CinematicEngine;
import de.behindtheblocks.cinematic.core.CinematicModule;
import de.behindtheblocks.cinematic.core.bus.SceneMessages;
import de.behindtheblocks.cinematic.timeline.TimelineManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Replay: play a take once – the mod records every actor (position, look, pose, items, arm swings),
 * every film creature and the events (scene starts, collapses, eruptions, ghast crashes, the
 * guardian's attacks). Afterwards the take is played back as often as you like with puppets
 * (mannequins with the actors' skins, puppet creatures) – so the camera can film it again and again
 * from any angle without the actors repeating anything. A single recorded actor is a "double".
 */
public final class ReplayManager implements CinematicModule, CommandContributor {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/replay");
	public static final String PUPPET_TAG = "btb_puppet";
	private static final Gson GSON = new GsonBuilder().create();

	/** True while a replay plays: zone triggers and new spawns are held back (the puppets are the take). */
	public static volatile boolean playing;
	private static ReplayManager instance;

	private CinematicEngine engine;
	private MinecraftServer server;

	// ------------------------------------------------------------------ recording state
	private String recName;
	private int recTick;
	private double recRadius;
	private final Map<UUID, JsonObject> recTracks = new LinkedHashMap<>();
	private final JsonArray recEvents = new JsonArray();
	private final Map<UUID, Boolean> lastSwing = new HashMap<>();

	// ------------------------------------------------------------------ playback state
	private static final class Puppet {
		Entity entity;
		JsonArray frames;
		int start;
		boolean player;
	}

	private String playName;
	private volatile int playTick, playLength;
	/** Started from the replay editor: holds (paused) at the end instead of stopping. */
	private boolean editorMode;
	private boolean paused;
	private float speed = 1f;
	private final Map<String, Puppet> puppets = new HashMap<>();
	private final List<JsonObject> playTracks = new ArrayList<>();
	private JsonArray playEvents = new JsonArray();
	private int playEventCursor;
	private final Map<UUID, GameType> hiddenActors = new HashMap<>();
	private String soloActor;

	@Override
	public String id() {
		return "replay";
	}

	@Override
	public void initialize(CinematicEngine engine) {
		this.engine = engine;
		instance = this;
		engine.bus().subscribe(SceneMessages.Started.class, m -> logEvent("scene_start", m.sceneId()));
		engine.bus().subscribe(SceneMessages.Stopped.class, m -> logEvent("scene_stop", m.sceneId()));
		// puppets and scenery creatures cannot be hurt
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
				!(entity.entityTags().contains(PUPPET_TAG) || entity.entityTags().contains("btb_invulnerable")) || source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY));
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		this.server = server;
	}

	@Override
	public void onServerStopping(MinecraftServer server) {
		stopPlayback();
		recName = null;
	}

	public static Path folder() {
		return FabricLoader.getInstance().getConfigDir().resolve(BtbCinematic.MOD_ID).resolve("replays");
	}

	// ------------------------------------------------------------------ hooks used by other modules

	private void logEvent(String type, String arg) {
		if (recName == null) {
			return;
		}
		JsonObject e = new JsonObject();
		e.addProperty("t", recTick);
		e.addProperty("type", type);
		e.addProperty("arg", arg);
		recEvents.add(e);
	}

	/** World events that are not part of a scene (scenes are replayed as a whole). */
	public static void log(String type, String arg) {
		if (instance != null && instance.recName != null && TimelineManager.currentCommandScene() == null) {
			instance.logEvent(type, arg);
		}
	}

	/** Something a recorded creature did (e.g. the guardian's attacks), replayed on its puppet. */
	public static void logEntity(Entity e, String action) {
		if (instance != null && instance.recName != null && instance.recTracks.containsKey(e.getUUID())) {
			instance.logEvent("entity", e.getUUID() + ":" + action);
		}
	}

	public static boolean isPuppet(Entity e) {
		return e.entityTags().contains(PUPPET_TAG);
	}

	// ------------------------------------------------------------------ tick

	@Override
	public void tick(MinecraftServer server) {
		if (recName != null) {
			recordTick(server);
		}
		if (playName != null) {
			playTick();
		}
		tickWaving();
	}

	private List<ServerPlayer> actors(MinecraftServer server) {
		return server.getPlayerList().getPlayers().stream().filter(p -> !p.entityTags().contains("kamera") && !p.isSpectator()).toList();
	}

	private void recordTick(MinecraftServer server) {
		List<ServerPlayer> actors = actors(server);
		for (ServerPlayer p : actors) {
			JsonObject tr = recTracks.computeIfAbsent(p.getUUID(), id -> newPlayerTrack(p));
			addFrame(tr, p, swingStarted(p));
		}
		// film creatures near the actors (spawned waves, the guardian, our creatures)
		for (ServerPlayer a : actors) {
			for (Entity e : a.level().getEntities((Entity) null, a.getBoundingBox().inflate(recRadius), this::filmCreature)) {
				JsonObject tr = recTracks.computeIfAbsent(e.getUUID(), id -> newEntityTrack(e));
				if (tr.get("last").getAsInt() < recTick) {
					addFrame(tr, e, false);
				}
			}
		}
		recTick++;
	}

	private boolean filmCreature(Entity e) {
		if (!(e instanceof LivingEntity) || e instanceof ServerPlayer || e instanceof Mannequin || isPuppet(e) || !e.isAlive()) {
			return false;
		}
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
		return e.entityTags().contains(de.behindtheblocks.cinematic.story.SpawnManager.TAG) || id.getNamespace().equals(BtbCinematic.MOD_ID);
	}

	private boolean swingStarted(ServerPlayer p) {
		boolean now = p.getCurrentSwing() != null;
		Boolean before = lastSwing.put(p.getUUID(), now);
		return now && (before == null || !before);
	}

	private JsonObject newPlayerTrack(ServerPlayer p) {
		JsonObject t = new JsonObject();
		t.addProperty("kind", "player");
		t.addProperty("name", p.getGameProfile().name());
		t.addProperty("uuid", p.getUUID().toString());
		JsonArray props = new JsonArray();
		p.getGameProfile().properties().get("textures").forEach(pr -> {
			JsonObject o = new JsonObject();
			o.addProperty("value", pr.value());
			if (pr.signature() != null) {
				o.addProperty("signature", pr.signature());
			}
			props.add(o);
		});
		t.add("textures", props);
		JsonObject armor = new JsonObject();
		for (EquipmentSlot s : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			ItemStack st = p.getItemBySlot(s);
			if (!st.isEmpty()) {
				armor.addProperty(s.getName(), BuiltInRegistries.ITEM.getKey(st.getItem()).toString());
			}
		}
		t.add("armor", armor);
		t.addProperty("start", recTick);
		t.addProperty("last", recTick - 1);
		t.addProperty("dim", p.level().dimension().identifier().toString());
		t.add("frames", new JsonArray());
		return t;
	}

	private JsonObject newEntityTrack(Entity e) {
		JsonObject t = new JsonObject();
		t.addProperty("kind", "entity");
		t.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
		t.addProperty("uuid", e.getUUID().toString());
		t.addProperty("start", recTick);
		t.addProperty("last", recTick - 1);
		t.addProperty("dim", e.level().dimension().identifier().toString());
		t.add("frames", new JsonArray());
		return t;
	}

	/** One frame: x y z yaw pitch head body pose flags main off – compact array. */
	private void addFrame(JsonObject tr, Entity e, boolean swing) {
		JsonArray f = new JsonArray();
		f.add(Math.round(e.getX() * 1000) / 1000.0);
		f.add(Math.round(e.getY() * 1000) / 1000.0);
		f.add(Math.round(e.getZ() * 1000) / 1000.0);
		f.add(Math.round(e.getYRot() * 10) / 10.0);
		f.add(Math.round(e.getXRot() * 10) / 10.0);
		f.add(Math.round(e.getYHeadRot() * 10) / 10.0);
		float body = e instanceof LivingEntity le ? le.yBodyRot : e.getYRot();
		f.add(Math.round(body * 10) / 10.0);
		f.add(e.getPose().ordinal());
		int flags = (swing ? 1 : 0) | (e.isSprinting() ? 2 : 0) | (e.isOnFire() ? 4 : 0);
		String main = "", off = "";
		if (e instanceof LivingEntity le) {
			if (le.isUsingItem()) {
				flags |= le.getUsedItemHand() == InteractionHand.MAIN_HAND ? 8 : 16;
			}
			main = le.getMainHandItem().isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(le.getMainHandItem().getItem()).toString();
			off = le.getOffhandItem().isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(le.getOffhandItem().getItem()).toString();
		}
		f.add(flags);
		f.add(main);
		f.add(off);
		tr.getAsJsonArray("frames").add(f);
		tr.addProperty("last", recTick);
	}

	// ------------------------------------------------------------------ files

	private void save(String name, JsonObject data) throws IOException {
		Files.createDirectories(folder());
		try (Writer w = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(folder().resolve(name + ".replay"))), StandardCharsets.UTF_8)) {
			GSON.toJson(data, w);
		}
	}

	private JsonObject load(String name) throws IOException {
		try (Reader r = new InputStreamReader(new GZIPInputStream(Files.newInputStream(folder().resolve(name + ".replay"))), StandardCharsets.UTF_8)) {
			return JsonParser.parseReader(r).getAsJsonObject();
		}
	}

	private List<String> names() {
		try (var s = Files.list(folder())) {
			return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".replay")).map(n -> n.substring(0, n.length() - 7)).sorted().toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	// ------------------------------------------------------------------ playback

	private String startPlayback(String name, double fromSeconds, String solo, boolean reset, boolean hideActors) throws IOException {
		if (recName != null) {
			return "Es wird gerade aufgenommen – erst /btb replay stop";
		}
		stopPlayback();
		JsonObject data = load(name);
		if (reset && solo == null) {
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "btb resetall");
		}
		playName = name;
		soloActor = solo;
		playLength = data.get("length").getAsInt();
		playTick = (int) Math.round(fromSeconds * 20);
		playTracks.clear();
		for (var el : data.getAsJsonArray("tracks")) {
			JsonObject t = el.getAsJsonObject();
			if (solo != null && !(t.get("kind").getAsString().equals("player") && t.get("name").getAsString().equalsIgnoreCase(solo))) {
				continue;
			}
			playTracks.add(t);
		}
		playEvents = solo != null ? new JsonArray() : data.getAsJsonArray("events");
		playEventCursor = 0;
		while (playEventCursor < playEvents.size() && playEvents.get(playEventCursor).getAsJsonObject().get("t").getAsInt() < playTick) {
			playEventCursor++;
		}
		if (hideActors && solo == null) {
			for (ServerPlayer p : actors(server)) {
				hiddenActors.put(p.getUUID(), p.gameMode.getGameModeForPlayer());
				p.setGameMode(GameType.SPECTATOR);
			}
		}
		playing = solo == null;
		return null;
	}

	private void playTick() {
		// events of this tick
		while (playEventCursor < playEvents.size()) {
			JsonObject e = playEvents.get(playEventCursor).getAsJsonObject();
			if (e.get("t").getAsInt() > playTick) {
				break;
			}
			playEventCursor++;
			runEvent(e.get("type").getAsString(), e.get("arg").getAsString());
		}
		for (JsonObject t : playTracks) {
			String id = t.get("uuid").getAsString();
			int start = t.get("start").getAsInt();
			JsonArray frames = t.getAsJsonArray("frames");
			int i = playTick - start;
			Puppet pp = puppets.get(id);
			if (i < 0 || i >= frames.size()) {
				if (pp != null && i >= frames.size()) {
					pp.entity.discard(); // gone (died / left) at this point in the take
					puppets.remove(id);
				}
				continue;
			}
			if (pp == null) {
				pp = spawnPuppet(t, frames.get(i).getAsJsonArray());
				if (pp == null) {
					continue;
				}
				puppets.put(id, pp);
			}
			apply(pp, frames.get(i).getAsJsonArray());
		}
		playTick++;
		if (playTick > playLength) {
			if (editorMode) {
				playTick = playLength;
				setPaused(true);
			} else {
				LOG.info("Replay {} finished", playName);
				stopPlayback();
			}
		}
	}

	private void runEvent(String type, String arg) {
		CommandSourceStack src = server.createCommandSourceStack().withSuppressedOutput();
		switch (type) {
			case "scene_start" -> server.getCommands().performPrefixedCommand(src, "btb scene restart " + arg);
			case "scene_stop" -> server.getCommands().performPrefixedCommand(src, "btb scene stop " + arg);
			case "destruction" -> server.getCommands().performPrefixedCommand(src, "btb destruction trigger " + arg);
			case "command" -> server.getCommands().performPrefixedCommand(src, arg);
			case "entity" -> {
				String[] p = arg.split(":", 2);
				Puppet pp = puppets.get(p[0]);
				if (pp != null && pp.entity instanceof de.behindtheblocks.cinematic.content.entity.HeartWarden w) {
					w.playAttack(p[1]);
				}
			}
			default -> { }
		}
	}

	private ServerLevel levelOf(JsonObject t) {
		for (ServerLevel l : server.getAllLevels()) {
			if (l.dimension().identifier().toString().equals(t.get("dim").getAsString())) {
				return l;
			}
		}
		return server.overworld();
	}

	private Puppet spawnPuppet(JsonObject t, JsonArray f) {
		ServerLevel level = levelOf(t);
		Entity e;
		boolean player = t.get("kind").getAsString().equals("player");
		if (player) {
			Mannequin m = Mannequin.create(net.minecraft.world.entity.EntityTypes.MANNEQUIN, level);
			if (m == null) {
				return null;
			}
			com.google.common.collect.Multimap<String, com.mojang.authlib.properties.Property> mm = com.google.common.collect.LinkedHashMultimap.create();
			for (var el : t.getAsJsonArray("textures")) {
				JsonObject o = el.getAsJsonObject();
				mm.put("textures", new com.mojang.authlib.properties.Property("textures", o.get("value").getAsString(),
						o.has("signature") ? o.get("signature").getAsString() : null));
			}
			var profile = new com.mojang.authlib.GameProfile(UUID.fromString(t.get("uuid").getAsString()), t.get("name").getAsString(),
					new com.mojang.authlib.properties.PropertyMap(mm));
			invoke(m, "setProfile", net.minecraft.world.item.component.ResolvableProfile.class,
					net.minecraft.world.item.component.ResolvableProfile.createResolved(profile));
			invoke(m, "setImmovable", boolean.class, true);
			invoke(m, "setHideDescription", boolean.class, true);
			JsonObject armor = t.getAsJsonObject("armor");
			for (String slot : armor.keySet()) {
				m.setItemSlot(EquipmentSlot.byName(slot), new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(armor.get(slot).getAsString()))));
			}
			e = m;
		} else {
			EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(t.get("type").getAsString()));
			e = type.create(level, EntitySpawnReason.COMMAND);
			if (e == null) {
				return null;
			}
			if (e instanceof Mob mob) {
				mob.setNoAi(true);
				mob.setPersistenceRequired();
			}
		}
		e.addTag(PUPPET_TAG);
		e.setNoGravity(true);
		e.addTag("btb_invulnerable");
		e.snapTo(f.get(0).getAsDouble(), f.get(1).getAsDouble(), f.get(2).getAsDouble(), f.get(3).getAsFloat(), f.get(4).getAsFloat());
		level.addFreshEntity(e);
		Puppet pp = new Puppet();
		pp.entity = e;
		pp.player = player;
		return pp;
	}

	private static void invoke(Object target, String method, Class<?> type, Object value) {
		try {
			var m = target.getClass().getDeclaredMethod(method, type);
			m.setAccessible(true);
			m.invoke(target, value);
		} catch (ReflectiveOperationException ex) {
			LOG.warn("Mannequin.{} not available: {}", method, ex.toString());
		}
	}

	private void apply(Puppet pp, JsonArray f) {
		Entity e = pp.entity;
		double x = f.get(0).getAsDouble(), y = f.get(1).getAsDouble(), z = f.get(2).getAsDouble();
		float yaw = f.get(3).getAsFloat(), pitch = f.get(4).getAsFloat();
		e.snapTo(x, y, z, yaw, pitch);
		e.setYHeadRot(f.get(5).getAsFloat());
		if (e instanceof LivingEntity le) {
			le.yBodyRot = f.get(6).getAsFloat();
		}
		Pose pose = Pose.values()[Math.min(Pose.values().length - 1, f.get(7).getAsInt())];
		if (e.getPose() != pose && (!pp.player || pose == Pose.STANDING || pose == Pose.CROUCHING || pose == Pose.SWIMMING
				|| pose == Pose.FALL_FLYING || pose == Pose.SLEEPING)) {
			e.setPose(pose);
		}
		int flags = f.get(8).getAsInt();
		e.setSprinting((flags & 2) != 0);
		if ((flags & 4) != 0) {
			e.setRemainingFireTicks(10);
		}
		if (e instanceof LivingEntity le) {
			if ((flags & 1) != 0) {
				le.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
			}
			setItem(le, EquipmentSlot.MAINHAND, f.get(9).getAsString());
			setItem(le, EquipmentSlot.OFFHAND, f.get(10).getAsString());
			boolean using = (flags & 24) != 0;
			if (using && !le.isUsingItem()) {
				le.startUsingItem((flags & 8) != 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
			} else if (!using && le.isUsingItem()) {
				le.stopUsingItem();
			}
		}
	}

	private static void setItem(LivingEntity le, EquipmentSlot slot, String id) {
		String cur = le.getItemBySlot(slot).isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(le.getItemBySlot(slot).getItem()).toString();
		if (!cur.equals(id)) {
			le.setItemSlot(slot, id.isEmpty() ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id))));
		}
	}

	private void stopPlayback() {
		puppets.values().forEach(p -> p.entity.discard());
		puppets.clear();
		if (server != null) {
			for (var en : hiddenActors.entrySet()) {
				ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
				if (p != null) {
					p.setGameMode(en.getValue());
				}
			}
			// leftover puppets (e.g. after a crash) are removed too
			for (ServerLevel l : server.getAllLevels()) {
				for (Entity e : l.getAllEntities()) {
					if (isPuppet(e)) {
						e.discard();
					}
				}
			}
		}
		hiddenActors.clear();
		playName = null;
		playing = false;
		editorMode = false;
		if (paused || speed != 1f) {
			paused = false;
			speed = 1f;
			if (server != null) {
				server.tickRateManager().setFrozen(false);
				server.tickRateManager().setTickRate(20f);
			}
		}
	}

	// ------------------------------------------------------------------ editor API (same JVM in single player)

	/** Name of the replay currently playing, or null. */
	public static String current() {
		return instance == null ? null : instance.playName;
	}

	/** Next tick of the take to be played (the puppets show tick-1). */
	public static int currentTick() {
		return instance == null ? 0 : instance.playTick;
	}

	public static int currentLength() {
		return instance == null ? 0 : instance.playLength;
	}

	public static boolean isPaused() {
		return instance != null && instance.paused;
	}

	public static float currentSpeed() {
		return instance == null ? 1f : instance.speed;
	}

	public static List<String> list() {
		return instance == null ? List.of() : instance.names();
	}

	/** Length, tracks (name, kind, start, frames) and events of a take – without the frames, for the editor timeline. */
	public static JsonObject header(String name) {
		try {
			JsonObject d = instance.load(name), h = new JsonObject();
			h.addProperty("length", d.get("length").getAsInt());
			JsonArray tr = new JsonArray();
			for (var el : d.getAsJsonArray("tracks")) {
				JsonObject t = el.getAsJsonObject(), o = new JsonObject();
				o.addProperty("name", t.has("name") ? t.get("name").getAsString() : t.get("kind").getAsString());
				o.addProperty("kind", t.get("kind").getAsString());
				o.addProperty("start", t.get("start").getAsInt());
				o.addProperty("frames", t.getAsJsonArray("frames").size());
				tr.add(o);
			}
			h.add("tracks", tr);
			h.add("events", d.getAsJsonArray("events").deepCopy());
			return h;
		} catch (Exception e) {
			return null;
		}
	}

	private void setPaused(boolean p) {
		paused = p;
		server.tickRateManager().setFrozen(p); // the whole world holds still, like a paused video
	}

	private void setSpeed(float f) {
		speed = Math.max(0.1f, Math.min(4f, f));
		server.tickRateManager().setTickRate(20f * speed); // smooth slow/fast motion of everything
	}

	/**
	 * Jumps to a moment of the take: the set is reset, scenes that were running at that moment resume
	 * at the right offset, collapses that already happened are triggered again, and the puppets stand
	 * exactly where they were.
	 */
	private String seek(double seconds) throws IOException {
		if (playName == null) {
			return "Es läuft kein Replay";
		}
		String n = playName;
		boolean wasPaused = paused, edit = editorMode;
		float sp = speed;
		if (wasPaused) {
			server.tickRateManager().setFrozen(false);
		}
		int target = Math.max(0, Math.min(playLength, (int) Math.round(seconds * 20)));
		String err = startPlayback(n, target / 20.0, null, true, true);
		if (err != null) {
			return err;
		}
		editorMode = edit;
		// what happened before the target: scenes still running resume mid-way, collapses happen (again)
		Map<String, Integer> running = new LinkedHashMap<>();
		CommandSourceStack src = server.createCommandSourceStack().withSuppressedOutput();
		for (var el : playEvents) {
			JsonObject e = el.getAsJsonObject();
			int t = e.get("t").getAsInt();
			if (t >= target) {
				break;
			}
			String type = e.get("type").getAsString(), arg = e.get("arg").getAsString();
			switch (type) {
				case "scene_start" -> running.put(arg, t);
				case "scene_stop" -> running.remove(arg);
				case "destruction" -> server.getCommands().performPrefixedCommand(src, "btb destruction trigger " + arg);
				default -> {
				}
			}
		}
		running.forEach((id, t) -> server.getCommands().performPrefixedCommand(src,
				String.format(java.util.Locale.ROOT, "btb scene restart %s from %.2f", id, (target - t) / 20.0)));
		playTick(); // puppets appear at the target right away (also while paused)
		if (sp != 1f) {
			setSpeed(sp);
		}
		if (wasPaused) {
			setPaused(true);
		}
		return null;
	}

	// ------------------------------------------------------------------ commands

	@Override
	public void contributeCommands(LiteralArgumentBuilder<CommandSourceStack> root, CommandBuildContext buildContext) {
		SuggestionProvider<CommandSourceStack> replays = (c, b) -> SharedSuggestionProvider.suggest(names(), b);
		root.then(literal("replay")
				.then(literal("record").then(argument("name", StringArgumentType.word()).executes(c -> {
					if (playName != null || recName != null) {
						return Feedback.fail(c.getSource(), "Läuft schon etwas – erst /btb replay stop");
					}
					recName = StringArgumentType.getString(c, "name");
					recTick = 0;
					recRadius = 64;
					recTracks.clear();
					lastSwing.clear();
					while (recEvents.size() > 0) {
						recEvents.remove(0);
					}
					// scenes already running at the start are part of the take
					engine.scenes().sceneIds().stream().filter(engine.scenes()::isRunning).forEach(s -> logEvent("scene_start", s));
					return Feedback.ok(c.getSource(), "● Replay „" + recName + "“ nimmt auf: alle Darsteller (nicht Tag kamera), Kreaturen, Szenen und Ereignisse. Ende: /btb replay stop");
				})))
				.then(literal("stop").executes(c -> {
					if (recName != null) {
						JsonObject data = new JsonObject();
						data.addProperty("length", recTick);
						JsonArray tracks = new JsonArray();
						recTracks.values().forEach(tracks::add);
						data.add("tracks", tracks);
						data.add("events", recEvents.deepCopy());
						String n = recName;
						recName = null;
						try {
							save(n, data);
						} catch (IOException e) {
							return Feedback.fail(c.getSource(), "Speichern fehlgeschlagen: " + e.getMessage());
						}
						return Feedback.ok(c.getSource(), String.format(java.util.Locale.ROOT, "Replay „%s“ gespeichert: %.1f s, %d Spuren. Abspielen: /btb replay play %s",
								n, recTick / 20.0, recTracks.size(), n));
					}
					if (playName != null) {
						stopPlayback();
						return Feedback.ok(c.getSource(), "Replay gestoppt");
					}
					return Feedback.fail(c.getSource(), "Es läuft keine Aufnahme und kein Replay");
				}))
				.then(literal("play").then(argument("name", StringArgumentType.word()).suggests(replays)
						.executes(c -> play(c.getSource(), StringArgumentType.getString(c, "name"), 0))
						.then(literal("from").then(argument("seconds", DoubleArgumentType.doubleArg(0, 36000))
								.executes(c -> play(c.getSource(), StringArgumentType.getString(c, "name"), DoubleArgumentType.getDouble(c, "seconds")))))))
				.then(literal("edit").then(argument("name", StringArgumentType.word()).suggests(replays).executes(c -> {
					String n = StringArgumentType.getString(c, "name");
					try {
						String err = startPlayback(n, 0, null, true, true);
						if (err != null) {
							return Feedback.fail(c.getSource(), err);
						}
					} catch (IOException e) {
						return Feedback.fail(c.getSource(), "Replay „" + n + "“ nicht gefunden");
					}
					editorMode = true;
					playTick();
					setPaused(true);
					return Feedback.ok(c.getSource(), "Replay-Editor: „" + n + "“ geladen (pausiert)");
				})))
				.then(literal("editor").executes(c -> editor(c.getSource(), null))
						.then(argument("name", StringArgumentType.word()).suggests(replays).executes(c -> editor(c.getSource(), StringArgumentType.getString(c, "name")))))
				.then(literal("pause").executes(c -> {
					if (playName == null) {
						return Feedback.fail(c.getSource(), "Es läuft kein Replay");
					}
					setPaused(!paused);
					return Feedback.ok(c.getSource(), paused ? "⏸ Pause" : "▶ Weiter");
				}))
				.then(literal("seek").then(argument("seconds", DoubleArgumentType.doubleArg(0, 36000)).executes(c -> {
					try {
						String err = seek(DoubleArgumentType.getDouble(c, "seconds"));
						return err == null ? Feedback.ok(c.getSource(), String.format(java.util.Locale.ROOT, "⏩ %.1f s", playTick / 20.0))
								: Feedback.fail(c.getSource(), err);
					} catch (IOException e) {
						return Feedback.fail(c.getSource(), e.getMessage());
					}
				})))
				.then(literal("speed").then(argument("factor", com.mojang.brigadier.arguments.FloatArgumentType.floatArg(0.1f, 4f)).executes(c -> {
					if (playName == null) {
						return Feedback.fail(c.getSource(), "Es läuft kein Replay");
					}
					setSpeed(com.mojang.brigadier.arguments.FloatArgumentType.getFloat(c, "factor"));
					return Feedback.ok(c.getSource(), "Tempo " + speed + "×");
				})))
				.then(literal("list").executes(c -> Feedback.ok(c.getSource(), names().isEmpty() ? "Keine Replays" : "Replays: " + String.join(", ", names()))))
				.then(literal("delete").then(argument("name", StringArgumentType.word()).suggests(replays).executes(c -> {
					try {
						Files.deleteIfExists(folder().resolve(StringArgumentType.getString(c, "name") + ".replay"));
					} catch (IOException e) {
						return Feedback.fail(c.getSource(), e.getMessage());
					}
					return Feedback.ok(c.getSource(), "Replay gelöscht");
				}))));
		// export <name> <seconds> [4k|1440p|1080p] [fps] [replay]: frame-exact film export on the executing client
		var res = (SuggestionProvider<CommandSourceStack>) (c, bld) -> SharedSuggestionProvider.suggest(List.of("4k", "1440p", "1080p"), bld);
		root.then(literal("export")
				.then(literal("stop").executes(c -> {
					engine.cues().queue(TimelineManager.MANUAL, 0, "export_stop", new JsonObject(), List.of(c.getSource().getPlayerOrException()));
					return Feedback.ok(c.getSource(), "Export gestoppt");
				}))
				.then(argument("name", StringArgumentType.word())
						.then(argument("seconds", DoubleArgumentType.doubleArg(0.5, 3600))
								.executes(c -> export(c.getSource(), StringArgumentType.getString(c, "name"), DoubleArgumentType.getDouble(c, "seconds"), "4k", 60, null))
								.then(argument("resolution", StringArgumentType.word()).suggests(res)
										.executes(c -> export(c.getSource(), StringArgumentType.getString(c, "name"), DoubleArgumentType.getDouble(c, "seconds"),
												StringArgumentType.getString(c, "resolution"), 60, null))
										.then(argument("fps", com.mojang.brigadier.arguments.IntegerArgumentType.integer(20, 120))
												.executes(c -> export(c.getSource(), StringArgumentType.getString(c, "name"), DoubleArgumentType.getDouble(c, "seconds"),
														StringArgumentType.getString(c, "resolution"), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "fps"), null))
												.then(argument("replay", StringArgumentType.word()).suggests(replays)
														.executes(c -> export(c.getSource(), StringArgumentType.getString(c, "name"), DoubleArgumentType.getDouble(c, "seconds"),
																StringArgumentType.getString(c, "resolution"), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "fps"),
																StringArgumentType.getString(c, "replay")))))))));
		var gestures = (SuggestionProvider<CommandSourceStack>) (c, bld) -> SharedSuggestionProvider.suggest(
				List.of("ducken", "liegen", "kriechen", "zielen", "abwehren", "arm", "fernrohr", "winken", "normal"), bld);
		root.then(literal("geste").then(argument("targets", net.minecraft.commands.arguments.EntityArgument.entities())
				.then(argument("geste", StringArgumentType.word()).suggests(gestures)
						.executes(c -> gesture(c.getSource(), net.minecraft.commands.arguments.EntityArgument.getEntities(c, "targets"),
								StringArgumentType.getString(c, "geste"), 0))
						.then(argument("seconds", DoubleArgumentType.doubleArg(0.1, 600))
								.executes(c -> gesture(c.getSource(), net.minecraft.commands.arguments.EntityArgument.getEntities(c, "targets"),
										StringArgumentType.getString(c, "geste"), DoubleArgumentType.getDouble(c, "seconds")))))));
		root.then(literal("statist")
				.then(literal("clear").executes(c -> {
					int n = 0;
					for (ServerLevel l : c.getSource().getServer().getAllLevels()) {
						for (Entity e : l.getAllEntities()) {
							if (e.entityTags().contains("btb_extra")) {
								e.discard();
								n++;
							}
						}
					}
					return Feedback.ok(c.getSource(), n + " Statisten entfernt");
				}))
				.then(argument("name", StringArgumentType.word())
						.executes(c -> extra(c.getSource(), StringArgumentType.getString(c, "name"), c.getSource().getPosition()))
						.then(argument("pos", net.minecraft.commands.arguments.coordinates.Vec3Argument.vec3())
								.executes(c -> extra(c.getSource(), StringArgumentType.getString(c, "name"),
										net.minecraft.commands.arguments.coordinates.Vec3Argument.getVec3(c, "pos"))))));
		// doubles: one recorded actor plays again as a puppet next to the live take (nothing is reset)
		root.then(literal("double")
				.then(literal("record").then(argument("name", StringArgumentType.word()).executes(c ->
						c.getSource().getServer().getCommands().getDispatcher().execute("btb replay record " + StringArgumentType.getString(c, "name"), c.getSource()))))
				.then(literal("stop").executes(c -> {
					stopPlayback();
					return Feedback.ok(c.getSource(), "Double entfernt");
				}))
				.then(argument("name", StringArgumentType.word()).suggests(replays)
						.then(argument("actor", StringArgumentType.word()).executes(c -> {
							try {
								String err = startPlayback(StringArgumentType.getString(c, "name"), 0, StringArgumentType.getString(c, "actor"), false, false);
								return err == null ? Feedback.ok(c.getSource(), "Double: " + StringArgumentType.getString(c, "actor") + " spielt seine Aufnahme nach")
										: Feedback.fail(c.getSource(), err);
							} catch (IOException e) {
								return Feedback.fail(c.getSource(), "Replay nicht gefunden");
							}
						}))));
	}

	// ------------------------------------------------------------------ gestures

	private final List<Object[]> waving = new ArrayList<>(); // {entity, ticksLeft}

	/**
	 * Gestures for puppets, doubles and extras (mannequins): duck, lie, crawl, aim, defend, raise arm,
	 * spyglass, wave – optionally only for some seconds.
	 */
	private int gesture(CommandSourceStack src, java.util.Collection<? extends Entity> targets, String g, double seconds) {
		int n = 0;
		for (Entity e : targets) {
			if (!(e instanceof LivingEntity le) || e instanceof ServerPlayer) {
				continue;
			}
			ItemStack mainBefore = le.getMainHandItem().copy(), offBefore = le.getOffhandItem().copy();
			switch (g) {
				case "ducken" -> e.setPose(Pose.CROUCHING);
				case "liegen" -> e.setPose(Pose.SLEEPING);
				case "kriechen" -> e.setPose(Pose.SWIMMING);
				case "zielen" -> useItem(le, InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.BOW));
				case "abwehren" -> useItem(le, InteractionHand.OFF_HAND, new ItemStack(net.minecraft.world.item.Items.SHIELD));
				case "arm" -> useItem(le, InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.TRIDENT));
				case "fernrohr" -> useItem(le, InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.SPYGLASS));
				case "winken" -> waving.add(new Object[] {le, (int) Math.round((seconds > 0 ? seconds : 3) * 20)});
				default -> {
					e.setPose(Pose.STANDING);
					le.stopUsingItem();
				}
			}
			n++;
			if (seconds > 0 && !g.equals("winken")) {
				engine.timeline().schedule("", "gesture end", (int) Math.round(seconds * 20), () -> {
					e.setPose(Pose.STANDING);
					le.stopUsingItem();
					le.setItemSlot(EquipmentSlot.MAINHAND, mainBefore);
					le.setItemSlot(EquipmentSlot.OFFHAND, offBefore);
				});
			}
		}
		return n == 0 ? Feedback.fail(src, "Gesten gehen bei Puppen, Doubles und Statisten (keine echten Spieler)")
				: Feedback.ok(src, "Geste „" + g + "“ für " + n);
	}

	private static void useItem(LivingEntity le, InteractionHand hand, ItemStack stack) {
		le.setItemInHand(hand, stack);
		le.startUsingItem(hand);
	}

	private void tickWaving() {
		java.util.Iterator<Object[]> it = waving.iterator();
		while (it.hasNext()) {
			Object[] w = it.next();
			LivingEntity le = (LivingEntity) w[0];
			int left = (int) w[1] - 1;
			w[1] = left;
			if (left % 8 == 0) {
				le.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
			}
			if (left <= 0 || le.isRemoved()) {
				it.remove();
			}
		}
	}

	/** An extra: a mannequin with a player's skin (name of a player who is online, or any name for the default look). */
	private int extra(CommandSourceStack src, String name, net.minecraft.world.phys.Vec3 pos) {
		ServerLevel level = src.getLevel();
		Mannequin m = Mannequin.create(net.minecraft.world.entity.EntityTypes.MANNEQUIN, level);
		if (m == null) {
			return 0;
		}
		ServerPlayer online = src.getServer().getPlayerList().getPlayerByName(name);
		invoke(m, "setProfile", net.minecraft.world.item.component.ResolvableProfile.class, online != null
				? net.minecraft.world.item.component.ResolvableProfile.createResolved(online.getGameProfile())
				: net.minecraft.world.item.component.ResolvableProfile.createUnresolved(name));
		invoke(m, "setHideDescription", boolean.class, true);
		m.addTag("btb_extra");
		m.addTag("btb_invulnerable");
		m.snapTo(pos.x, pos.y, pos.z, src.getRotation().y + 180, 0);
		m.setYHeadRot(src.getRotation().y + 180);
		level.addFreshEntity(m);
		return Feedback.ok(src, "Statist „" + name + "“ – Gesten: /btb geste @e[tag=btb_extra,limit=1,sort=nearest] winken");
	}

	private int export(CommandSourceStack src, String name, double seconds, String res, int fps, String replay)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		int w, h;
		switch (res) {
			case "1080p" -> { w = 1920; h = 1080; }
			case "1440p" -> { w = 2560; h = 1440; }
			default -> { w = 3840; h = 2160; }
		}
		JsonObject o = new JsonObject();
		o.addProperty("name", name);
		o.addProperty("seconds", seconds);
		o.addProperty("width", w);
		o.addProperty("height", h);
		o.addProperty("fps", fps);
		if (replay != null) {
			o.addProperty("replay", replay);
		}
		engine.cues().queue(TimelineManager.MANUAL, 0, "export_start", o, List.of(src.getPlayerOrException()));
		return Feedback.ok(src, "Export „" + name + "“ startet: " + w + "×" + h + ", " + fps + " FPS, " + seconds + " s");
	}

	private int editor(CommandSourceStack src, String name) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		JsonObject o = new JsonObject();
		if (name != null) {
			o.addProperty("name", name);
		}
		engine.cues().queue(TimelineManager.MANUAL, 0, "replay_editor", o, List.of(src.getPlayerOrException()));
		return 1;
	}

	private int play(CommandSourceStack src, String name, double from) {
		try {
			String err = startPlayback(name, from, null, true, true);
			return err == null ? Feedback.ok(src, "▶ Replay „" + name + "“ – Darsteller sind kurz Zuschauer, die Puppen spielen. Filmen mit jeder Kamera; Stopp: /btb replay stop")
					: Feedback.fail(src, err);
		} catch (IOException e) {
			return Feedback.fail(src, "Replay „" + name + "“ nicht gefunden");
		}
	}
}
