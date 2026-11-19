package de.behindtheblocks.cinematic.scene;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import de.behindtheblocks.cinematic.command.CommandContributor;
import de.behindtheblocks.cinematic.command.Feedback;
import de.behindtheblocks.cinematic.core.CinematicEngine;
import de.behindtheblocks.cinematic.core.CinematicModule;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Scene builder without files: create a scene, add steps at a second, move or delete them, and
 * <b>live recording</b> — {@code /btb scene rec <id>}, then direct as usual (menu clicks, typed
 * commands, cue keys); every action is stored with its exact timing. The JSON file is written for you.
 *
 * <p>Steps are stored as {@code command} events together with who triggered them and where
 * ({@code as}, {@code pos}, {@code rot}), so "~", "^" and "for me" mean the same at playback.
 */
public final class SceneEditor implements CinematicModule, CommandContributor {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/scene-editor");
	private static SceneEditor instance;

	/** Commands that manage things rather than act in the film; never recorded. */
	private static final List<String> NOT_RECORDED = List.of("btb scene", "btb director", "btb undo", "btb rueckgaengig", "btb versionen",
			"btb projekt", "btb rolle", "btb vorlage", "btb stop", "btb sprache", "btb language", "btb help", "btb events", "btb reload",
			"btb camera record", "btb camera create", "btb camera point", "btb camera remove", "btb camera duration", "btb camera easing",
			"btb camera mode", "btb camera speed", "btb camera fov", "btb camera roll", "btb camera info", "btb camera list",
			"btb camera delete", "btb camera goto", "btb camera reload", "btb hud save", "btb hud delete", "btb hud credits",
			"btb hud list", "btb hud reload", "btb hud styles", "btb portal create", "btb portal fill", "btb portal build",
			"btb portal keystone", "btb portal link", "btb portal exit", "btb portal facing", "btb portal requirekey",
			"btb portal arrival", "btb portal activationscene", "btb portal transition", "btb portal list", "btb portal info",
			"btb portal delete", "btb portal reload", "btb portal state", "btb destruction create", "btb destruction effect",
			"btb destruction duration", "btb destruction seed", "btb destruction center", "btb destruction backup",
			"btb destruction proximity", "btb destruction list", "btb destruction delete", "btb destruction reload",
			"btb parkour create", "btb parkour checkpoint", "btb parkour camera", "btb parkour follow", "btb parkour cameraman",
			"btb parkour fally", "btb parkour list", "btb parkour delete", "btb trigger zone", "btb trigger interact",
			"btb trigger action", "btb trigger clear", "btb trigger once", "btb trigger list", "btb trigger reload",
			"btb trigger delete", "btb ambience create", "btb ambience sound", "btb ambience particles", "btb ambience shake",
			"btb ambience flash", "btb ambience delete", "btb ambience list", "btb ambience reload", "btb showcase create",
			"btb showcase item", "btb showcase scale", "btb showcase pos", "btb showcase list", "btb showcase info",
			"btb showcase delete", "btb showcase reload", "btb demo", "btb resetall");
	/** Vanilla commands that make sense in a film take (navigation like /tp or /gamemode is not recorded). */
	private static final Set<String> VANILLA_RECORDED = Set.of("time", "weather", "summon", "effect", "particle", "playsound",
			"title", "setblock", "fill", "give", "kill", "say", "tellraw", "clear", "stopsound");

	private record Recording(String sceneId, long startTick) {
	}

	private final Map<UUID, Recording> recordings = new HashMap<>();
	private boolean testing;
	private CinematicEngine engine;
	private MinecraftServer server;

	public static SceneEditor instance() {
		return instance;
	}

	@Override
	public String id() {
		return "scene_editor";
	}

	@Override
	public void initialize(CinematicEngine engine) {
		this.engine = engine;
		instance = this;
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		this.server = server;
	}

	// ------------------------------------------------------------------ files

	private static Path file(String id) {
		return SceneManager.sceneDirectory().resolve(id + ".json");
	}

	/** Reads a scene as editable JSON (null if it doesn't exist as a file). */
	public JsonObject read(String id) {
		Path f = file(id);
		if (!Files.exists(f)) {
			return null;
		}
		try {
			JsonReader r = new JsonReader(new StringReader(Files.readString(f, StandardCharsets.UTF_8)));
			r.setStrictness(Strictness.LENIENT);
			return JsonParser.parseReader(r).getAsJsonObject();
		} catch (IOException | RuntimeException e) {
			LOG.warn("Scene {} unreadable: {}", id, e.getMessage());
			return null;
		}
	}

	private void write(String id, JsonObject scene) {
		// Keep the timeline in order; equal times keep their insertion order.
		JsonArray events = events(scene);
		List<JsonElement> sorted = new ArrayList<>();
		events.forEach(sorted::add);
		sorted.sort((a, b) -> Integer.compare(time(a.getAsJsonObject()), time(b.getAsJsonObject())));
		JsonArray out = new JsonArray();
		sorted.forEach(out::add);
		scene.add("events", out);
		int last = sorted.isEmpty() ? 0 : time(sorted.get(sorted.size() - 1).getAsJsonObject());
		if (!scene.has("duration") || scene.get("duration").getAsInt() <= last) {
			scene.addProperty("duration", last + 40);
		}
		try {
			Files.createDirectories(SceneManager.sceneDirectory());
			de.behindtheblocks.cinematic.studio.Versions.before(file(id));
			Files.writeString(file(id), new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(scene), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOG.error("Could not save scene {}", id, e);
		}
		engine.scenes().reload();
	}

	private static JsonArray events(JsonObject scene) {
		if (!scene.has("events") || !scene.get("events").isJsonArray()) {
			scene.add("events", new JsonArray());
		}
		return scene.getAsJsonArray("events");
	}

	private static int time(JsonObject e) {
		return e.has("time") ? e.get("time").getAsInt() : 0;
	}

	/** Short human label of an event, for chat and the Director. */
	public static String label(JsonObject e) {
		String type = e.has("type") ? e.get("type").getAsString() : "?";
		if (type.equals("command") && e.has("command")) {
			String c = e.get("command").getAsString();
			return c.startsWith("btb ") ? c.substring(4) : "/" + c;
		}
		StringBuilder b = new StringBuilder(type);
		for (String k : List.of("id", "path", "scene", "text", "sound", "entity", "style", "color")) {
			if (e.has(k) && e.get(k).isJsonPrimitive()) {
				b.append(' ').append(e.get(k).getAsString());
				break;
			}
		}
		return b.toString();
	}

	/** Event list for the Director: [{time (s), label}] per scene. */
	public JsonObject directorState(String forPlayer) {
		JsonObject all = new JsonObject();
		for (String id : engine.scenes().sceneIds()) {
			JsonObject scene = read(id);
			if (scene == null) {
				continue;
			}
			JsonArray list = new JsonArray();
			int n = 0;
			for (JsonElement el : events(scene)) {
				if (n++ >= 200) {
					break;
				}
				JsonObject e = el.getAsJsonObject();
				JsonObject o = new JsonObject();
				o.addProperty("t", time(e) / 20.0);
				String l = label(e);
				o.addProperty("label", l.length() > 80 ? l.substring(0, 79) + "…" : l);
				list.add(o);
			}
			all.add(id, list);
		}
		JsonObject o = new JsonObject();
		o.add("events", all);
		if (forPlayer != null && server != null) {
			ServerPlayer p = server.getPlayerList().getPlayerByName(forPlayer);
			Recording r = p == null ? null : recordings.get(p.getUUID());
			if (r != null) {
				o.addProperty("recording", r.sceneId);
				o.addProperty("recording_seconds", (server.getTickCount() - r.startTick) / 20.0);
			}
		}
		return o;
	}

	// ------------------------------------------------------------------ editing

	private static JsonObject commandEvent(int tick, String command, CommandSourceStack src) {
		JsonObject e = new JsonObject();
		e.addProperty("time", tick);
		e.addProperty("type", "command");
		e.addProperty("command", command.startsWith("/") ? command.substring(1) : command);
		if (src != null && src.getEntity() instanceof ServerPlayer p) {
			// Who and where: "for me", "~" and "^" mean the same when the scene plays later.
			e.addProperty("as", p.getName().getString());
			JsonArray pos = new JsonArray();
			pos.add(Math.round(src.getPosition().x * 100) / 100.0);
			pos.add(Math.round(src.getPosition().y * 100) / 100.0);
			pos.add(Math.round(src.getPosition().z * 100) / 100.0);
			e.add("pos", pos);
			JsonArray rot = new JsonArray();
			rot.add(Math.round(src.getRotation().y * 10) / 10.0);
			rot.add(Math.round(src.getRotation().x * 10) / 10.0);
			e.add("rot", rot);
		}
		return e;
	}

	public String create(String id, double seconds) {
		if (!SceneLoader.ID_PATTERN.matcher(id).matches()) {
			return "Name: only a-z, 0-9 and _";
		}
		if (Files.exists(file(id))) {
			return "Scene '" + id + "' already exists";
		}
		JsonObject s = new JsonObject();
		s.addProperty("id", id);
		s.addProperty("duration", Math.max(20, (int) Math.round(seconds * 20)));
		s.add("events", new JsonArray());
		write(id, s);
		return null;
	}

	private JsonObject require(CommandContext<CommandSourceStack> c, String id) {
		JsonObject s = read(id);
		if (s == null) {
			Feedback.fail(c.getSource(), "Scene '" + id + "' not found. New one: /btb scene create " + id);
		}
		return s;
	}

	private JsonObject event(CommandContext<CommandSourceStack> c, JsonObject scene, int nr) {
		JsonArray ev = events(scene);
		if (nr < 1 || nr > ev.size()) {
			Feedback.fail(c.getSource(), "Step " + nr + " doesn't exist (scene has " + ev.size() + ")");
			return null;
		}
		return ev.get(nr - 1).getAsJsonObject();
	}

	private int list(CommandSourceStack src, String id) {
		JsonObject scene = read(id);
		if (scene == null) {
			return Feedback.fail(src, "Scene '" + id + "' not found");
		}
		JsonArray ev = events(scene);
		Feedback.ok(src, "Scene '" + id + "' – " + ev.size() + " step(s), " + fmt(scene.get("duration").getAsInt() / 20.0) + " s");
		for (int i = 0; i < ev.size(); i++) {
			JsonObject e = ev.get(i).getAsJsonObject();
			int nr = i + 1;
			MutableComponent line = Component.literal(" ");
			line.append(button("▶", "/btb scene test " + id + " " + nr, "Diesen Schritt jetzt ausführen", ChatFormatting.GREEN));
			line.append(button("✎", "/btb scene move " + id + " " + nr + " " + fmt(time(e) / 20.0), "Zeit ändern", ChatFormatting.YELLOW, true));
			line.append(button("✕", "/btb scene remove " + id + " " + nr, "Schritt löschen", ChatFormatting.RED));
			line.append(Component.literal(" " + nr + ". ").withStyle(ChatFormatting.GRAY));
			line.append(Component.literal(fmt(time(e) / 20.0) + " s  ").withStyle(ChatFormatting.GOLD));
			line.append(Component.literal(label(e)).withStyle(ChatFormatting.WHITE));
			src.sendSuccess(() -> line, false);
		}
		return ev.size();
	}

	private static MutableComponent button(String text, String command, String hover, ChatFormatting color) {
		return button(text, command, hover, color, false);
	}

	private static MutableComponent button(String text, String command, String hover, ChatFormatting color, boolean suggest) {
		return Component.literal("[" + text + "]").withStyle(s -> s.withColor(color)
				.withClickEvent(suggest ? new ClickEvent.SuggestCommand(command) : new ClickEvent.RunCommand(command))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	static String fmt(double seconds) {
		return String.format(Locale.ROOT, "%.2f", seconds).replaceAll("0+$", "").replaceAll("\\.$", "");
	}

	// ------------------------------------------------------------------ live recording

	/** Called (via mixin) for every command a player runs. Records it if that player is recording a scene. */
	public void onPlayerCommand(ParseResults<CommandSourceStack> parsed, String command) {
		if (testing || recordings.isEmpty() || server == null || !(parsed.getContext().getSource().getEntity() instanceof ServerPlayer p)) {
			return;
		}
		Recording r = recordings.get(p.getUUID());
		if (r == null || parsed.getReader().canRead() || !parsed.getExceptions().isEmpty() || parsed.getContext().getLastChild().getCommand() == null) {
			return;
		}
		String cmd = (command.startsWith("/") ? command.substring(1) : command).trim();
		String root = cmd.split(" ", 2)[0];
		boolean btb = root.equals("btb");
		if (btb) {
			for (String prefix : NOT_RECORDED) {
				if (cmd.equals(prefix) || cmd.startsWith(prefix + " ")) {
					return;
				}
			}
		} else if (!VANILLA_RECORDED.contains(root)) {
			return;
		}
		JsonObject scene = read(r.sceneId);
		if (scene == null) {
			return;
		}
		int tick = (int) (server.getTickCount() - r.startTick);
		events(scene).add(commandEvent(tick, cmd, parsed.getContext().getSource()));
		write(r.sceneId, scene);
		p.sendSystemMessage(Component.literal("● " + fmt(tick / 20.0) + " s  ").withStyle(ChatFormatting.RED)
				.append(Component.literal(cmd.startsWith("btb ") ? cmd.substring(4) : "/" + cmd).withStyle(ChatFormatting.GRAY)), true);
	}

	@Override
	public void tick(MinecraftServer server) {
		if (recordings.isEmpty() || server.getTickCount() % 10 != 0) {
			return;
		}
		for (Map.Entry<UUID, Recording> e : recordings.entrySet()) {
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p != null) {
				double s = (server.getTickCount() - e.getValue().startTick) / 20.0;
				p.sendSystemMessage(Component.literal("● REC  " + e.getValue().sceneId + "  " + fmt(Math.floor(s * 2) / 2) + " s")
						.withStyle(ChatFormatting.RED), true);
			}
		}
	}

	// ------------------------------------------------------------------ commands

	@Override
	public void contributeCommands(LiteralArgumentBuilder<CommandSourceStack> root, CommandBuildContext buildContext) {
		SuggestionProvider<CommandSourceStack> scenes = (c, b) -> SharedSuggestionProvider.suggest(engine.scenes().sceneIds(), b);
		root.then(literal("scene")
				.then(literal("create").then(argument("id", StringArgumentType.word())
						.executes(c -> createCmd(c, 30))
						.then(argument("seconds", DoubleArgumentType.doubleArg(1, 7200))
								.executes(c -> createCmd(c, DoubleArgumentType.getDouble(c, "seconds"))))))
				.then(literal("add").then(argument("id", StringArgumentType.word()).suggests(scenes)
						.then(argument("second", DoubleArgumentType.doubleArg(0, 7200))
								.then(argument("command", StringArgumentType.greedyString()).executes(c -> {
									String id = StringArgumentType.getString(c, "id");
									JsonObject s = read(id);
									if (s == null) {
										if (create(id, 30) != null) {
											return Feedback.fail(c.getSource(), "Name: only a-z, 0-9 and _");
										}
										s = read(id);
									}
									int tick = (int) Math.round(DoubleArgumentType.getDouble(c, "second") * 20);
									String cmd = StringArgumentType.getString(c, "command");
									String check = validate(c.getSource(), cmd);
									if (check != null) {
										return Feedback.fail(c.getSource(), check);
									}
									events(s).add(commandEvent(tick, cmd, c.getSource()));
									write(id, s);
									return Feedback.ok(c.getSource(), "Added at " + fmt(tick / 20.0) + " s: " + cmd + "  (/btb scene events " + id + ")");
								})))))
				.then(literal("events").then(argument("id", StringArgumentType.word()).suggests(scenes)
						.executes(c -> list(c.getSource(), StringArgumentType.getString(c, "id")))))
				.then(literal("remove").then(argument("id", StringArgumentType.word()).suggests(scenes)
						.then(argument("nr", IntegerArgumentType.integer(1)).executes(c -> {
							String id = StringArgumentType.getString(c, "id");
							JsonObject s = require(c, id);
							if (s == null || event(c, s, IntegerArgumentType.getInteger(c, "nr")) == null) {
								return 0;
							}
							JsonElement removed = events(s).remove(IntegerArgumentType.getInteger(c, "nr") - 1);
							write(id, s);
							Feedback.ok(c.getSource(), "Removed: " + label(removed.getAsJsonObject()));
							return list(c.getSource(), id);
						}))))
				.then(literal("move").then(argument("id", StringArgumentType.word()).suggests(scenes)
						.then(argument("nr", IntegerArgumentType.integer(1))
								.then(argument("second", DoubleArgumentType.doubleArg(0, 7200)).executes(c -> {
									String id = StringArgumentType.getString(c, "id");
									JsonObject s = require(c, id);
									JsonObject e = s == null ? null : event(c, s, IntegerArgumentType.getInteger(c, "nr"));
									if (e == null) {
										return 0;
									}
									e.addProperty("time", (int) Math.round(DoubleArgumentType.getDouble(c, "second") * 20));
									write(id, s);
									return list(c.getSource(), id);
								})))))
				.then(literal("shift").then(argument("id", StringArgumentType.word()).suggests(scenes)
						.then(argument("nr", IntegerArgumentType.integer(1))
								.then(argument("seconds", DoubleArgumentType.doubleArg(-7200, 7200)).executes(c -> {
									String id = StringArgumentType.getString(c, "id");
									JsonObject s = require(c, id);
									JsonObject e = s == null ? null : event(c, s, IntegerArgumentType.getInteger(c, "nr"));
									if (e == null) {
										return 0;
									}
									e.addProperty("time", Math.max(0, time(e) + (int) Math.round(DoubleArgumentType.getDouble(c, "seconds") * 20)));
									write(id, s);
									return Feedback.ok(c.getSource(), "Step " + IntegerArgumentType.getInteger(c, "nr") + " now at " + fmt(time(e) / 20.0) + " s");
								})))))
				.then(literal("test").then(argument("id", StringArgumentType.word()).suggests(scenes)
						.then(argument("nr", IntegerArgumentType.integer(1)).executes(c -> {
							JsonObject s = require(c, StringArgumentType.getString(c, "id"));
							JsonObject e = s == null ? null : event(c, s, IntegerArgumentType.getInteger(c, "nr"));
							if (e == null) {
								return 0;
							}
							if (!e.has("command")) {
								return Feedback.fail(c.getSource(), "Only command steps can be tested alone – play the scene");
							}
							testing = true;
							try {
								server.getCommands().performPrefixedCommand(c.getSource(), e.get("command").getAsString());
							} finally {
								testing = false;
							}
							return 1;
						}))))
				.then(literal("duration").then(argument("id", StringArgumentType.word()).suggests(scenes)
						.then(argument("seconds", DoubleArgumentType.doubleArg(1, 7200)).executes(c -> {
							String id = StringArgumentType.getString(c, "id");
							JsonObject s = require(c, id);
							if (s == null) {
								return 0;
							}
							s.addProperty("duration", (int) Math.round(DoubleArgumentType.getDouble(c, "seconds") * 20));
							write(id, s);
							return Feedback.ok(c.getSource(), "Scene '" + id + "' is now " + fmt(DoubleArgumentType.getDouble(c, "seconds")) + " s long");
						}))))
				.then(literal("loop").then(argument("id", StringArgumentType.word()).suggests(scenes)
						.then(literal("on").executes(c -> setFlag(c, "loop", true)))
						.then(literal("off").executes(c -> setFlag(c, "loop", false)))))
				.then(literal("copy").then(argument("id", StringArgumentType.word()).suggests(scenes)
						.then(argument("to", StringArgumentType.word()).executes(c -> {
							JsonObject s = require(c, StringArgumentType.getString(c, "id"));
							String to = StringArgumentType.getString(c, "to");
							if (s == null) {
								return 0;
							}
							if (!SceneLoader.ID_PATTERN.matcher(to).matches() || Files.exists(file(to))) {
								return Feedback.fail(c.getSource(), "Target name invalid or already taken");
							}
							s.addProperty("id", to);
							write(to, s);
							return Feedback.ok(c.getSource(), "Copied to '" + to + "'");
						}))))
				.then(literal("delete").then(argument("id", StringArgumentType.word()).suggests(scenes).executes(c -> {
					String id = StringArgumentType.getString(c, "id");
					engine.scenes().stop(id);
					try {
						de.behindtheblocks.cinematic.studio.Versions.before(file(id));
						if (!Files.deleteIfExists(file(id))) {
							return Feedback.fail(c.getSource(), "Scene '" + id + "' not found");
						}
					} catch (IOException e) {
						return Feedback.fail(c.getSource(), e.getMessage());
					}
					engine.scenes().reload();
					return Feedback.ok(c.getSource(), "Scene '" + id + "' deleted");
				})))
				.then(literal("rec")
						.then(literal("stop").executes(c -> {
							ServerPlayer p = c.getSource().getPlayerOrException();
							Recording r = recordings.remove(p.getUUID());
							if (r == null) {
								return Feedback.fail(c.getSource(), "No scene recording running");
							}
							p.sendSystemMessage(Component.empty(), true);
							Feedback.ok(c.getSource(), "Recording stopped. Play: /btb scene restart " + r.sceneId);
							return list(c.getSource(), r.sceneId);
						}))
						.then(argument("id", StringArgumentType.word()).suggests(scenes).executes(c -> {
							ServerPlayer p = c.getSource().getPlayerOrException();
							String id = StringArgumentType.getString(c, "id");
							if (read(id) == null) {
								String err = create(id, 30);
								if (err != null) {
									return Feedback.fail(c.getSource(), err);
								}
							}
							recordings.put(p.getUUID(), new Recording(id, server.getTickCount()));
							return Feedback.ok(c.getSource(), "● Recording into '" + id + "' from now on: every click in the Director and every /btb command "
									+ "lands in the scene at its exact time. Stop: /btb scene rec stop");
						}))));
	}

	private int createCmd(CommandContext<CommandSourceStack> c, double seconds) {
		String id = StringArgumentType.getString(c, "id");
		String err = create(id, seconds);
		return err == null ? Feedback.ok(c.getSource(), "Scene '" + id + "' created. Add steps: /btb scene add " + id + " <second> <command>, "
				+ "or record live: /btb scene rec " + id) : Feedback.fail(c.getSource(), err);
	}

	private int setFlag(CommandContext<CommandSourceStack> c, String key, boolean value) {
		String id = StringArgumentType.getString(c, "id");
		JsonObject s = require(c, id);
		if (s == null) {
			return 0;
		}
		s.addProperty(key, value);
		write(id, s);
		return Feedback.ok(c.getSource(), "'" + id + "': " + key + " " + (value ? "on" : "off"));
	}

	/** Syntax check of a step before it is stored, so typos surface while building, not on set. */
	private String validate(CommandSourceStack src, String command) {
		String cmd = command.startsWith("/") ? command.substring(1) : command;
		ParseResults<CommandSourceStack> parsed = server.getCommands().getDispatcher().parse(cmd, src);
		if (parsed.getReader().canRead() || parsed.getContext().getLastChild().getCommand() == null) {
			var ex = server.getCommands().getDispatcher().parse(cmd, src).getExceptions();
			return "Command not understood: /" + cmd + (ex.isEmpty() ? "" : " – " + ex.values().iterator().next().getMessage());
		}
		return null;
	}
}
