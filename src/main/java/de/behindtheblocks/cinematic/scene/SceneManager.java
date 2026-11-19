package de.behindtheblocks.cinematic.scene;

import com.google.gson.JsonObject;
import de.behindtheblocks.cinematic.BtbCinematic;
import de.behindtheblocks.cinematic.core.CinematicEngine;
import de.behindtheblocks.cinematic.core.CinematicModule;
import de.behindtheblocks.cinematic.core.bus.SceneMessages;
import de.behindtheblocks.cinematic.event.EventContext;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Owns all scene definitions and the currently running playbacks.
 *
 * <p>Scenes are plain JSON files in {@code config/btb_cinematic/scenes/}. Example scenes bundled
 * with the mod are copied there on first start (existing files are never overwritten). Use
 * {@code /btb scene reload} after editing files.
 */
public final class SceneManager implements CinematicModule {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/scenes");
	private static final String BUNDLED_DIR = "btb_cinematic/default_scenes";

	private final Map<String, SceneDefinition> definitions = new TreeMap<>();
	private final Map<String, List<LoadProblem>> problemsByFile = new TreeMap<>();
	private final Map<String, ScenePlayback> active = new LinkedHashMap<>();
	private CinematicEngine engine;
	private MinecraftServer server;

	/** Outcome of a control operation, with a user-facing message. */
	public record Outcome(boolean success, String message) {
		static Outcome ok(String m) {
			return new Outcome(true, m);
		}

		static Outcome fail(String m) {
			return new Outcome(false, m);
		}
	}

	@Override
	public String id() {
		return "scene";
	}

	@Override
	public void initialize(CinematicEngine engine) {
		this.engine = engine;
	}

	public static Path sceneDirectory() {
		return FabricLoader.getInstance().getConfigDir().resolve(BtbCinematic.MOD_ID).resolve("scenes");
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		this.server = server;
		installBundledScenes();
		reload();
	}

	@Override
	public void onServerStopping(MinecraftServer server) {
		stopAll();
		this.server = null;
	}

	// ------------------------------------------------------------------ loading

	/** Copies example scenes from the mod jar into the config folder if they don't exist yet. */
	private void installBundledScenes() {
		Path target = sceneDirectory();
		try {
			Files.createDirectories(target);
		} catch (IOException e) {
			LOG.error("Cannot create scene directory {}", target, e);
			return;
		}
		Optional<ModContainer> mod = FabricLoader.getInstance().getModContainer(BtbCinematic.MOD_ID);
		Optional<Path> bundled = mod.flatMap(m -> m.findPath(BUNDLED_DIR));
		if (bundled.isEmpty()) {
			return;
		}
		try (Stream<Path> files = Files.list(bundled.get())) {
			for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
				Path dest = target.resolve(file.getFileName().toString());
				if (!Files.exists(dest)) {
					Files.copy(file, dest);
					LOG.info("Installed example scene {}", dest.getFileName());
				}
			}
		} catch (IOException e) {
			LOG.error("Failed to install bundled scenes", e);
		}
	}

	/**
	 * Re-reads every {@code *.json} in the scene directory. Running playbacks keep the definition
	 * they were started with.
	 *
	 * @return number of scenes loaded
	 */
	public int reload() {
		SceneLoader loader = new SceneLoader(engine.events()::find);
		Map<String, SceneDefinition> loaded = new TreeMap<>();
		problemsByFile.clear();
		Path dir = sceneDirectory();
		List<Path> files = new ArrayList<>();
		try {
			Files.createDirectories(dir);
			try (Stream<Path> s = Files.list(dir)) {
				s.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().forEach(files::add);
			}
		} catch (IOException e) {
			LOG.error("Cannot read scene directory {}", dir, e);
		}

		for (Path file : files) {
			String fileName = file.getFileName().toString();
			String fallbackId = fileName.substring(0, fileName.length() - ".json".length());
			SceneLoader.Result result;
			try {
				result = loader.load(fallbackId, Files.readString(file, StandardCharsets.UTF_8), fileName);
			} catch (IOException e) {
				problemsByFile.put(fileName, List.of(LoadProblem.error("cannot read file: " + e.getMessage())));
				continue;
			}
			if (!result.problems().isEmpty()) {
				problemsByFile.put(fileName, result.problems());
				result.problems().forEach(p -> LOG.warn("{}: {}", fileName, p));
			}
			result.scene().ifPresent(scene -> {
				if (loaded.containsKey(scene.id())) {
					problemsByFile.computeIfAbsent(fileName, k -> new ArrayList<>());
					List<LoadProblem> list = new ArrayList<>(problemsByFile.get(fileName));
					list.add(LoadProblem.error("duplicate scene id '" + scene.id() + "', file ignored"));
					problemsByFile.put(fileName, list);
				} else {
					loaded.put(scene.id(), scene);
				}
			});
		}

		definitions.clear();
		definitions.putAll(loaded);
		int problemCount = problemsByFile.values().stream().mapToInt(List::size).sum();
		LOG.info("Loaded {} scene(s) from {} ({} problem(s))", definitions.size(), dir, problemCount);
		engine.bus().publish(new SceneMessages.DefinitionsReloaded(definitions.size(), problemCount));
		return definitions.size();
	}

	/** Registers a scene built in code (used by other modules, e.g. generated camera scenes). */
	public void registerRuntimeScene(SceneDefinition scene) {
		definitions.put(scene.id(), scene);
	}

	// ------------------------------------------------------------------ control

	public Outcome start(String id) {
		return start(id, 0);
	}

	/** Starts a scene at a given tick (events before it are skipped). */
	public Outcome start(String id, int fromTick) {
		SceneDefinition scene = definitions.get(id);
		if (scene == null) {
			return Outcome.fail("Unknown scene '" + id + "'");
		}
		if (active.containsKey(id)) {
			return Outcome.fail("Scene '" + id + "' is already running (use stop, reset or restart)");
		}
		ScenePlayback playback = new ScenePlayback(scene);
		active.put(id, playback);
		engine.bus().publish(new SceneMessages.Started(id));
		sendStateCue(id, 0, "started");
		playback.start(this::fire, fromTick);
		if (playback.state() == PlaybackState.STOPPED) {
			finish(playback);
		}
		if (fromTick > 0) {
			return Outcome.ok("Scene '" + id + "' started at " + String.format(java.util.Locale.ROOT, "%.1f", fromTick / 20.0) + " s");
		}
		return Outcome.ok("Scene '" + id + "' started (" + scene.duration() + " ticks, "
				+ scene.events().size() + " events)");
	}

	public Outcome restart(String id) {
		if (active.containsKey(id)) {
			stop(id);
		}
		return start(id);
	}

	public Outcome stop(String id) {
		ScenePlayback playback = active.get(id);
		if (playback == null) {
			return Outcome.fail("Scene '" + id + "' is not running");
		}
		playback.stop();
		finish(playback);
		return Outcome.ok("Scene '" + id + "' stopped at tick " + playback.tick());
	}

	public int stopAll() {
		List<String> ids = new ArrayList<>(active.keySet());
		ids.forEach(this::stop);
		return ids.size();
	}

	public Outcome pause(String id) {
		ScenePlayback playback = active.get(id);
		if (playback == null || !playback.pause()) {
			return Outcome.fail("Scene '" + id + "' is not running");
		}
		engine.bus().publish(new SceneMessages.Paused(id, playback.tick()));
		sendStateCue(id, playback.tick(), "paused");
		return Outcome.ok("Scene '" + id + "' paused at tick " + playback.tick());
	}

	public Outcome resume(String id) {
		ScenePlayback playback = active.get(id);
		if (playback == null || !playback.resume()) {
			return Outcome.fail("Scene '" + id + "' is not paused");
		}
		engine.bus().publish(new SceneMessages.Resumed(id, playback.tick()));
		sendStateCue(id, playback.tick(), "resumed");
		return Outcome.ok("Scene '" + id + "' resumed at tick " + playback.tick());
	}

	/**
	 * Stops the scene (if running) and asks every module to undo the scene's changes
	 * (restore destroyed blocks, deactivate portals, clear screen effects ...).
	 */
	public Outcome reset(String id) {
		if (!definitions.containsKey(id) && !active.containsKey(id)) {
			return Outcome.fail("Unknown scene '" + id + "'");
		}
		if (active.containsKey(id)) {
			stop(id);
		}
		engine.bus().publish(new SceneMessages.Reset(id));
		sendStateCue(id, 0, "reset");
		return Outcome.ok("Scene '" + id + "' reset");
	}

	// ------------------------------------------------------------------ ticking

	@Override
	public void tick(MinecraftServer server) {
		if (active.isEmpty()) {
			return;
		}
		// Copy: events may start or stop scenes while we iterate.
		for (ScenePlayback playback : List.copyOf(active.values())) {
			if (active.get(playback.scene().id()) != playback) {
				continue; // stopped/replaced by an earlier event this tick
			}
			if (!playback.advance(this::fire)) {
				finish(playback);
			}
		}
	}

	private boolean fire(ScenePlayback playback, ScheduledEvent event) {
		EventContext ctx = new EventContext(server, engine, playback.scene().id(), playback.tick());
		boolean ok = engine.events().execute(event, ctx);
		if (!ok) {
			notifyOperators(Component.literal("[BTB] Scene '" + playback.scene().id() + "' tick " + playback.tick()
					+ ": event #" + event.index() + " (" + event.type() + ") failed — see server log")
					.withStyle(ChatFormatting.RED));
		}
		return ok;
	}

	private void finish(ScenePlayback playback) {
		String id = playback.scene().id();
		if (active.get(id) == playback) {
			active.remove(id);
			engine.bus().publish(new SceneMessages.Stopped(id, playback.tick(), playback.finished()));
			sendStateCue(id, playback.tick(), playback.finished() ? "finished" : "stopped");
			LOG.info("Scene '{}' {} at tick {} ({} failed event(s))", id,
					playback.finished() ? "finished" : "stopped", playback.tick(), playback.failures());
		}
	}

	private void sendStateCue(String sceneId, int tick, String state) {
		JsonObject data = new JsonObject();
		data.addProperty("state", state);
		engine.cues().queue(sceneId, tick, "scene_state", data);
	}

	private void notifyOperators(Component message) {
		if (server == null) {
			return;
		}
		LOG.warn(message.getString());
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
				player.sendSystemMessage(message);
			}
		}
	}

	// ------------------------------------------------------------------ queries

	public Optional<SceneDefinition> definition(String id) {
		return Optional.ofNullable(definitions.get(id));
	}

	public boolean isRunning(String id) {
		return active.containsKey(id);
	}

	public Collection<String> sceneIds() {
		return Collections.unmodifiableSet(definitions.keySet());
	}

	public Collection<ScenePlayback> activePlaybacks() {
		return Collections.unmodifiableCollection(active.values());
	}

	public Collection<String> activeIds() {
		return Collections.unmodifiableSet(active.keySet());
	}

	public Map<String, List<LoadProblem>> problems() {
		return Collections.unmodifiableMap(problemsByFile);
	}
}
