package de.behindtheblocks.cinematic.scene;

import java.util.List;

/**
 * Immutable, validated scene loaded from JSON. Running playbacks hold a reference to the
 * definition they were started with, so a reload never changes a take that is in progress.
 *
 * @param duration length in ticks; the scene finishes after this tick
 * @param events   events sorted by (time, index)
 * @param problems non-fatal warnings collected while loading
 */
public record SceneDefinition(
		String id,
		String description,
		int duration,
		boolean loop,
		ErrorPolicy errorPolicy,
		List<ScheduledEvent> events,
		List<LoadProblem> problems,
		String source) {

	public SceneDefinition {
		events = List.copyOf(events);
		problems = List.copyOf(problems);
	}
}
