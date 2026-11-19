package de.behindtheblocks.cinematic.core;

import de.behindtheblocks.cinematic.command.BtbCommands;
import de.behindtheblocks.cinematic.command.CommandContributor;
import de.behindtheblocks.cinematic.core.bus.CinematicBus;
import de.behindtheblocks.cinematic.event.EventManager;
import de.behindtheblocks.cinematic.network.CueBroadcaster;
import de.behindtheblocks.cinematic.scene.SceneManager;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Central hub of the server-side cinematic system.
 *
 * <p>Holds every manager, wires them to Fabric lifecycle events and ticks them in a fixed order:
 * <ol>
 *   <li>{@link SceneManager} advances all timelines (fires events, which may queue cues)</li>
 *   <li>all other modules tick (camera paths, showcases, portals, ...)</li>
 *   <li>{@link CueBroadcaster} flushes queued client cues — last, so everything produced in this
 *       tick reaches the clients in a single batch</li>
 * </ol>
 * A module that throws during its tick is logged and skipped for that tick; it can't take the
 * server down.
 */
public final class CinematicEngine {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/engine");

	private final CinematicBus bus = new CinematicBus();
	private final EventManager events = new EventManager();
	private final SceneManager scenes = new SceneManager();
	private final CueBroadcaster cues = new CueBroadcaster();
	private final List<CinematicModule> modules = new ArrayList<>();
	private final List<CommandContributor> commandContributors = new ArrayList<>();
	private MinecraftServer server;
	private boolean initialized;

	public CinematicEngine() {
		modules.add(events);
		modules.add(scenes);
	}

	/** Adds a module. Must be called before {@link #initialize()}. */
	public void addModule(CinematicModule module) {
		if (initialized) {
			throw new IllegalStateException("Modules must be added before initialization");
		}
		modules.add(module);
		if (module instanceof CommandContributor c) {
			commandContributors.add(c);
		}
	}

	public void initialize() {
		if (initialized) {
			return;
		}
		initialized = true;
		modules.add(cues); // always last: flushes what the others produced
		for (CinematicModule m : modules) {
			m.initialize(this);
			LOG.debug("Initialized module {}", m.id());
		}

		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			this.server = s;
			modules.forEach(m -> guarded(m, "start", () -> m.onServerStarted(s)));
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			modules.forEach(m -> guarded(m, "stop", () -> m.onServerStopping(s)));
			this.server = null;
		});
		ServerTickEvents.END_SERVER_TICK.register(s -> {
			// frozen world (export, replay pause, /tick freeze): scenes, replays and effects wait too –
			// a step (/tick step, export frame) lets exactly that many ticks through
			if (!s.tickRateManager().runsNormally()) {
				return;
			}
			for (CinematicModule m : modules) {
				guarded(m, "tick", () -> m.tick(s));
			}
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) ->
				BtbCommands.register(dispatcher, buildContext, this, commandContributors));

		LOG.info("Cinematic engine ready with modules {}", modules.stream().map(CinematicModule::id).toList());
	}

	private static void guarded(CinematicModule module, String phase, Runnable r) {
		try {
			r.run();
		} catch (RuntimeException e) {
			LOG.error("Module '{}' failed during {}", module.id(), phase, e);
		}
	}

	public CinematicBus bus() {
		return bus;
	}

	public EventManager events() {
		return events;
	}

	public SceneManager scenes() {
		return scenes;
	}

	/** The scene-synchronised scheduler (always present). */
	public de.behindtheblocks.cinematic.timeline.TimelineManager timeline() {
		return module(de.behindtheblocks.cinematic.timeline.TimelineManager.class).orElseThrow();
	}

	public CueBroadcaster cues() {
		return cues;
	}

	public List<CinematicModule> modules() {
		return List.copyOf(modules);
	}

	public Optional<MinecraftServer> server() {
		return Optional.ofNullable(server);
	}

	@SuppressWarnings("unchecked")
	public <T extends CinematicModule> Optional<T> module(Class<T> type) {
		return modules.stream().filter(type::isInstance).map(m -> (T) m).findFirst();
	}
}
