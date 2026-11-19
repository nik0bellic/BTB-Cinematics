package de.behindtheblocks.cinematic.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import de.behindtheblocks.cinematic.BtbCinematic;
import de.behindtheblocks.cinematic.command.CommandContributor;
import de.behindtheblocks.cinematic.command.CommandUtil;
import de.behindtheblocks.cinematic.command.Feedback;
import de.behindtheblocks.cinematic.core.CinematicEngine;
import de.behindtheblocks.cinematic.core.CinematicModule;
import de.behindtheblocks.cinematic.core.bus.SceneMessages;
import de.behindtheblocks.cinematic.event.builtin.BuiltinEvents;
import de.behindtheblocks.cinematic.event.builtin.PlayerTargets;
import de.behindtheblocks.cinematic.json.JsonParamException;
import de.behindtheblocks.cinematic.json.JsonParams;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Cinematic HUD texts, server side. Text definitions live in
 * {@code config/btb_cinematic/hud/<id>.json} (see {@link HudTextSpec}); the full definition is sent
 * with the cue, so clients need no files. HUD texts go to all players by default.
 */
public final class HudManager implements CinematicModule, CommandContributor {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/hud");

	private final Map<String, JsonObject> definitions = new TreeMap<>();
	private CinematicEngine engine;

	@Override
	public String id() {
		return "hud";
	}

	public static Path directory() {
		return FabricLoader.getInstance().getConfigDir().resolve(BtbCinematic.MOD_ID).resolve("hud");
	}

	@Override
	public void initialize(CinematicEngine engine) {
		this.engine = engine;
		engine.bus().subscribe(SceneMessages.Reset.class, m -> hide(m.sceneId(), 0, null, 10, null));
		engine.events().register(BuiltinEvents.simple("hud_show",
				"Shows a HUD text. {id (from config/btb_cinematic/hud) | text/lines + style fields, players?}",
				p -> {
					String id = p.has("id") && !p.has("text") && !p.has("lines") ? p.requireString("id") : null;
					JsonObject inline = null;
					if (id == null) {
						inline = p.raw().deepCopy();
						inline.remove("time");
						inline.remove("type");
						inline.remove("players");
						if (!inline.has("id")) {
							inline.addProperty("id", "inline_" + Integer.toHexString(inline.hashCode()));
						}
						HudTextSpec.fromJson("inline", inline); // validate now
					}
					JsonObject finalInline = inline;
					PlayerTargets t = PlayerTargets.parse(p);
					return ctx -> {
						JsonObject def = finalInline != null ? finalInline : definitions.get(id);
						if (def == null) {
							throw new IllegalStateException("HUD text '" + id + "' does not exist");
						}
						show(ctx.sceneId(), ctx.tick(), def, t.all() ? null : t.resolve(ctx.server()));
					};
				}));
		engine.events().register(BuiltinEvents.simple("hud_hide",
				"Fades out HUD text. {id? (omit = all), out? ticks, players?}",
				p -> {
					String id = p.has("id") ? p.requireString("id") : null;
					int out = p.getInt("out", 20);
					PlayerTargets t = PlayerTargets.parse(p);
					return ctx -> hide(ctx.sceneId(), ctx.tick(), id, out, t.all() ? null : t.resolve(ctx.server()));
				}));
		engine.events().register(BuiltinEvents.simple("subtitle",
				"Quick subtitle at the bottom. {text, duration? ticks (default 60), players?}",
				p -> {
					JsonObject def = subtitle(p.requireString("text"), p.getInt("duration", 60));
					HudTextSpec.fromJson("subtitle", def);
					PlayerTargets t = PlayerTargets.parse(p);
					return ctx -> show(ctx.sceneId(), ctx.tick(), def, t.all() ? null : t.resolve(ctx.server()));
				}));
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		installBundled();
		reload();
	}

	private void installBundled() {
		try {
			Files.createDirectories(directory());
			var bundled = FabricLoader.getInstance().getModContainer(BtbCinematic.MOD_ID)
					.flatMap(m -> m.findPath("btb_cinematic/default_hud"));
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
			LOG.error("Cannot prepare HUD directory", e);
		}
	}

	/** @return number of loaded definitions */
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
					if (!json.has("id")) {
						json.addProperty("id", id);
					}
					HudTextSpec.fromJson(id, json);
					definitions.put(id, json);
				} catch (RuntimeException | IOException e) {
					LOG.warn("HUD text {} invalid: {}", name, e.getMessage());
				}
			}
		} catch (IOException e) {
			LOG.error("Cannot list HUD texts", e);
		}
		LOG.info("Loaded {} HUD text(s)", definitions.size());
		return definitions.size();
	}

	public Collection<String> ids() {
		return definitions.keySet();
	}

	/** Shows a stored text to everyone; returns false if the id is unknown. */
	public boolean showById(String sceneId, String id) {
		JsonObject def = definitions.get(id);
		if (def == null) {
			return false;
		}
		show(sceneId, 0, def, null);
		return true;
	}

	public void show(String sceneId, int tick, JsonObject definition, Collection<ServerPlayer> players) {
		JsonObject cue = new JsonObject();
		cue.add("spec", definition);
		engine.cues().queue(sceneId, tick, "hud_show", cue, players);
	}

	/** @param id text id or null for all */
	public void hide(String sceneId, int tick, String id, int outTicks, Collection<ServerPlayer> players) {
		JsonObject cue = new JsonObject();
		if (id != null) {
			cue.addProperty("id", id);
		}
		cue.addProperty("out", outTicks);
		engine.cues().queue(sceneId, tick, "hud_hide", cue, players);
	}

	static JsonObject subtitle(String text, int ticks) {
		JsonObject o = new JsonObject();
		o.addProperty("id", "subtitle");
		o.addProperty("text", text);
		o.addProperty("font", "default");
		o.addProperty("scale", 1.5);
		o.addProperty("anchor", "bottom");
		var off = new com.google.gson.JsonArray();
		off.add(0);
		off.add(-40);
		o.add("offset", off);
		o.addProperty("animation_in", "fade");
		o.addProperty("in", 6);
		o.addProperty("hold", Math.max(1, ticks - 12));
		o.addProperty("out", 6);
		o.addProperty("background", "#90000000");
		o.addProperty("padding", 4);
		return o;
	}

	static JsonObject title(String text, int ticks) {
		JsonObject o = new JsonObject();
		o.addProperty("id", "title");
		o.addProperty("text", text.replace("\\n", "\n"));
		o.addProperty("font", "cinematic");
		o.addProperty("scale", 3);
		o.addProperty("letter_spacing", 2);
		o.addProperty("animation_in", "tracking");
		o.addProperty("in", 30);
		o.addProperty("hold", Math.max(1, ticks - 60));
		o.addProperty("out", 30);
		return o;
	}

	// ------------------------------------------------------------------ commands

	@Override
	public void contributeCommands(LiteralArgumentBuilder<CommandSourceStack> root, CommandBuildContext buildContext) {
		SuggestionProvider<CommandSourceStack> ids = (c, b) -> SharedSuggestionProvider.suggest(definitions.keySet(), b);
		SuggestionProvider<CommandSourceStack> styles = (c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(HudQuickStyles.STYLES.keySet(), b);
		root.then(literal("hud")
				.then(literal("list").executes(c -> {
					Feedback.ok(c.getSource(), definitions.size() + " HUD text(s): " + String.join(", ", definitions.keySet()));
					return definitions.size();
				}))
				.then(literal("reload").executes(c -> Feedback.ok(c.getSource(), "Loaded " + reload() + " HUD text(s)")))
				.then(literal("show").then(argument("id", StringArgumentType.word()).suggests(ids)
						.executes(c -> showCmd(c, false))
						.then(argument("targets", EntityArgument.players()).executes(c -> showCmd(c, true)))))
				.then(literal("hide")
						.executes(c -> {
							hide("", 0, null, 20, null);
							return Feedback.ok(c.getSource(), "HUD texts hidden");
						})
						.then(argument("id", StringArgumentType.word()).suggests(ids).executes(c -> {
							hide("", 0, StringArgumentType.getString(c, "id"), 20, null);
							return Feedback.ok(c.getSource(), "HUD text hidden");
						})))
				.then(literal("subtitle").then(argument("seconds", DoubleArgumentType.doubleArg(0.5, 600))
						.then(argument("text", StringArgumentType.greedyString()).executes(c -> quick(c, true)))))
				.then(literal("title").then(argument("seconds", DoubleArgumentType.doubleArg(1, 600))
						.then(argument("text", StringArgumentType.greedyString()).executes(c -> quick(c, false)))))
				// Texts straight from chat: /btb hud text boss 5 DER WÄCHTER | Hüter des Herzens
				.then(literal("text").then(argument("style", StringArgumentType.word()).suggests(styles)
						.then(argument("seconds", DoubleArgumentType.doubleArg(0, 600))
								.then(argument("text", StringArgumentType.greedyString()).executes(this::textCmd)))))
				// …and keep it as a reusable preset (written to config for you)
				.then(literal("save").then(argument("id", StringArgumentType.word())
						.then(argument("style", StringArgumentType.word()).suggests(styles)
								.then(argument("seconds", DoubleArgumentType.doubleArg(0, 600))
										.then(argument("text", StringArgumentType.greedyString()).executes(this::saveCmd))))))
				.then(literal("delete").then(argument("id", StringArgumentType.word()).suggests(ids).executes(c -> {
					String id = StringArgumentType.getString(c, "id");
					if (definitions.remove(id) == null) {
						return Feedback.fail(c.getSource(), "Unknown HUD text '" + id + "'");
					}
					try {
						Files.deleteIfExists(directory().resolve(id + ".json"));
					} catch (IOException e) {
						LOG.error("delete failed", e);
					}
					return Feedback.ok(c.getSource(), "HUD text '" + id + "' deleted");
				})))
				.then(literal("styles").executes(c -> {
					HudQuickStyles.STYLES.forEach((k, v) -> Feedback.info(c.getSource(), k + " – " + v));
					return HudQuickStyles.STYLES.size();
				}))
				.then(creditsCommands(ids)));
	}

	private int textCmd(CommandContext<CommandSourceStack> c) {
		try {
			JsonObject def = HudQuickStyles.build(StringArgumentType.getString(c, "style"),
					DoubleArgumentType.getDouble(c, "seconds"), StringArgumentType.getString(c, "text"));
			HudTextSpec.fromJson("quick", def);
			show("", 0, def, null);
			return Feedback.ok(c.getSource(), "Text shown (hide: /btb hud hide " + def.get("id").getAsString() + ")");
		} catch (JsonParamException e) {
			return Feedback.fail(c.getSource(), e.getMessage());
		}
	}

	private int saveCmd(CommandContext<CommandSourceStack> c) {
		String id = StringArgumentType.getString(c, "id");
		if (!de.behindtheblocks.cinematic.scene.SceneLoader.ID_PATTERN.matcher(id).matches()) {
			return Feedback.fail(c.getSource(), "Id: only a-z, 0-9 and _");
		}
		try {
			JsonObject def = HudQuickStyles.build(StringArgumentType.getString(c, "style"),
					DoubleArgumentType.getDouble(c, "seconds"), StringArgumentType.getString(c, "text"));
			def.addProperty("id", id);
			HudTextSpec.fromJson(id, def);
			put(id, def);
			show("", 0, def, null);
			return Feedback.ok(c.getSource(), "Saved as '" + id + "' – show again: /btb hud show " + id);
		} catch (JsonParamException e) {
			return Feedback.fail(c.getSource(), e.getMessage());
		}
	}

	/** Stores a definition and writes it to the config folder (nobody has to touch the file). */
	public void put(String id, JsonObject def) {
		definitions.put(id, def);
		try {
			Files.createDirectories(directory());
			Files.writeString(directory().resolve(id + ".json"),
					new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(def), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOG.error("Could not save HUD text {}", id, e);
		}
	}

	public JsonObject definition(String id) {
		return definitions.get(id);
	}

	// ------------------------------------------------------------------ credits builder

	private LiteralArgumentBuilder<CommandSourceStack> creditsCommands(com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> ids) {
		var kinds = (com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack>) (c, b) ->
				net.minecraft.commands.SharedSuggestionProvider.suggest(java.util.List.of("vorspann", "abspann", "endkarte"), b);
		var icons = (com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack>) (c, b) ->
				net.minecraft.commands.SharedSuggestionProvider.suggest(HudTextSpec.ICONS, b);
		return literal("credits")
				// /btb hud credits <id> new vorspann|abspann|endkarte [titel]
				.then(argument("id", StringArgumentType.word()).suggests(ids)
						.then(literal("new").then(argument("kind", StringArgumentType.word()).suggests(kinds)
								.executes(c -> creditsNew(c, ""))
								.then(argument("title", StringArgumentType.greedyString())
										.executes(c -> creditsNew(c, StringArgumentType.getString(c, "title"))))))
						// add Rolle | Name | icon | link   (icon/link optional)
						.then(literal("add").then(argument("entry", StringArgumentType.greedyString()).executes(c -> creditsEdit(c, o -> {
							String[] f = StringArgumentType.getString(c, "entry").split("\\|", -1);
							JsonObject e = new JsonObject();
							e.addProperty("role", f[0].trim());
							e.addProperty("name", f.length > 1 ? f[1].trim() : "");
							if (f.length > 2 && !f[2].isBlank()) {
								String icon = f[2].trim().toLowerCase(java.util.Locale.ROOT);
								if (!HudTextSpec.ICONS.contains(icon)) {
									throw new JsonParamException("Unknown icon '" + icon + "'. Icons: " + String.join(", ", HudTextSpec.ICONS));
								}
								e.addProperty("icon", icon);
							}
							if (f.length > 3 && !f[3].isBlank()) {
								e.addProperty("link", f[3].trim());
							}
							entries(o).add(e);
						}))))
						.then(literal("heading").then(argument("text", StringArgumentType.greedyString()).executes(c -> creditsEdit(c, o -> {
							JsonObject e = new JsonObject();
							e.addProperty("heading", StringArgumentType.getString(c, "text"));
							entries(o).add(e);
						}))))
						.then(literal("link").then(argument("icon", StringArgumentType.word()).suggests(icons)
								.then(argument("text", StringArgumentType.greedyString()).executes(c -> creditsEdit(c, o -> {
									String icon = StringArgumentType.getString(c, "icon").toLowerCase(java.util.Locale.ROOT);
									if (!HudTextSpec.ICONS.contains(icon)) {
										throw new JsonParamException("Unknown icon '" + icon + "'");
									}
									JsonObject e = new JsonObject();
									e.addProperty("icon", icon);
									e.addProperty("link", StringArgumentType.getString(c, "text"));
									entries(o).add(e);
								})))))
						.then(literal("title").then(argument("text", StringArgumentType.greedyString()).executes(c -> creditsEdit(c, o -> {
							String t = StringArgumentType.getString(c, "text");
							if (o.has("lines")) {
								String[] p = t.split("\\|");
								JsonArray a = new JsonArray();
								for (String x : p) {
									a.add(x.trim());
								}
								o.add("lines", a);
							} else {
								o.addProperty("title", t);
							}
						}))))
						.then(literal("remove").then(argument("nr", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 500))
								.executes(c -> creditsEdit(c, o -> {
									int nr = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "nr");
									JsonArray a = entries(o);
									if (nr > a.size()) {
										throw new JsonParamException("Only " + a.size() + " entries");
									}
									a.remove(nr - 1);
								}))))
						.then(literal("clear").executes(c -> creditsEdit(c, o -> o.add("entries", new JsonArray()))))
						// edit <nr> Rolle | Name | icon | link   (or "## Überschrift")
						.then(literal("edit").then(argument("nr", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 500))
								.then(argument("entry", StringArgumentType.greedyString()).executes(c -> creditsEdit(c, o -> {
									int nr = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "nr");
									JsonArray a = entries(o);
									if (nr > a.size()) {
										throw new JsonParamException("Only " + a.size() + " entries");
									}
									a.set(nr - 1, parseEntry(StringArgumentType.getString(c, "entry")));
								})))))
						// move <nr> up|down
						.then(literal("move").then(argument("nr", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 500))
								.then(argument("dir", StringArgumentType.word()).suggests((c, b) ->
										net.minecraft.commands.SharedSuggestionProvider.suggest(java.util.List.of("up", "down"), b))
										.executes(c -> creditsEdit(c, o -> {
											int i = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "nr") - 1;
											int j = StringArgumentType.getString(c, "dir").equals("up") ? i - 1 : i + 1;
											JsonArray a = entries(o);
											if (i >= a.size() || j < 0 || j >= a.size()) {
												return;
											}
											var tmp = a.get(i);
											a.set(i, a.get(j));
											a.set(j, tmp);
										})))))
						// tempo <x>: Abspann = scroll speed (default 0.6), Vorspann = seconds per entry (default 3.5)
						.then(literal("tempo").then(argument("value", com.mojang.brigadier.arguments.FloatArgumentType.floatArg(0.05f, 30f))
								.executes(c -> creditsEdit(c, o -> {
									float v = com.mojang.brigadier.arguments.FloatArgumentType.getFloat(c, "value");
									if ("roll".equals(o.has("style") ? o.get("style").getAsString() : "")) {
										o.addProperty("roll_speed", Math.min(10f, v));
									} else {
										o.addProperty("entry_ticks", Math.max(10, Math.round(v * 20)));
									}
								}))))
						.then(literal("list").executes(c -> {
							JsonObject o = definitions.get(StringArgumentType.getString(c, "id"));
							if (o == null) {
								return Feedback.fail(c.getSource(), "Unknown HUD text");
							}
							int i = 1;
							for (var el : entries(o)) {
								Feedback.info(c.getSource(), (i++) + ". " + describe(el.getAsJsonObject()));
							}
							return i - 1;
						})));
	}

	/** "Rolle | Name | icon | link" or "## Überschrift" → entry. */
	private static JsonObject parseEntry(String text) {
		JsonObject e = new JsonObject();
		if (text.trim().startsWith("##")) {
			e.addProperty("heading", text.trim().substring(2).trim());
			return e;
		}
		String[] f = text.split("\\|", -1);
		e.addProperty("role", f[0].trim());
		e.addProperty("name", f.length > 1 ? f[1].trim() : "");
		if (f.length > 2 && !f[2].isBlank()) {
			String icon = f[2].trim().toLowerCase(java.util.Locale.ROOT);
			if (!HudTextSpec.ICONS.contains(icon)) {
				throw new JsonParamException("Unknown icon '" + icon + "'. Icons: " + String.join(", ", HudTextSpec.ICONS));
			}
			e.addProperty("icon", icon);
		}
		if (f.length > 3 && !f[3].isBlank()) {
			e.addProperty("link", f[3].trim());
		}
		return e;
	}

	public static String describe(JsonObject e) {
		if (e.has("heading")) {
			return "## " + e.get("heading").getAsString();
		}
		StringBuilder b = new StringBuilder();
		if (e.has("role")) {
			b.append(e.get("role").getAsString());
		}
		if (e.has("name") && !e.get("name").getAsString().isEmpty()) {
			b.append(b.isEmpty() ? "" : " – ").append(e.get("name").getAsString());
		}
		if (e.has("icon")) {
			b.append(" [").append(e.get("icon").getAsString()).append("]");
		}
		if (e.has("link")) {
			b.append(" ").append(e.get("link").getAsString());
		}
		return b.toString();
	}

	private static JsonArray entries(JsonObject o) {
		if (!o.has("entries")) {
			o.add("entries", new JsonArray());
		}
		return o.getAsJsonArray("entries");
	}

	private int creditsNew(CommandContext<CommandSourceStack> c, String title) {
		String id = StringArgumentType.getString(c, "id");
		if (!de.behindtheblocks.cinematic.scene.SceneLoader.ID_PATTERN.matcher(id).matches()) {
			return Feedback.fail(c.getSource(), "Id: only a-z, 0-9 and _");
		}
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("accent", "#FF7A1A");
		switch (StringArgumentType.getString(c, "kind")) {
			case "vorspann" -> {
				o.addProperty("style", "credits");
				o.addProperty("anchor", "bottom_left");
				o.addProperty("scale", 2.2);
				o.addProperty("entry_ticks", 70);
				o.addProperty("in", 10);
				o.addProperty("out", 20);
				if (!title.isEmpty()) {
					JsonObject h = new JsonObject();
					h.addProperty("heading", title);
					entries(o).add(h);
				}
			}
			case "abspann" -> {
				o.addProperty("style", "roll");
				o.addProperty("title", title.isEmpty() ? "ENDE" : title);
				o.addProperty("roll_speed", 0.7);
				o.addProperty("in", 1);
				o.addProperty("out", 10);
			}
			case "endkarte" -> {
				o.addProperty("style", "end_card");
				JsonArray l = new JsonArray();
				for (String x : (title.isEmpty() ? "TO BE CONTINUED" : title).split("\\|")) {
					l.add(x.trim());
				}
				o.add("lines", l);
				o.addProperty("scale", 3.2);
				o.addProperty("in", 60);
				o.addProperty("hold", -1);
				o.addProperty("out", 30);
			}
			default -> {
				return Feedback.fail(c.getSource(), "Kind: vorspann, abspann or endkarte");
			}
		}
		entries(o);
		put(id, o);
		return Feedback.ok(c.getSource(), "Created '" + id + "'. Add entries: /btb hud credits " + id + " add Rolle | Name | youtube | link");
	}

	private int creditsEdit(CommandContext<CommandSourceStack> c, java.util.function.Consumer<JsonObject> edit) {
		String id = StringArgumentType.getString(c, "id");
		JsonObject o = definitions.get(id);
		if (o == null) {
			return Feedback.fail(c.getSource(), "Unknown HUD text '" + id + "'. Create it: /btb hud credits " + id + " new vorspann");
		}
		JsonObject copy = o.deepCopy();
		try {
			edit.accept(copy);
			HudTextSpec.fromJson(id, copy);
		} catch (JsonParamException e) {
			return Feedback.fail(c.getSource(), e.getMessage());
		}
		put(id, copy);
		return Feedback.ok(c.getSource(), "'" + id + "' updated (" + entries(copy).size() + " entries) – preview: /btb hud show " + id);
	}

	private int showCmd(CommandContext<CommandSourceStack> c, boolean hasTargets) throws CommandSyntaxException {
		String id = StringArgumentType.getString(c, "id");
		JsonObject def = definitions.get(id);
		if (def == null) {
			return Feedback.fail(c.getSource(), "Unknown HUD text '" + id + "'");
		}
		show("", 0, def, hasTargets ? CommandUtil.targets(c, true) : null);
		return Feedback.ok(c.getSource(), "Showing '" + id + "'");
	}

	private int quick(CommandContext<CommandSourceStack> c, boolean subtitle) {
		String text = StringArgumentType.getString(c, "text");
		int ticks = CommandUtil.ticks(DoubleArgumentType.getDouble(c, "seconds"));
		JsonObject def = subtitle ? subtitle(text, ticks) : title(text, ticks);
		try {
			HudTextSpec.fromJson("quick", def);
		} catch (JsonParamException e) {
			return Feedback.fail(c.getSource(), e.getMessage());
		}
		show("", 0, def, null);
		return Feedback.ok(c.getSource(), subtitle ? "Subtitle shown" : "Title shown");
	}

	/** Exposed for other modules (showcase name cards). */
	public static HudTextSpec validate(JsonObject def) {
		return HudTextSpec.fromJson(new JsonParams(def).getString("id", "text"), def);
	}
}
