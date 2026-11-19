package de.behindtheblocks.cinematic.story;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import de.behindtheblocks.cinematic.BtbCinematic;
import de.behindtheblocks.cinematic.command.CommandContributor;
import de.behindtheblocks.cinematic.command.Feedback;
import de.behindtheblocks.cinematic.core.CinematicEngine;
import de.behindtheblocks.cinematic.core.CinematicModule;
import de.behindtheblocks.cinematic.core.bus.SceneMessages;
import de.behindtheblocks.cinematic.json.JsonParamException;
import de.behindtheblocks.cinematic.json.JsonParams;
import de.behindtheblocks.cinematic.scene.SceneLoader;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Story triggers: things happen when an actor gets somewhere or touches something — the book
 * on the pedestal, a bridge that starts burning, enemies appearing at a door.
 *
 * <pre>{@code
 * { "dimension": "minecraft:the_nether", "type": "interact", "pos": [x,y,z],      // right-click this block
 *   // or "type": "zone", "pos": [x,y,z], "radius": 4
 *   "once": true, "cancel": true,                                                   // cancel = don't open the lectern etc.
 *   "actions": ["btb hud show book_heart", "btb sound play crystal_shimmer"] }
 * }</pre>
 * Actions are server commands run with game-master permission. Triggers re-arm on {@code /btb resetall},
 * on any scene reset and with {@code /btb trigger rearm}.
 */
public final class TriggerManager implements CinematicModule, CommandContributor {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/trigger");

	private record Trigger(String id, Identifier dimension, boolean zone, Vec3 pos, double radius, boolean once, boolean cancel,
			boolean enabled, List<String> actions, String requireItem, boolean consume) {
	}

	private final Map<String, JsonObject> definitions = new TreeMap<>();
	private final Map<String, Trigger> triggers = new TreeMap<>();
	private final Set<String> fired = new HashSet<>();
	/** Players currently inside each zone: zones fire on entering, not while standing inside. */
	private final Map<String, Set<java.util.UUID>> inside = new java.util.HashMap<>();
	private CinematicEngine engine;
	private MinecraftServer server;

	@Override
	public String id() {
		return "trigger";
	}

	public static Path directory() {
		return FabricLoader.getInstance().getConfigDir().resolve(BtbCinematic.MOD_ID).resolve("triggers");
	}

	@Override
	public void initialize(CinematicEngine engine) {
		this.engine = engine;
		engine.bus().subscribe(SceneMessages.Reset.class, m -> rearm());
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!(level instanceof ServerLevel sl) || !(player instanceof ServerPlayer sp) || triggers.isEmpty()) {
				return InteractionResult.PASS;
			}
			BlockPos clicked = hit.getBlockPos();
			for (Trigger t : triggers.values()) {
				if (!t.zone && t.enabled && sl.dimension().identifier().equals(t.dimension) && BlockPos.containing(t.pos).equals(clicked)) {
					if (!t.requireItem.isEmpty()) {
						var held = sp.getItemInHand(hand);
						String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
						if (!id.equals(t.requireItem)) {
							if (!(t.once && fired.contains(t.id))) {
								sp.sendSystemMessage(net.minecraft.network.chat.Component.literal("Verschlossen – dafür brauchst du: "
										+ net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(Identifier.parse(t.requireItem)).getDefaultInstance().getHoverName().getString())
										.withStyle(net.minecraft.ChatFormatting.GOLD), true);
								sl.playSound(null, clicked, net.minecraft.sounds.SoundEvents.CHEST_LOCKED, net.minecraft.sounds.SoundSource.BLOCKS, 1f, 1f);
							}
							return InteractionResult.SUCCESS;
						}
						if (t.consume && !(t.once && fired.contains(t.id)) && !sp.getAbilities().instabuild) {
							held.shrink(1);
						}
					}
					boolean ran = fire(t, sp);
					if (t.cancel && (ran || t.once)) {
						return InteractionResult.SUCCESS;
					}
				}
			}
			return InteractionResult.PASS;
		});
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		this.server = server;
		reload();
	}

	public int reload() {
		definitions.clear();
		triggers.clear();
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
						triggers.put(id, parse(id, json));
						definitions.put(id, json);
					} catch (RuntimeException e) {
						LOG.warn("Trigger {} invalid: {}", name, e.getMessage());
					}
				}
			}
		} catch (IOException e) {
			LOG.error("Cannot read trigger directory", e);
		}
		return triggers.size();
	}

	private static Trigger parse(String id, JsonObject json) {
		JsonParams p = new JsonParams(json);
		double[] pos = p.requireDoubleArray("pos", 3);
		String type = p.getString("type", "zone");
		if (!type.equals("zone") && !type.equals("interact")) {
			throw new JsonParamException("'type' must be zone or interact");
		}
		List<String> actions = new ArrayList<>();
		if (json.has("actions")) {
			for (JsonElement e : json.getAsJsonArray("actions")) {
				String a = e.getAsString().trim();
				actions.add(a.startsWith("/") ? a.substring(1) : a);
			}
		}
		return new Trigger(id, Identifier.tryParse(p.getString("dimension", "minecraft:overworld")), type.equals("zone"),
				new Vec3(pos[0], pos[1], pos[2]), Math.max(0.5, p.getDouble("radius", 3)), p.getBoolean("once", true),
				p.getBoolean("cancel", true), p.getBoolean("enabled", true), actions, p.getString("require_item", ""),
				p.getBoolean("consume", false));
	}

	private void put(String id, JsonObject json) {
		Trigger t = parse(id, json);
		triggers.put(id, t);
		definitions.put(id, json);
		// Whoever stands in the zone while setting it up must not set it off; it fires on the next entry.
		inside.put(id, occupants(t));
		fired.remove(id); // an edited / re-created trigger is armed again
		try {
			Files.createDirectories(directory());
			Files.writeString(directory().resolve(id + ".json"), new GsonBuilder().setPrettyPrinting().create().toJson(json), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOG.error("Could not save trigger {}", id, e);
		}
	}

	/** @return true if the actions ran */
	private boolean fire(Trigger t, ServerPlayer by) {
		if (t.once && fired.contains(t.id)) {
			return false;
		}
		fired.add(t.id);
		LOG.info("Trigger '{}' fired by {}", t.id, by.getName().getString());
		CommandSourceStack source = server.createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER)
				.withSuppressedOutput().withPosition(by.position()).withEntity(by).withLevel((ServerLevel) by.level());
		Commands commands = server.getCommands();
		for (String a : t.actions) {
			ParseResults<CommandSourceStack> parsed = commands.getDispatcher().parse(a, source);
			if (parsed.getReader().canRead() || !parsed.getExceptions().isEmpty()) {
				LOG.warn("Trigger '{}': invalid action '/{}'", t.id, a);
				continue;
			}
			commands.performCommand(parsed, a);
		}
		return true;
	}

	@Override
	public void tick(MinecraftServer server) {
		if (triggers.isEmpty() || server.getTickCount() % 4 != 0 || de.behindtheblocks.cinematic.replay.ReplayManager.playing) {
			return;
		}
		for (Trigger t : triggers.values()) {
			if (!t.zone || !t.enabled || t.once && fired.contains(t.id)) {
				continue;
			}
			ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, t.dimension));
			if (level == null) {
				continue;
			}
			Set<java.util.UUID> now = occupants(t, level);
			Set<java.util.UUID> before = inside.put(t.id, now);
			if (before == null) {
				continue; // first look at this zone (world just loaded): only remember who is inside
			}
			for (ServerPlayer p : level.players()) {
				if (now.contains(p.getUUID()) && !before.contains(p.getUUID())) {
					fire(t, p);
					break;
				}
			}
		}
	}

	private Set<java.util.UUID> occupants(Trigger t) {
		ServerLevel level = server == null || !t.zone ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, t.dimension));
		return level == null ? new HashSet<>() : occupants(t, level);
	}

	private static Set<java.util.UUID> occupants(Trigger t, ServerLevel level) {
		Set<java.util.UUID> out = new HashSet<>();
		for (ServerPlayer p : level.players()) {
			if (!p.isSpectator() && p.position().distanceTo(t.pos) <= t.radius) {
				out.add(p.getUUID());
			}
		}
		return out;
	}

	/** Draws the zone border for a few seconds so it can be seen while setting up. */
	private void showZone(ServerLevel level, Trigger t) {
		if (t == null || !t.zone) {
			return;
		}
		for (int k = 0; k < 6; k++) {
			engine.timeline().schedule(de.behindtheblocks.cinematic.timeline.TimelineManager.MANUAL, "zone ring", k * 10, () -> {
				int n = (int) Math.max(16, t.radius * 10);
				for (int i = 0; i < n; i++) {
					double a = i * Math.PI * 2 / n;
					level.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD, t.pos.x + Math.cos(a) * t.radius, t.pos.y + 0.2,
							t.pos.z + Math.sin(a) * t.radius, 1, 0, 0, 0, 0);
				}
			});
		}
	}

	public java.util.Collection<String> ids() {
		return triggers.keySet();
	}

	public void rearm() {
		fired.clear();
		// Re-armed zones fire on the next entry, not for whoever happens to stand inside right now.
		for (Trigger t : triggers.values()) {
			inside.put(t.id, occupants(t));
		}
	}

	// ------------------------------------------------------------------ commands

	@Override
	public void contributeCommands(LiteralArgumentBuilder<CommandSourceStack> root, CommandBuildContext buildContext) {
		SuggestionProvider<CommandSourceStack> ids = (c, b) -> SharedSuggestionProvider.suggest(triggers.keySet(), b);
		LiteralArgumentBuilder<CommandSourceStack> tr = literal("trigger");
		tr.then(literal("list").executes(c -> {
			Feedback.ok(c.getSource(), triggers.size() + " trigger(s):");
			triggers.values().forEach(t -> Feedback.info(c.getSource(), " • " + t.id + " (" + (t.zone ? "zone" : "interact") + ", "
					+ t.actions.size() + " action(s)" + (fired.contains(t.id) ? ", fired" : "") + ")"));
			return triggers.size();
		}));
		tr.then(literal("reload").executes(c -> Feedback.ok(c.getSource(), "Loaded " + reload() + " trigger(s)")));
		tr.then(literal("rearm").executes(c -> {
			rearm();
			return Feedback.ok(c.getSource(), "All triggers armed again");
		}));
		tr.then(literal("zone").then(argument("id", StringArgumentType.word()).then(argument("radius", DoubleArgumentType.doubleArg(0.5, 64))
				.executes(c -> create(c, true, c.getSource().getPosition(), DoubleArgumentType.getDouble(c, "radius"))))));
		tr.then(literal("interact").then(argument("id", StringArgumentType.word()).then(argument("pos", BlockPosArgument.blockPos())
				.executes(c -> create(c, false, Vec3.atCenterOf(BlockPosArgument.getBlockPos(c, "pos")), 1)))));
		tr.then(literal("action").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(argument("command", StringArgumentType.greedyString()).executes(c -> edit(c, o -> {
					JsonArray a = o.has("actions") ? o.getAsJsonArray("actions") : new JsonArray();
					a.add(StringArgumentType.getString(c, "command"));
					o.add("actions", a);
				})))));
		// Lock an interact trigger: only fires when the player holds this item (e.g. the Nether key).
		tr.then(literal("needitem").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(literal("none").executes(c -> edit(c, o -> o.remove("require_item"))))
				.then(argument("item", net.minecraft.commands.arguments.IdentifierArgument.id())
						.suggests((c, b) -> SharedSuggestionProvider.suggestResource(net.minecraft.core.registries.BuiltInRegistries.ITEM.keySet(), b))
						.executes(c -> edit(c, o -> o.addProperty("require_item", net.minecraft.commands.arguments.IdentifierArgument.getId(c, "item").toString())))
						.then(literal("consume").executes(c -> edit(c, o -> {
							o.addProperty("require_item", net.minecraft.commands.arguments.IdentifierArgument.getId(c, "item").toString());
							o.addProperty("consume", true);
						}))))));
		tr.then(literal("clear").then(argument("id", StringArgumentType.word()).suggests(ids).executes(c -> edit(c, o -> o.add("actions", new JsonArray())))));
		tr.then(literal("once").then(argument("id", StringArgumentType.word()).suggests(ids)
				.then(literal("on").executes(c -> edit(c, o -> o.addProperty("once", true))))
				.then(literal("off").executes(c -> edit(c, o -> o.addProperty("once", false))))));
		tr.then(literal("enable").then(argument("id", StringArgumentType.word()).suggests(ids).executes(c -> edit(c, o -> o.addProperty("enabled", true)))));
		tr.then(literal("disable").then(argument("id", StringArgumentType.word()).suggests(ids).executes(c -> edit(c, o -> o.addProperty("enabled", false)))));
		tr.then(literal("fire").then(argument("id", StringArgumentType.word()).suggests(ids).executes(c -> {
			Trigger t = triggers.get(StringArgumentType.getString(c, "id"));
			if (t == null) {
				return Feedback.fail(c.getSource(), "Unknown trigger");
			}
			fired.remove(t.id);
			fire(t, c.getSource().getPlayerOrException());
			return Feedback.ok(c.getSource(), "Trigger fired");
		})));
		tr.then(literal("delete").then(argument("id", StringArgumentType.word()).suggests(ids).executes(c -> {
			String id = StringArgumentType.getString(c, "id");
			triggers.remove(id);
			definitions.remove(id);
			fired.remove(id);
			inside.remove(id);
			try {
				Files.deleteIfExists(directory().resolve(id + ".json"));
			} catch (IOException e) {
				LOG.error("delete failed", e);
			}
			return Feedback.ok(c.getSource(), "Trigger deleted");
		})));
		root.then(tr);
	}

	private int create(CommandContext<CommandSourceStack> c, boolean zone, Vec3 pos, double radius) {
		String id = StringArgumentType.getString(c, "id");
		if (!SceneLoader.ID_PATTERN.matcher(id).matches()) {
			return Feedback.fail(c.getSource(), "Id must match [a-z0-9_]");
		}
		JsonObject o = new JsonObject();
		o.addProperty("dimension", c.getSource().getLevel().dimension().identifier().toString());
		o.addProperty("type", zone ? "zone" : "interact");
		JsonArray p = new JsonArray();
		p.add(Math.round(pos.x * 10) / 10.0);
		p.add(Math.round(pos.y * 10) / 10.0);
		p.add(Math.round(pos.z * 10) / 10.0);
		o.add("pos", p);
		o.addProperty("radius", radius);
		o.addProperty("once", true);
		o.addProperty("cancel", true);
		o.add("actions", new JsonArray());
		put(id, o);
		if (zone) {
			showZone(c.getSource().getLevel(), triggers.get(id));
			return Feedback.ok(c.getSource(), "Zone trigger '" + id + "' created (ring of particles). It fires when someone WALKS IN: "
					+ "step out and back in to test, or /btb trigger fire " + id + ". Add actions: /btb trigger action " + id + " <command>");
		}
		return Feedback.ok(c.getSource(), "Trigger '" + id + "' created. Add actions: /btb trigger action " + id + " <command>");
	}

	private int edit(CommandContext<CommandSourceStack> c, Consumer<JsonObject> op) {
		String id = StringArgumentType.getString(c, "id");
		JsonObject cur = definitions.get(id);
		if (cur == null) {
			return Feedback.fail(c.getSource(), "Unknown trigger '" + id + "'");
		}
		JsonObject copy = cur.deepCopy();
		op.accept(copy);
		try {
			put(id, copy);
		} catch (JsonParamException e) {
			return Feedback.fail(c.getSource(), e.getMessage());
		}
		return Feedback.ok(c.getSource(), "Trigger '" + id + "' updated");
	}
}
