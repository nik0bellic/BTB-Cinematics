package de.behindtheblocks.cinematic.client;

import com.google.gson.JsonObject;
import de.behindtheblocks.cinematic.client.network.ClientCueDispatcher;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client mirror of the server's scene playback states, fed by {@code scene_state} cues.
 *
 * <p>Client-side animation modules (camera paths, screen effects, HUD text) ask
 * {@link #isPaused(String)} to freeze their own clocks while the server scene is paused, and
 * subscribe to resets to clear their state.
 */
public final class ClientSceneState {
	public enum State { RUNNING, PAUSED }

	/** Notified when a scene stops, finishes or is reset on the server. */
	@FunctionalInterface
	public interface EndListener {
		void onSceneEnded(String sceneId, boolean reset);
	}

	private static final Map<String, State> STATES = new ConcurrentHashMap<>();
	private static final java.util.List<EndListener> END_LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

	private ClientSceneState() {
	}

	public static void init() {
		ClientCueDispatcher.register("scene_state", ClientSceneState::onCue);
	}

	private static final java.util.List<java.util.function.Consumer<String>> STOP_LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

	/** Called only when a scene is stopped by hand (not when it finishes or is reset). */
	public static void onSceneStopped(java.util.function.Consumer<String> listener) {
		STOP_LISTENERS.add(listener);
	}

	public static void onSceneEnded(EndListener listener) {
		END_LISTENERS.add(listener);
	}

	private static void onCue(String sceneId, int tick, JsonObject data) {
		String state = data.has("state") ? data.get("state").getAsString() : "";
		switch (state) {
			case "started", "resumed" -> STATES.put(sceneId, State.RUNNING);
			case "paused" -> STATES.put(sceneId, State.PAUSED);
			case "stopped", "finished", "reset" -> {
				STATES.remove(sceneId);
				boolean reset = state.equals("reset");
				END_LISTENERS.forEach(l -> l.onSceneEnded(sceneId, reset));
				if (state.equals("stopped")) {
					STOP_LISTENERS.forEach(l -> l.accept(sceneId));
				}
			}
			default -> { /* ignore unknown states from newer servers */ }
		}
	}

	public static boolean isPaused(String sceneId) {
		return STATES.get(sceneId) == State.PAUSED;
	}

	public static boolean isRunning(String sceneId) {
		return STATES.containsKey(sceneId);
	}

	/** Clears everything, e.g. when leaving a world. */
	public static void clear() {
		STATES.clear();
	}
}
