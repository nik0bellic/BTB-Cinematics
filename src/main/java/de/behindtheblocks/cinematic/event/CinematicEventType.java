package de.behindtheblocks.cinematic.event;

import de.behindtheblocks.cinematic.json.JsonParamException;
import de.behindtheblocks.cinematic.json.JsonParams;

/**
 * A kind of timeline event that can appear in a scene file ({@code "type": "..."}).
 *
 * <p>Parameters are validated and compiled once when the scene is loaded ({@link #prepare}). The
 * returned {@link CinematicAction} is then executed every time the timeline reaches the event.
 * This keeps runtime work minimal and surfaces authoring mistakes at load time.
 */
public interface CinematicEventType {
	/** The value of {@code "type"} in scene JSON, lowercase snake_case. */
	String id();

	/** One-line description shown by {@code /btb events}. */
	String description();

	/**
	 * Validates the event's JSON parameters and builds the action to run.
	 *
	 * @throws JsonParamException with a user-facing message if parameters are invalid
	 */
	CinematicAction prepare(JsonParams params);
}
