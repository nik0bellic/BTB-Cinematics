package de.behindtheblocks.cinematic.scene;

import com.google.gson.JsonObject;
import de.behindtheblocks.cinematic.event.CinematicAction;

/**
 * One compiled event on a scene timeline.
 *
 * @param index  position in the scene file's {@code events} array (stable tie-breaker for ordering)
 * @param time   scene tick at which the event fires
 * @param type   event type id
 * @param action pre-validated executable action
 * @param raw    original JSON (kept for diagnostics and for client cue forwarding)
 */
public record ScheduledEvent(int index, int time, String type, CinematicAction action, JsonObject raw) {
}
