package de.behindtheblocks.cinematic.client.timeline;

import de.behindtheblocks.cinematic.client.ClientSceneState;
import net.minecraft.client.Minecraft;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side animation clocks, one per scene plus a global one for manual (command) cues.
 *
 * <p>A scene clock only advances while the server says the scene is running, so every client
 * animation (camera path, fade, HUD text) freezes exactly when the scene is paused and resumes
 * in sync. Clocks advance in whole client ticks; {@link #now} adds the frame's partial tick for
 * smooth motion at any frame rate.
 */
public final class ClientTimeline {
	private static final Map<String, long[]> CLOCKS = new ConcurrentHashMap<>();

	private ClientTimeline() {
	}

	/** Called once per client tick. */
	public static void tick(Minecraft mc) {
		if (mc.isPaused()) {
			return;
		}
		CLOCKS.computeIfAbsent("", k -> new long[1])[0]++;
		CLOCKS.forEach((scene, clock) -> {
			if (!scene.isEmpty() && !ClientSceneState.isPaused(scene)) {
				clock[0]++;
			}
		});
	}

	private static boolean frozen(String sceneId) {
		return Minecraft.getInstance().isPaused() || (!sceneId.isEmpty() && ClientSceneState.isPaused(sceneId));
	}

	/** Current time of a clock in ticks, including the partial tick of the current frame. */
	public static double now(String sceneId, float partialTick) {
		long ticks = CLOCKS.computeIfAbsent(sceneId, k -> new long[1])[0];
		return frozen(sceneId) ? ticks : ticks + partialTick;
	}

	/** Partial tick of the current frame (0..1). */
	public static float partial() {
		return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
	}

	/** A point on a clock: remembers its start and reports elapsed time. */
	public record Stamp(String sceneId, double start) {
		public static Stamp now(String sceneId) {
			return new Stamp(sceneId, ClientTimeline.now(sceneId, 0f));
		}

		public double elapsed(float partialTick) {
			return ClientTimeline.now(sceneId, partialTick) - start;
		}
	}
}
