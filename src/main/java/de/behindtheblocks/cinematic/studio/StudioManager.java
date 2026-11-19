package de.behindtheblocks.cinematic.studio;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import de.behindtheblocks.cinematic.command.CommandContributor;
import de.behindtheblocks.cinematic.command.Feedback;
import de.behindtheblocks.cinematic.core.CinematicEngine;
import de.behindtheblocks.cinematic.core.CinematicModule;
import de.behindtheblocks.cinematic.hud.HudManager;
import de.behindtheblocks.cinematic.hud.HudQuickStyles;
import de.behindtheblocks.cinematic.timeline.TimelineManager;
import de.behindtheblocks.cinematic.util.I18n;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * The "studio" tools on top of the engine:
 * <ul>
 *   <li>{@code /btb stop} – stop everything that runs (without resetting the set)</li>
 *   <li>{@code /btb undo} · {@code /btb versionen …} – undo and automatic versions of every saved file</li>
 *   <li>{@code /btb projekt export|import|liste} – a whole film project as one zip</li>
 *   <li>{@code /btb vorlage …} – ready-made shots (establishing, chase, shot/reverse shot, reveal …)</li>
 *   <li>{@code /btb welt zeit|wetter} – time of day and weather as smooth keyframes</li>
 *   <li>{@code /btb gruppe …} – crowds of extras that walk, cheer, flee as one</li>
 *   <li>{@code /btb dialog …} – dialog/subtitle track (German + English), .srt export, talking animation</li>
 *   <li>{@code /btb rolle …} · {@code /btb hinweis …} – crew roles and a prompter for the actors</li>
 * </ul>
 */
public final class StudioManager implements CinematicModule, CommandContributor {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/studio");
	public static final String GROUP_TAG = "btb_gruppe";
	private static StudioManager instance;

	private CinematicEngine engine;
	private MinecraftServer server;

	// time of day / weather transitions
	private long timeFrom, timeTo;
	private int timeTick, timeTicks;

	// crowds: group name → members
	private final Map<String, List<UUID>> groups = new LinkedHashMap<>();
	private final Map<UUID, Integer> cheering = new HashMap<>();

	// dialog playback
	private record Line(double t, double dur, String speaker, String de, String en) {
	}

	private record Talk(String speaker, int until) {
	}

	private final List<Object[]> dialogRuns = new ArrayList<>(); // {id, lines, tick}
	private final List<Talk> talking = new ArrayList<>();
	private int serverTick;

	public static StudioManager instance() {
		return instance;
	}

	@Override
	public String id() {
		return "studio";
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

	@Override
	public void onServerStopping(MinecraftServer server) {
		this.server = null;
		groups.clear();
		dialogRuns.clear();
		talking.clear();
	}

	// ------------------------------------------------------------------ helpers

	private void run(String command) {
		if (server != null) {
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
		}
	}

	private static Path config() {
		return Versions.configRoot();
	}

	public static Path dialogDir() {
		return config().resolve("dialog");
	}

	public static Path projectDir() {
		return config().resolve("projekte");
	}

	// ------------------------------------------------------------------ commands

	@Override
	public void contributeCommands(LiteralArgumentBuilder<CommandSourceStack> root, CommandBuildContext ctx) {
		root.then(literal("stop").executes(c -> stopAll(c.getSource())));
		root.then(literal("undo").executes(c -> undo(c.getSource())));
		root.then(literal("rueckgaengig").executes(c -> undo(c.getSource())));
		SuggestionProvider<CommandSourceStack> versionKeys = (c, b) -> SharedSuggestionProvider.suggest(
				Versions.keys().stream().map(k -> k.replace('/', '.')).toList(), b);
		root.then(literal("versionen")
				.executes(c -> {
					List<String> keys = Versions.keys();
					if (keys.isEmpty()) {
						return Feedback.ok(c.getSource(), "Noch keine Versionen – sie entstehen bei jeder Änderung von selbst");
					}
					return Feedback.ok(c.getSource(), "Versionen: " + String.join(", ", keys.stream().limit(20).map(k -> k.replace('/', '.')).toList()));
				})
				.then(argument("datei", StringArgumentType.string()).suggests(versionKeys)
						.executes(c -> {
							String key = StringArgumentType.getString(c, "datei").replace('.', '/');
							List<String> v = Versions.versions(key);
							return v.isEmpty() ? Feedback.fail(c.getSource(), "Keine Versionen von „" + key + "“")
									: Feedback.ok(c.getSource(), v.size() + " Version(en) von „" + key + "“: " + String.join(", ", v.subList(Math.max(0, v.size() - 8), v.size())));
						})
						.then(literal("wiederherstellen").then(argument("version", StringArgumentType.string()).executes(c -> {
							String key = StringArgumentType.getString(c, "datei").replace('.', '/');
							try {
								Versions.restore(key, StringArgumentType.getString(c, "version"));
							} catch (IOException e) {
								return Feedback.fail(c.getSource(), e.getMessage());
							}
							reload(key);
							return Feedback.ok(c.getSource(), "Version wiederhergestellt: " + key);
						})))));

		root.then(literal("projekt")
				.then(literal("liste").executes(c -> {
					List<String> p = projects();
					return Feedback.ok(c.getSource(), p.isEmpty() ? "Noch keine Projekte" : "Projekte: " + String.join(", ", p));
				}))
				.then(literal("export").then(argument("name", StringArgumentType.word()).executes(c -> {
					try {
						int n = exportProject(StringArgumentType.getString(c, "name"));
						return Feedback.ok(c.getSource(), "Projekt „" + StringArgumentType.getString(c, "name") + "“ gespeichert: " + n
								+ " Dateien → config/btb_cinematic/projekte/");
					} catch (IOException e) {
						return Feedback.fail(c.getSource(), "Export fehlgeschlagen: " + e.getMessage());
					}
				})))
				.then(literal("import").then(argument("name", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(projects(), b)).executes(c -> {
							try {
								int n = importProject(StringArgumentType.getString(c, "name"));
								return Feedback.ok(c.getSource(), "Projekt „" + StringArgumentType.getString(c, "name") + "“ geladen: " + n
										+ " Dateien (alte Stände liegen in den Versionen)");
							} catch (IOException e) {
								return Feedback.fail(c.getSource(), "Import fehlgeschlagen: " + e.getMessage());
							}
						}))));

		SuggestionProvider<CommandSourceStack> templates = (c, b) -> SharedSuggestionProvider.suggest(Templates.ids(), b);
		root.then(literal("vorlage")
				.executes(c -> Feedback.ok(c.getSource(), "Vorlagen: " + String.join(", ", Templates.ids())))
				.then(argument("name", StringArgumentType.word()).suggests(templates)
						.executes(c -> template(c, false, false))
						.then(argument("darsteller", EntityArgument.entity())
								.executes(c -> template(c, true, false))
								.then(literal("szene").executes(c -> template(c, true, true))))
						.then(literal("szene").executes(c -> template(c, false, true)))));

		root.then(literal("welt")
				.then(literal("zeit").then(argument("ziel", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("morgen", "mittag", "abend", "nacht", "mitternacht", "6000"), b))
						.executes(c -> time(c, 0))
						.then(argument("sekunden", DoubleArgumentType.doubleArg(0, 600)).executes(c -> time(c, DoubleArgumentType.getDouble(c, "sekunden"))))))
				.then(literal("wetter").then(argument("art", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("klar", "regen", "gewitter"), b))
						.executes(c -> weather(c.getSource(), StringArgumentType.getString(c, "art"))))));

		SuggestionProvider<CommandSourceStack> groupIds = (c, b) -> SharedSuggestionProvider.suggest(groups.keySet(), b);
		root.then(literal("gruppe")
				.then(literal("liste").executes(c -> Feedback.ok(c.getSource(), groups.isEmpty() ? "Keine Gruppen"
						: "Gruppen: " + String.join(", ", groups.entrySet().stream().map(e -> e.getKey() + " (" + alive(e.getKey()).size() + ")").toList()))))
				.then(argument("name", StringArgumentType.word()).suggests(groupIds)
						.then(literal("spawn").then(argument("typ", net.minecraft.commands.arguments.IdentifierArgument.id())
								.suggests((c, b) -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.ENTITY_TYPE.keySet(), b))
								.then(argument("anzahl", IntegerArgumentType.integer(1, 60))
										.executes(c -> spawnGroup(c, 4))
										.then(argument("radius", DoubleArgumentType.doubleArg(0.5, 40)).executes(c -> spawnGroup(c, DoubleArgumentType.getDouble(c, "radius")))))))
						.then(literal("laufen").then(argument("ziel", Vec3Argument.vec3())
								.executes(c -> walk(c, 1.0))
								.then(argument("tempo", DoubleArgumentType.doubleArg(0.2, 3)).executes(c -> walk(c, DoubleArgumentType.getDouble(c, "tempo"))))))
						.then(literal("fliehen").executes(c -> flee(c)))
						.then(literal("jubeln").executes(c -> cheer(c, 5)).then(argument("sekunden", DoubleArgumentType.doubleArg(0.5, 60))
								.executes(c -> cheer(c, DoubleArgumentType.getDouble(c, "sekunden")))))
						.then(literal("schauen").then(argument("ziel", Vec3Argument.vec3()).executes(this::lookGroup)))
						.then(literal("stehen").executes(c -> standGroup(c)))
						.then(literal("weg").executes(c -> removeGroup(c)))));

		SuggestionProvider<CommandSourceStack> dialogs = (c, b) -> SharedSuggestionProvider.suggest(dialogIds(), b);
		root.then(literal("dialog")
				.then(literal("liste").executes(c -> Feedback.ok(c.getSource(), dialogIds().isEmpty() ? "Noch keine Dialoge" : "Dialoge: " + String.join(", ", dialogIds()))))
				.then(literal("stop").executes(c -> {
					dialogRuns.clear();
					talking.clear();
					run("btb hud hide");
					return Feedback.ok(c.getSource(), "Dialog gestoppt");
				}))
				.then(argument("id", StringArgumentType.word()).suggests(dialogs)
						.executes(c -> showDialog(c.getSource(), StringArgumentType.getString(c, "id")))
						.then(literal("add").then(argument("sekunde", DoubleArgumentType.doubleArg(0, 7200))
								.then(argument("dauer", DoubleArgumentType.doubleArg(0.3, 60))
										.then(argument("text", StringArgumentType.greedyString()).executes(this::addLine)))))
						.then(literal("remove").then(argument("nr", IntegerArgumentType.integer(1, 9999)).executes(c -> editDialog(c, (lines, nr) -> {
							if (nr > lines.size()) {
								return "Zeile " + nr + " gibt es nicht";
							}
							lines.remove(nr - 1);
							return null;
						}))))
						.then(literal("shift").then(argument("nr", IntegerArgumentType.integer(1, 9999))
								.then(argument("sekunden", DoubleArgumentType.doubleArg(-600, 600)).executes(c -> editDialog(c, (lines, nr) -> {
									if (nr > lines.size()) {
										return "Zeile " + nr + " gibt es nicht";
									}
									JsonObject l = lines.get(nr - 1).getAsJsonObject();
									l.addProperty("t", Math.max(0, l.get("t").getAsDouble() + DoubleArgumentType.getDouble(c, "sekunden")));
									return null;
								})))))
						.then(literal("clear").executes(c -> editDialog(c, (lines, nr) -> {
							while (!lines.isEmpty()) {
								lines.remove(0);
							}
							return null;
						})))
						.then(literal("play").executes(c -> playDialog(c.getSource(), StringArgumentType.getString(c, "id"))))
						.then(literal("srt").executes(c -> srt(c.getSource(), StringArgumentType.getString(c, "id"))))
						.then(literal("delete").executes(c -> {
							Path f = dialogDir().resolve(StringArgumentType.getString(c, "id") + ".json");
							Versions.before(f);
							try {
								Files.deleteIfExists(f);
							} catch (IOException e) {
								return Feedback.fail(c.getSource(), e.getMessage());
							}
							return Feedback.ok(c.getSource(), "Dialog gelöscht");
						}))));

		SuggestionProvider<CommandSourceStack> roles = (c, b) -> SharedSuggestionProvider.suggest(List.of("regie", "kamera", "darsteller", "aus"), b);
		root.then(literal("rolle")
				.executes(c -> Feedback.ok(c.getSource(), rolesSummary()))
				.then(argument("spieler", EntityArgument.players()).then(argument("rolle", StringArgumentType.word()).suggests(roles).executes(c -> {
					String r = StringArgumentType.getString(c, "rolle").toLowerCase(Locale.ROOT);
					int n = 0;
					for (ServerPlayer p : EntityArgument.getPlayers(c, "spieler")) {
						setRole(p, r);
						n++;
					}
					return Feedback.ok(c.getSource(), n + " Spieler: Rolle " + r);
				}))));
		root.then(literal("hinweis").then(argument("an", EntityArgument.players())
				.then(argument("vorlauf", DoubleArgumentType.doubleArg(0, 30))
						.then(argument("text", StringArgumentType.greedyString()).executes(c -> prompt(c, true)))))
				.then(literal("darsteller").then(argument("vorlauf", DoubleArgumentType.doubleArg(0, 30))
						.then(argument("text", StringArgumentType.greedyString()).executes(c -> prompt(c, false))))));
	}

	// ------------------------------------------------------------------ stop / undo

	private int stopAll(CommandSourceStack src) {
		run("btb scene stop");
		run("btb camera stop @a");
		run("btb effects clear 0.5");
		run("btb slowmo off");
		run("btb sound stop");
		if (!de.behindtheblocks.cinematic.replay.ReplayManager.menuInfo().get("playing").getAsString().isEmpty()) {
			run("btb replay stop");
		}
		dialogRuns.clear();
		talking.clear();
		timeTicks = 0;
		return Feedback.ok(src, "Alles gestoppt (Szenen, Kamera, Effekte, Zeitlupe, Ton, Dialog) – das Set bleibt, wie es ist");
	}

	private int undo(CommandSourceStack src) {
		try {
			Versions.Change c = Versions.undo();
			if (c == null) {
				return Feedback.fail(src, "Nichts zum Rückgängigmachen");
			}
			reload(c.label());
			return Feedback.ok(src, "↶ Rückgängig: " + c.label());
		} catch (IOException e) {
			return Feedback.fail(src, "Rückgängig fehlgeschlagen: " + e.getMessage());
		}
	}

	/** Reloads the module that owns a config file ("scenes/intro" → scenes). */
	private void reload(String key) {
		String folder = key.contains("/") ? key.substring(0, key.indexOf('/')) : key;
		switch (folder) {
			case "scenes" -> engine.scenes().reload();
			case "cameras" -> run("btb camera reload");
			case "hud" -> run("btb hud reload");
			case "triggers" -> run("btb trigger reload");
			case "ambience" -> run("btb ambience reload");
			case "portals" -> run("btb portal reload");
			case "showcases" -> run("btb showcase reload");
			case "destruction" -> run("btb destruction reload");
			default -> {
			}
		}
	}

	public static JsonObject menuInfo() {
		JsonObject o = new JsonObject();
		o.addProperty("undo", Versions.undoSize());
		o.addProperty("undo_next", Versions.nextUndo());
		JsonArray keys = new JsonArray();
		Versions.keys().stream().limit(40).forEach(k -> {
			JsonObject e = new JsonObject();
			e.addProperty("key", k);
			List<String> v = Versions.versions(k);
			JsonArray va = new JsonArray();
			v.subList(Math.max(0, v.size() - 5), v.size()).forEach(va::add);
			e.add("versions", va);
			keys.add(e);
		});
		o.add("versions", keys);
		JsonArray pr = new JsonArray();
		projects().forEach(pr::add);
		o.add("projects", pr);
		JsonArray tp = new JsonArray();
		Templates.ids().forEach(tp::add);
		o.add("templates", tp);
		JsonArray ti = new JsonArray();
		for (Templates.Template t : Templates.all()) {
			JsonObject e = new JsonObject();
			e.addProperty("id", t.id());
			e.addProperty("title", t.title());
			e.addProperty("description", t.description());
			ti.add(e);
		}
		o.add("template_info", ti);
		StudioManager s = instance;
		JsonObject dialogs = new JsonObject();
		for (String id : dialogIds()) {
			JsonArray lines = readDialog(id);
			dialogs.add(id, lines);
		}
		o.add("dialogs", dialogs);
		JsonObject gr = new JsonObject();
		if (s != null) {
			s.groups.keySet().forEach(g -> gr.addProperty(g, s.alive(g).size()));
		}
		o.add("groups", gr);
		JsonObject rolesObj = new JsonObject();
		if (s != null && s.server != null) {
			for (ServerPlayer p : s.server.getPlayerList().getPlayers()) {
				rolesObj.addProperty(p.getName().getString(), role(p));
			}
		}
		o.add("roles", rolesObj);
		return o;
	}

	// ------------------------------------------------------------------ projects

	private static final List<String> PROJECT_PARTS = List.of("scenes", "cameras", "hud", "dialog", "destruction", "portals", "showcases",
			"ambience", "triggers", "parkour", "replays");

	private static List<String> projects() {
		if (!Files.isDirectory(projectDir())) {
			return List.of();
		}
		try (Stream<Path> s = Files.list(projectDir())) {
			return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".zip")).map(n -> n.substring(0, n.length() - 4)).sorted().toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	private int exportProject(String name) throws IOException {
		Files.createDirectories(projectDir());
		Path zip = projectDir().resolve(name + ".zip");
		int n = 0;
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			for (String part : PROJECT_PARTS) {
				Path dir = config().resolve(part);
				if (!Files.isDirectory(dir)) {
					continue;
				}
				try (Stream<Path> s = Files.walk(dir)) {
					for (Path p : s.filter(Files::isRegularFile).toList()) {
						out.putNextEntry(new ZipEntry(config().relativize(p).toString().replace('\\', '/')));
						Files.copy(p, out);
						out.closeEntry();
						n++;
					}
				}
			}
			// a small note what this is (obs.json with the password and personal settings stay out)
			out.putNextEntry(new ZipEntry("BTB_PROJEKT.txt"));
			out.write(("BTB Cinematics project \"" + name + "\"\nImport: put this zip into config/btb_cinematic/projekte/ and run /btb projekt import "
					+ name + "\n").getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}
		return n;
	}

	private int importProject(String name) throws IOException {
		Path zip = projectDir().resolve(name + ".zip");
		if (!Files.exists(zip)) {
			throw new IOException("„" + name + "“ nicht gefunden");
		}
		int n = 0;
		Path base = config().toAbsolutePath().normalize();
		try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
			ZipEntry e;
			while ((e = in.getNextEntry()) != null) {
				if (e.isDirectory()) {
					continue;
				}
				String entry = e.getName();
				String top = entry.contains("/") ? entry.substring(0, entry.indexOf('/')) : "";
				if (!PROJECT_PARTS.contains(top)) {
					continue;
				}
				Path target = base.resolve(entry).normalize();
				if (!target.startsWith(base)) { // no "../" tricks
					continue;
				}
				Files.createDirectories(target.getParent());
				if (entry.endsWith(".json")) {
					Versions.before(target);
				}
				Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
				n++;
			}
		}
		for (String part : List.of("scenes", "cameras", "hud", "triggers", "ambience", "portals", "showcases", "destruction")) {
			reload(part);
		}
		return n;
	}

	// ------------------------------------------------------------------ templates

	private int template(CommandContext<CommandSourceStack> c, boolean hasActor, boolean asScene) throws CommandSyntaxException {
		CommandSourceStack src = c.getSource();
		String name = StringArgumentType.getString(c, "name");
		Templates.Template t = Templates.get(name);
		if (t == null) {
			return Feedback.fail(src, "Unbekannte Vorlage „" + name + "“" + Suggest.hint(name, Templates.ids()));
		}
		Entity actor = hasActor ? EntityArgument.getEntity(c, "darsteller") : nearestActor(src);
		if (actor == null) {
			return Feedback.fail(src, "Kein Darsteller gefunden – stell dich neben jemanden oder gib einen Namen an");
		}
		String scene = "vorlage_" + name;
		List<String[]> steps = t.steps(actor, src);
		run("btb scene delete " + scene);
		run("btb scene create " + scene);
		for (String[] s : steps) {
			run("btb scene add " + scene + " " + s[0] + " " + s[1]);
		}
		if (!asScene) {
			run("btb scene restart " + scene);
			return Feedback.ok(src, "▶ " + t.title() + " mit " + actor.getName().getString() + " (bearbeiten: Szene „" + scene + "“)");
		}
		return Feedback.ok(src, "Szene „" + scene + "“ angelegt (" + steps.size() + " Schritte) – im Baukasten anpassen");
	}

	private Entity nearestActor(CommandSourceStack src) {
		Entity self = src.getEntity();
		Vec3 at = src.getPosition();
		Entity best = null;
		double bestD = 48 * 48;
		for (ServerPlayer p : src.getServer().getPlayerList().getPlayers()) {
			if (p == self || p.entityTags().contains("kamera") || p.isSpectator()) {
				continue;
			}
			double d = p.position().distanceToSqr(at);
			if (d < bestD) {
				bestD = d;
				best = p;
			}
		}
		if (best == null) {
			for (Entity e : src.getLevel().getEntities((Entity) null, new net.minecraft.world.phys.AABB(at, at).inflate(24),
					e -> e instanceof net.minecraft.world.entity.decoration.Mannequin || e.entityTags().contains(GROUP_TAG))) {
				double d = e.position().distanceToSqr(at);
				if (d < bestD) {
					bestD = d;
					best = e;
				}
			}
		}
		return best != null ? best : self;
	}

	// ------------------------------------------------------------------ world: time + weather

	private int time(CommandContext<CommandSourceStack> c, double seconds) {
		String goal = StringArgumentType.getString(c, "ziel").toLowerCase(Locale.ROOT);
		long target;
		switch (goal) {
			case "morgen", "morning", "day" -> target = 0;
			case "mittag", "noon" -> target = 6000;
			case "abend", "sunset", "evening" -> target = 12000;
			case "nacht", "night" -> target = 13800;
			case "mitternacht", "midnight" -> target = 18000;
			default -> {
				try {
					target = Long.parseLong(goal);
				} catch (NumberFormatException e) {
					return Feedback.fail(c.getSource(), "Zeit: morgen, mittag, abend, nacht, mitternacht oder 0–24000");
				}
			}
		}
		ServerLevel level = c.getSource().getServer().overworld();
		long now = level.getOverworldClockTime() % 24000;
		if (seconds <= 0) {
			run("time set " + target);
			return Feedback.ok(c.getSource(), "Zeit: " + goal);
		}
		long to = target;
		while (to < now) {
			to += 24000; // always forward: sunset into night, never back through noon
		}
		timeFrom = now;
		timeTo = to;
		timeTick = 0;
		timeTicks = (int) Math.round(seconds * 20);
		String note = c.getSource().getLevel().dimension() == net.minecraft.world.level.Level.OVERWORLD ? ""
				: " (im Nether/End gibt es keine Tageszeit – wirkt nur in der Oberwelt)";
		return Feedback.ok(c.getSource(), "Zeit wandert in " + DoubleArgumentType.getDouble(c, "sekunden") + " s zu „" + goal + "“" + note);
	}

	private int weather(CommandSourceStack src, String kind) {
		switch (kind.toLowerCase(Locale.ROOT)) {
			case "klar", "clear" -> run("weather clear 600");
			case "regen", "rain" -> run("weather rain 600");
			case "gewitter", "thunder" -> run("weather thunder 600");
			default -> {
				return Feedback.fail(src, "Wetter: klar, regen oder gewitter");
			}
		}
		String note = src.getLevel().dimension() == net.minecraft.world.level.Level.OVERWORLD ? "" : " (Wetter gibt es nur in der Oberwelt)";
		return Feedback.ok(src, "Wetter: " + kind + note);
	}

	// ------------------------------------------------------------------ crowds

	private List<Entity> alive(String group) {
		List<Entity> out = new ArrayList<>();
		if (server == null || !groups.containsKey(group)) {
			return out;
		}
		for (UUID id : groups.get(group)) {
			for (ServerLevel l : server.getAllLevels()) {
				Entity e = l.getEntity(id);
				if (e != null && e.isAlive()) {
					out.add(e);
					break;
				}
			}
		}
		return out;
	}

	private int spawnGroup(CommandContext<CommandSourceStack> c, double radius) throws CommandSyntaxException {
		String name = StringArgumentType.getString(c, "name");
		Identifier typeId = net.minecraft.commands.arguments.IdentifierArgument.getId(c, "typ");
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(typeId).orElse(null);
		if (type == null) {
			return Feedback.fail(c.getSource(), "Unbekannter Typ „" + typeId + "“");
		}
		int count = IntegerArgumentType.getInteger(c, "anzahl");
		ServerLevel level = c.getSource().getLevel();
		Vec3 at = c.getSource().getPosition();
		List<UUID> members = groups.computeIfAbsent(name, k -> new ArrayList<>());
		int made = 0;
		for (int i = 0; i < count; i++) {
			double a = level.getRandom().nextDouble() * Math.PI * 2, r = Math.sqrt(level.getRandom().nextDouble()) * radius;
			Entity e = type.create(level, EntitySpawnReason.COMMAND);
			if (e == null) {
				continue;
			}
			e.snapTo(at.x + Math.cos(a) * r, at.y, at.z + Math.sin(a) * r, level.getRandom().nextFloat() * 360, 0);
			e.addTag(GROUP_TAG);
			e.addTag(de.behindtheblocks.cinematic.story.SpawnManager.TAG);
			if (e instanceof Mob mob) {
				mob.setPersistenceRequired();
				mob.setTarget(null);
				// extras: no fighting, no wandering off – they only move when the director says so
				clearGoals(mob);
			}
			level.addFreshEntity(e);
			members.add(e.getUUID());
			made++;
		}
		return Feedback.ok(c.getSource(), "Gruppe „" + name + "“: " + made + " Statisten (" + typeId.getPath() + ")");
	}

	/** Minecraft is not obfuscated since 26.1: the goal selectors can be reached by name. */
	private static void clearGoals(Mob mob) {
		for (String field : new String[] {"goalSelector", "targetSelector"}) {
			try {
				java.lang.reflect.Field f = Mob.class.getDeclaredField(field);
				f.setAccessible(true);
				((net.minecraft.world.entity.ai.goal.GoalSelector) f.get(mob)).removeAllGoals(g -> true);
			} catch (ReflectiveOperationException | RuntimeException ex) {
				LOG.debug("Goals of {} kept: {}", mob, ex.toString());
			}
		}
	}

	private int walk(CommandContext<CommandSourceStack> c, double speed) {
		String name = StringArgumentType.getString(c, "name");
		Vec3 to = Vec3Argument.getVec3(c, "ziel");
		List<Entity> list = alive(name);
		if (list.isEmpty()) {
			return Feedback.fail(c.getSource(), "Gruppe „" + name + "“ ist leer" + Suggest.hint(name, groups.keySet()));
		}
		Vec3 center = center(list);
		for (Entity e : list) {
			// keep the formation: everyone keeps their offset to the group centre
			Vec3 goal = to.add(e.position().subtract(center).scale(0.8));
			if (e instanceof PathfinderMob m) {
				m.getNavigation().moveTo(goal.x, goal.y, goal.z, speed);
			}
		}
		return Feedback.ok(c.getSource(), "Gruppe „" + name + "“ läuft los (" + list.size() + ")");
	}

	private int flee(CommandContext<CommandSourceStack> c) {
		String name = StringArgumentType.getString(c, "name");
		List<Entity> list = alive(name);
		Vec3 from = c.getSource().getPosition();
		for (Entity e : list) {
			Vec3 away = e.position().subtract(from);
			away = new Vec3(away.x, 0, away.z);
			if (away.lengthSqr() < 0.01) {
				away = new Vec3(1, 0, 0);
			}
			Vec3 goal = e.position().add(away.normalize().scale(18));
			if (e instanceof PathfinderMob m) {
				m.getNavigation().moveTo(goal.x, goal.y, goal.z, 1.6);
			}
		}
		return Feedback.ok(c.getSource(), "Gruppe „" + name + "“ flieht vor dir");
	}

	private int cheer(CommandContext<CommandSourceStack> c, double seconds) {
		String name = StringArgumentType.getString(c, "name");
		List<Entity> list = alive(name);
		for (Entity e : list) {
			cheering.put(e.getUUID(), (int) Math.round(seconds * 20));
		}
		return Feedback.ok(c.getSource(), "Gruppe „" + name + "“ jubelt");
	}

	private int lookGroup(CommandContext<CommandSourceStack> c) {
		String name = StringArgumentType.getString(c, "name");
		Vec3 at = Vec3Argument.getVec3(c, "ziel");
		for (Entity e : alive(name)) {
			face(e, at);
		}
		return Feedback.ok(c.getSource(), "Gruppe „" + name + "“ schaut hin");
	}

	private int standGroup(CommandContext<CommandSourceStack> c) {
		String name = StringArgumentType.getString(c, "name");
		for (Entity e : alive(name)) {
			if (e instanceof Mob m) {
				m.getNavigation().stop();
			}
			cheering.remove(e.getUUID());
		}
		return Feedback.ok(c.getSource(), "Gruppe „" + name + "“ bleibt stehen");
	}

	private int removeGroup(CommandContext<CommandSourceStack> c) {
		String name = StringArgumentType.getString(c, "name");
		List<Entity> list = alive(name);
		list.forEach(Entity::discard);
		groups.remove(name);
		return Feedback.ok(c.getSource(), "Gruppe „" + name + "“ entfernt (" + list.size() + ")");
	}

	private static Vec3 center(List<Entity> list) {
		Vec3 s = Vec3.ZERO;
		for (Entity e : list) {
			s = s.add(e.position());
		}
		return s.scale(1.0 / list.size());
	}

	private static void face(Entity e, Vec3 at) {
		Vec3 d = at.subtract(e.getEyePosition());
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
		e.setYRot(yaw);
		e.setXRot(pitch);
		e.setYHeadRot(yaw);
		if (e instanceof LivingEntity le) {
			le.yBodyRot = yaw;
		}
	}

	// ------------------------------------------------------------------ dialog

	private static List<String> dialogIds() {
		if (!Files.isDirectory(dialogDir())) {
			return List.of();
		}
		try (Stream<Path> s = Files.list(dialogDir())) {
			return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).map(n -> n.substring(0, n.length() - 5)).sorted().toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	private static JsonArray readDialog(String id) {
		try {
			Path f = dialogDir().resolve(id + ".json");
			if (Files.exists(f)) {
				JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
				return o.has("lines") ? o.getAsJsonArray("lines") : new JsonArray();
			}
		} catch (Exception e) {
			LOG.warn("Dialog {} not readable: {}", id, e.toString());
		}
		return new JsonArray();
	}

	private static void writeDialog(String id, JsonArray lines) throws IOException {
		List<com.google.gson.JsonElement> sorted = new ArrayList<>();
		lines.forEach(sorted::add);
		sorted.sort((a, b) -> Double.compare(a.getAsJsonObject().get("t").getAsDouble(), b.getAsJsonObject().get("t").getAsDouble()));
		JsonArray out = new JsonArray();
		sorted.forEach(out::add);
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.add("lines", out);
		Files.createDirectories(dialogDir());
		Path f = dialogDir().resolve(id + ".json");
		Versions.before(f);
		Files.writeString(f, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(o), StandardCharsets.UTF_8);
	}

	/** add <s> <dur> Sprecher | deutscher Text [| English text] */
	private int addLine(CommandContext<CommandSourceStack> c) {
		String id = StringArgumentType.getString(c, "id");
		if (!de.behindtheblocks.cinematic.scene.SceneLoader.ID_PATTERN.matcher(id).matches()) {
			return Feedback.fail(c.getSource(), "Id: only a-z, 0-9 and _");
		}
		String[] parts = StringArgumentType.getString(c, "text").split("\\|", -1);
		JsonObject line = new JsonObject();
		line.addProperty("t", DoubleArgumentType.getDouble(c, "sekunde"));
		line.addProperty("dauer", DoubleArgumentType.getDouble(c, "dauer"));
		if (parts.length >= 2) {
			line.addProperty("sprecher", parts[0].trim());
			line.addProperty("de", parts[1].trim());
			line.addProperty("en", parts.length >= 3 && !parts[2].isBlank() ? parts[2].trim() : parts[1].trim());
		} else {
			line.addProperty("sprecher", "");
			line.addProperty("de", parts[0].trim());
			line.addProperty("en", parts[0].trim());
		}
		JsonArray lines = readDialog(id);
		lines.add(line);
		try {
			writeDialog(id, lines);
		} catch (IOException e) {
			return Feedback.fail(c.getSource(), e.getMessage());
		}
		return Feedback.ok(c.getSource(), "Dialog „" + id + "“: Zeile " + lines.size() + " hinzugefügt");
	}

	private interface LineEdit {
		String apply(JsonArray lines, int nr);
	}

	private int editDialog(CommandContext<CommandSourceStack> c, LineEdit edit) {
		String id = StringArgumentType.getString(c, "id");
		JsonArray lines = readDialog(id);
		int nr;
		try {
			nr = IntegerArgumentType.getInteger(c, "nr");
		} catch (IllegalArgumentException e) {
			nr = 0;
		}
		String err = edit.apply(lines, nr);
		if (err != null) {
			return Feedback.fail(c.getSource(), err);
		}
		try {
			writeDialog(id, lines);
		} catch (IOException e) {
			return Feedback.fail(c.getSource(), e.getMessage());
		}
		return Feedback.ok(c.getSource(), "Dialog „" + id + "“ geändert (" + lines.size() + " Zeilen)");
	}

	private int showDialog(CommandSourceStack src, String id) {
		JsonArray lines = readDialog(id);
		if (lines.isEmpty()) {
			return Feedback.fail(src, "Dialog „" + id + "“ ist leer – Zeile hinzufügen: /btb dialog " + id + " add <sek> <dauer> Sprecher | Text | English");
		}
		for (int i = 0; i < lines.size(); i++) {
			JsonObject l = lines.get(i).getAsJsonObject();
			Feedback.info(src, (i + 1) + ". " + l.get("t").getAsDouble() + " s  " + l.get("sprecher").getAsString() + ": " + l.get("de").getAsString());
		}
		return lines.size();
	}

	private static List<Line> lines(String id) {
		List<Line> out = new ArrayList<>();
		for (var el : readDialog(id)) {
			JsonObject l = el.getAsJsonObject();
			out.add(new Line(l.get("t").getAsDouble(), l.get("dauer").getAsDouble(), l.has("sprecher") ? l.get("sprecher").getAsString() : "",
					l.get("de").getAsString(), l.has("en") ? l.get("en").getAsString() : l.get("de").getAsString()));
		}
		return out;
	}

	private int playDialog(CommandSourceStack src, String id) {
		List<Line> l = lines(id);
		if (l.isEmpty()) {
			return Feedback.fail(src, "Dialog „" + id + "“ ist leer");
		}
		dialogRuns.add(new Object[] {id, l, 0});
		return Feedback.ok(src, "▶ Dialog „" + id + "“ (" + l.size() + " Zeilen)");
	}

	private void showLine(Line line) {
		if (server == null) {
			return;
		}
		HudManager hud = engine.module(HudManager.class).orElse(null);
		if (hud == null) {
			return;
		}
		List<ServerPlayer> de = new ArrayList<>(), en = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			(I18n.german(p) ? de : en).add(p);
		}
		String who = line.speaker.isBlank() ? "" : line.speaker + ": ";
		try {
			if (!de.isEmpty()) {
				hud.show("", 0, HudQuickStyles.build("untertitel", line.dur, who + line.de), de);
			}
			if (!en.isEmpty()) {
				hud.show("", 0, HudQuickStyles.build("untertitel", line.dur, who + line.en), en);
			}
		} catch (Exception e) {
			LOG.warn("Subtitle not shown: {}", e.toString());
		}
		if (!line.speaker.isBlank()) {
			talking.add(new Talk(line.speaker, serverTick + (int) Math.round(line.dur * 20)));
		}
	}

	private int srt(CommandSourceStack src, String id) {
		List<Line> l = lines(id);
		if (l.isEmpty()) {
			return Feedback.fail(src, "Dialog „" + id + "“ ist leer");
		}
		Path dir = FabricLoaderDirs.exportDir();
		try {
			Files.createDirectories(dir);
			Files.writeString(dir.resolve(id + "_de.srt"), srtText(l, true), StandardCharsets.UTF_8);
			Files.writeString(dir.resolve(id + "_en.srt"), srtText(l, false), StandardCharsets.UTF_8);
		} catch (IOException e) {
			return Feedback.fail(src, e.getMessage());
		}
		return Feedback.ok(src, "Untertitel gespeichert: btb_export/" + id + "_de.srt und " + id + "_en.srt");
	}

	static String srtText(List<Line> lines, boolean german) {
		StringBuilder sb = new StringBuilder();
		int n = 1;
		for (Line l : lines) {
			sb.append(n++).append('\n').append(stamp(l.t)).append(" --> ").append(stamp(l.t + l.dur)).append('\n');
			if (!l.speaker.isBlank()) {
				sb.append(l.speaker).append(": ");
			}
			sb.append(german ? l.de : l.en).append("\n\n");
		}
		return sb.toString();
	}

	private static String stamp(double s) {
		long ms = Math.round(s * 1000);
		return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000);
	}

	/** Entities called like the speaker: statists (mannequins), named mobs, players. */
	private List<Entity> speakers(String name) {
		List<Entity> out = new ArrayList<>();
		if (server == null) {
			return out;
		}
		ServerPlayer p = server.getPlayerList().getPlayerByName(name);
		if (p != null) {
			out.add(p);
		}
		for (ServerLevel l : server.getAllLevels()) {
			for (Entity e : l.getAllEntities()) {
				if (!(e instanceof ServerPlayer) && e.hasCustomName() && e.getCustomName().getString().equalsIgnoreCase(name)) {
					out.add(e);
				}
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ roles + prompter

	private static final List<String> ROLES = List.of("regie", "kamera", "darsteller");

	public static String role(ServerPlayer p) {
		for (String r : ROLES) {
			if (p.entityTags().contains(r.equals("kamera") ? "kamera" : "btb_" + r)) {
				return r;
			}
		}
		return "";
	}

	private void setRole(ServerPlayer p, String role) {
		p.removeTag("kamera");
		p.removeTag("btb_regie");
		p.removeTag("btb_darsteller");
		switch (role) {
			case "kamera" -> p.addTag("kamera"); // the existing camera tag: film moves aim here, replays leave it out
			case "regie" -> p.addTag("btb_regie");
			case "darsteller" -> p.addTag("btb_darsteller");
			default -> {
			}
		}
		JsonObject o = new JsonObject();
		o.addProperty("role", role(p));
		engine.cues().queue(TimelineManager.MANUAL, 0, "btb_role", o, List.of(p));
	}

	private String rolesSummary() {
		if (server == null) {
			return "";
		}
		List<String> parts = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			String r = role(p);
			parts.add(p.getName().getString() + ": " + (r.isEmpty() ? "–" : r));
		}
		return "Rollen: " + String.join(", ", parts);
	}

	private int prompt(CommandContext<CommandSourceStack> c, boolean explicit) throws CommandSyntaxException {
		Collection<ServerPlayer> to;
		if (explicit) {
			to = EntityArgument.getPlayers(c, "an");
		} else {
			to = c.getSource().getServer().getPlayerList().getPlayers().stream().filter(p -> role(p).equals("darsteller")).toList();
			if (to.isEmpty()) {
				return Feedback.fail(c.getSource(), "Noch keine Darsteller – im Regiepult unter Einstellungen → Rollen festlegen");
			}
		}
		JsonObject o = new JsonObject();
		o.addProperty("text", StringArgumentType.getString(c, "text"));
		o.addProperty("seconds", DoubleArgumentType.getDouble(c, "vorlauf"));
		engine.cues().queue(TimelineManager.MANUAL, 0, "btb_prompt", o, to);
		return Feedback.ok(c.getSource(), "Hinweis an " + to.size() + " Darsteller");
	}

	// ------------------------------------------------------------------ tick

	@Override
	public void tick(MinecraftServer server) {
		serverTick++;
		if (timeTicks > 0) {
			timeTick++;
			double k = Math.min(1, timeTick / (double) timeTicks);
			double s = k * k * (3 - 2 * k);
			long t = Math.round(timeFrom + (timeTo - timeFrom) * s) % 24000;
			if (timeTick % 2 == 0 || k >= 1) {
				run("time set " + t);
			}
			if (k >= 1) {
				timeTicks = 0;
			}
		}
		// dialogs
		var it = dialogRuns.iterator();
		while (it.hasNext()) {
			Object[] r = it.next();
			@SuppressWarnings("unchecked")
			List<Line> l = (List<Line>) r[1];
			int tick = (int) r[2];
			for (Line line : l) {
				if ((int) Math.round(line.t * 20) == tick) {
					showLine(line);
				}
			}
			r[2] = tick + 1;
			double end = l.stream().mapToDouble(x -> x.t + x.dur).max().orElse(0);
			if (tick > end * 20 + 2) {
				it.remove();
			}
		}
		// talking: speakers nod, look around a little and gesture now and then
		talking.removeIf(t -> t.until < serverTick);
		if (!talking.isEmpty() && serverTick % 2 == 0) {
			for (Talk t : talking) {
				for (Entity e : speakers(t.speaker)) {
					if (e instanceof ServerPlayer) {
						continue; // players move their own head
					}
					double ph = serverTick * 0.45 + e.getId();
					e.setXRot((float) (Math.sin(ph) * 6 + Math.sin(ph * 0.37) * 3));
					e.setYHeadRot(e.getYRot() + (float) (Math.sin(ph * 0.21) * 12));
					if (e instanceof LivingEntity le && (serverTick + e.getId() * 7) % 36 == 0) {
						le.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
					}
				}
			}
		}
		// cheering crowds: jump + wave
		if (!cheering.isEmpty()) {
			var ci = cheering.entrySet().iterator();
			while (ci.hasNext()) {
				var en = ci.next();
				int left = en.getValue() - 1;
				Entity e = null;
				for (ServerLevel lv : server.getAllLevels()) {
					e = lv.getEntity(en.getKey());
					if (e != null) {
						break;
					}
				}
				if (e == null || left <= 0) {
					ci.remove();
					continue;
				}
				en.setValue(left);
				if (e.onGround() && (left + e.getId()) % 14 == 0) {
					e.setDeltaMovement(e.getDeltaMovement().add(0, 0.42, 0));
					e.needsSync = true;
				}
				if (e instanceof LivingEntity le && (left + e.getId() * 3) % 10 == 0) {
					le.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
				}
			}
		}
	}

	/** Where exported videos and subtitles go (game directory/btb_export). */
	static final class FabricLoaderDirs {
		static Path exportDir() {
			return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("btb_export");
		}
	}

	@SuppressWarnings("unused")
	private static void copy(InputStream in, OutputStream out) throws IOException {
		in.transferTo(out);
	}
}
