package de.behindtheblocks.cinematic.core.bus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Small synchronous publish/subscribe bus used by the managers to talk to each other without
 * holding direct references (e.g. the SceneManager announces a reset, the destruction module
 * restores its block backups, the portal module returns to INACTIVE).
 *
 * <p>Delivery is synchronous on the publishing thread (always the server thread for server-side
 * messages), in subscription order. A failing subscriber is logged and does not prevent delivery
 * to the remaining subscribers.
 */
public final class CinematicBus {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/bus");

	private final Map<Class<?>, List<Consumer<?>>> subscribers = new ConcurrentHashMap<>();

	public <T extends BusMessage> void subscribe(Class<T> type, Consumer<? super T> listener) {
		subscribers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(listener);
	}

	@SuppressWarnings("unchecked")
	public void publish(BusMessage message) {
		List<Consumer<?>> list = subscribers.get(message.getClass());
		if (list == null) {
			return;
		}
		for (Consumer<?> c : list) {
			try {
				((Consumer<BusMessage>) c).accept(message);
			} catch (RuntimeException e) {
				LOG.error("Subscriber failed while handling {}", message, e);
			}
		}
	}

	public void clear() {
		subscribers.clear();
	}
}
