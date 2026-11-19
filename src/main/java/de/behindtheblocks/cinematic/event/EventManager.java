package de.behindtheblocks.cinematic.event;

import de.behindtheblocks.cinematic.core.CinematicEngine;
import de.behindtheblocks.cinematic.core.CinematicModule;
import de.behindtheblocks.cinematic.scene.ScheduledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Registry and executor for timeline event types.
 *
 * <p>Every module registers the event types it provides during {@link CinematicModule#initialize}.
 * Execution is guarded: an exception inside one event is caught, logged and reported back to the
 * caller as {@code false} so the scene's error policy can decide whether to continue.
 */
public final class EventManager implements CinematicModule {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/events");
	private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_]+");

	private final Map<String, CinematicEventType> types = new TreeMap<>();

	@Override
	public String id() {
		return "events";
	}

	public synchronized void register(CinematicEventType type) {
		if (!ID_PATTERN.matcher(type.id()).matches()) {
			throw new IllegalArgumentException("Invalid event type id: " + type.id());
		}
		if (types.putIfAbsent(type.id(), type) != null) {
			throw new IllegalStateException("Event type registered twice: " + type.id());
		}
	}

	public Optional<CinematicEventType> find(String id) {
		return Optional.ofNullable(types.get(id));
	}

	public Collection<CinematicEventType> all() {
		return Collections.unmodifiableCollection(types.values());
	}

	/**
	 * Runs one scheduled event.
	 *
	 * @return {@code true} on success, {@code false} if the event threw
	 */
	public boolean execute(ScheduledEvent event, EventContext context) {
		try {
			event.action().execute(context);
			return true;
		} catch (Exception e) {
			LOG.error("Scene '{}' tick {}: event #{} ({}) failed", context.sceneId(), context.tick(),
					event.index(), event.type(), e);
			return false;
		}
	}

	@Override
	public void initialize(CinematicEngine engine) {
		// Event types are registered by the modules that own them.
	}
}
