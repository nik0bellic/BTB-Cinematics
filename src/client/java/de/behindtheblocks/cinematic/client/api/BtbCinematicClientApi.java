package de.behindtheblocks.cinematic.client.api;

import de.behindtheblocks.cinematic.client.ClientSceneState;
import de.behindtheblocks.cinematic.client.network.ClientCueDispatcher;
import de.behindtheblocks.cinematic.client.timeline.ClientTimeline;

/**
 * Client-side API for other mods: render your own overlays/menus in sync with BTB scenes.
 *
 * <pre>{@code
 * BtbCinematicClientApi.registerCue("mymod:map_reveal", (sceneId, tick, data) -> MyOverlay.show(data));
 * }</pre>
 * Trigger it from a scene: {@code {"time": 40, "type": "client_cue", "cue": "mymod:map_reveal", "data": {...}}}.
 */
public final class BtbCinematicClientApi {
	private BtbCinematicClientApi() {
	}

	public static void registerCue(String cueType, ClientCueDispatcher.CueHandler handler) {
		ClientCueDispatcher.register(cueType, handler);
	}

	/** Scene clock in ticks incl. partial tick; freezes while the scene is paused. Use for your animations. */
	public static double sceneTime(String sceneId, float partialTick) {
		return ClientTimeline.now(sceneId, partialTick);
	}

	public static boolean isScenePaused(String sceneId) {
		return ClientSceneState.isPaused(sceneId);
	}
}
