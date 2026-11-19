package de.behindtheblocks.cinematic.scene;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import de.behindtheblocks.cinematic.event.CinematicAction;
import de.behindtheblocks.cinematic.event.CinematicEventType;
import de.behindtheblocks.cinematic.json.JsonParamException;
import de.behindtheblocks.cinematic.json.JsonParams;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Parses and validates scene JSON.
 *
 * <pre>{@code
 * {
 *   "id": "portal_activation",          // optional, defaults to the file name
 *   "description": "...",                // optional
 *   "duration": 300,                     // ticks, required
 *   "loop": false,                       // optional
 *   "on_error": "continue",              // continue | stop
 *   "strict": false,                     // true: any invalid event rejects the whole scene
 *   "bpm": 120, "beat_offset": 0,        // optional: events may use "beat" (and "bar") instead of "time"
 *   "beats_per_bar": 4,
 *   "events": [ { "time": 0, "type": "...", ...params } ]
 * }
 * }</pre>
 *
 * <p>Independent of Minecraft so it can be unit-tested; event types are looked up through a
 * function supplied by the caller.
 */
public final class SceneLoader {
	public static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_]{1,64}");
	/** Upper bound to catch typos like 2400000 instead of 2400 (= 2 hours of footage). */
	public static final int MAX_DURATION = 20 * 60 * 60 * 2;

	private final Function<String, Optional<CinematicEventType>> typeLookup;
	private double bpm;
	private double beatOffset;
	private double beatsPerBar = 4;

	public SceneLoader(Function<String, Optional<CinematicEventType>> typeLookup) {
		this.typeLookup = typeLookup;
	}

	/** Result: either a definition (possibly with warnings) or a list of fatal errors. */
	public record Result(Optional<SceneDefinition> scene, List<LoadProblem> problems) {
	}

	public Result load(String fallbackId, String jsonText, String source) {
		List<LoadProblem> problems = new ArrayList<>();
		JsonObject root;
		try {
			JsonReader reader = new JsonReader(new StringReader(jsonText));
			reader.setStrictness(Strictness.LENIENT); // allows // comments in hand-written files
			JsonElement parsed = JsonParser.parseReader(reader);
			if (!parsed.isJsonObject()) {
				return fail(problems, "root must be a JSON object");
			}
			root = parsed.getAsJsonObject();
		} catch (JsonParseException | IllegalStateException e) {
			return fail(problems, "invalid JSON: " + rootMessage(e));
		}

		JsonParams p = new JsonParams(root);
		String id;
		int duration;
		boolean loop;
		boolean strict;
		ErrorPolicy policy;
		String description;
		try {
			id = p.getString("id", fallbackId);
			if (!ID_PATTERN.matcher(id).matches()) {
				return fail(problems, "id '" + id + "' must match [a-z0-9_]{1,64}");
			}
			if (!id.equals(fallbackId)) {
				problems.add(LoadProblem.warning("id '" + id + "' differs from file name '" + fallbackId + "'"));
			}
			duration = p.requireInt("duration");
			if (duration < 1 || duration > MAX_DURATION) {
				return fail(problems, "duration must be between 1 and " + MAX_DURATION + " ticks");
			}
			loop = p.getBoolean("loop", false);
			strict = p.getBoolean("strict", false);
			policy = p.getEnum("on_error", ErrorPolicy.class, ErrorPolicy.CONTINUE);
			description = p.getString("description", "");
		} catch (JsonParamException e) {
			return fail(problems, e.getMessage());
		}

		double bpm, beatOffset, beatsPerBar;
		try {
			bpm = p.getDouble("bpm", 0);
			beatOffset = p.getDouble("beat_offset", 0);
			beatsPerBar = p.getDouble("beats_per_bar", 4);
			if (bpm < 0 || bpm > 400 || beatsPerBar < 1 || beatsPerBar > 32) {
				return fail(problems, "bpm 0..400, beats_per_bar 1..32");
			}
		} catch (JsonParamException e) {
			return fail(problems, e.getMessage());
		}
		this.bpm = bpm;
		this.beatOffset = beatOffset;
		this.beatsPerBar = beatsPerBar;
		List<ScheduledEvent> events = new ArrayList<>();
		if (root.has("events")) {
			if (!root.get("events").isJsonArray()) {
				return fail(problems, "'events' must be an array");
			}
			JsonArray arr = root.getAsJsonArray("events");
			for (int i = 0; i < arr.size(); i++) {
				parseEvent(i, arr.get(i), duration, events, problems);
			}
		}

		if (strict && problems.stream().anyMatch(pr -> pr.message().startsWith("event #"))) {
			problems.add(LoadProblem.error("scene is strict and has invalid events"));
			return new Result(Optional.empty(), List.copyOf(problems));
		}

		events.sort(Comparator.comparingInt(ScheduledEvent::time).thenComparingInt(ScheduledEvent::index));
		SceneDefinition def = new SceneDefinition(id, description, duration, loop, policy, events, problems, source);
		return new Result(Optional.of(def), List.copyOf(problems));
	}

	private void parseEvent(int index, JsonElement element, int duration, List<ScheduledEvent> out,
			List<LoadProblem> problems) {
		String prefix = "event #" + index;
		if (!element.isJsonObject()) {
			problems.add(LoadProblem.warning(prefix + ": must be an object, skipped"));
			return;
		}
		JsonObject obj = element.getAsJsonObject();
		JsonParams p = new JsonParams(obj);
		try {
			int time;
			if (!p.has("time") && (p.has("beat") || p.has("bar"))) {
				// Music sync: "bar" (0-based) and "beat" (0-based, may be fractional) at the scene's bpm.
				if (bpm <= 0) {
					throw new JsonParamException("uses 'beat'/'bar' but the scene has no 'bpm'");
				}
				double beat = p.getDouble("bar", 0) * beatsPerBar + p.getDouble("beat", 0);
				time = (int) Math.round(beatOffset + beat * 1200.0 / bpm);
			} else {
				time = p.requireInt("time");
			}
			String type = p.requireString("type");
			prefix += " (" + type + ")";
			if (time < 0 || time > duration) {
				problems.add(LoadProblem.warning(prefix + ": time " + time + " outside 0.." + duration + ", skipped"));
				return;
			}
			Optional<CinematicEventType> eventType = typeLookup.apply(type);
			if (eventType.isEmpty()) {
				problems.add(LoadProblem.warning(prefix + ": unknown event type, skipped"));
				return;
			}
			CinematicAction action = eventType.get().prepare(p);
			out.add(new ScheduledEvent(index, time, type, action, obj));
		} catch (JsonParamException e) {
			problems.add(LoadProblem.warning(prefix + ": " + e.getMessage() + ", skipped"));
		}
	}

	private static Result fail(List<LoadProblem> problems, String message) {
		problems.add(LoadProblem.error(message));
		return new Result(Optional.empty(), List.copyOf(problems));
	}

	private static String rootMessage(Throwable t) {
		Throwable c = t;
		while (c.getCause() != null) {
			c = c.getCause();
		}
		return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
	}
}
