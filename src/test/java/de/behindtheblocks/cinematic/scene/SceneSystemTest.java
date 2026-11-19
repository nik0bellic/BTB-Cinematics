package de.behindtheblocks.cinematic.scene;

import de.behindtheblocks.cinematic.event.CinematicAction;
import de.behindtheblocks.cinematic.event.CinematicEventType;
import de.behindtheblocks.cinematic.json.JsonParamException;
import de.behindtheblocks.cinematic.json.JsonParams;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Loader validation and deterministic playback timing, without Minecraft. */
class SceneSystemTest {
	/** Test event type "mark": requires a 'name' string. */
	private static final CinematicEventType MARK = new CinematicEventType() {
		public String id() { return "mark"; }
		public String description() { return "test"; }
		public CinematicAction prepare(JsonParams p) {
			p.requireString("name");
			return ctx -> { };
		}
	};
	private static final CinematicEventType FAIL = new CinematicEventType() {
		public String id() { return "fail"; }
		public String description() { return "test"; }
		public CinematicAction prepare(JsonParams p) { return ctx -> { throw new IllegalStateException("boom"); }; }
	};

	private final SceneLoader loader = new SceneLoader(id -> switch (id) {
		case "mark" -> Optional.of(MARK);
		case "fail" -> Optional.of(FAIL);
		default -> Optional.empty();
	});

	private SceneDefinition load(String json) {
		SceneLoader.Result r = loader.load("test", json, "test.json");
		assertTrue(r.scene().isPresent(), () -> "expected scene, problems: " + r.problems());
		return r.scene().get();
	}

	/** Runs a playback and records "tick:name" for every fired event. */
	private static List<String> run(ScenePlayback pb, int maxTicks) {
		List<String> fired = new ArrayList<>();
		ScenePlayback.Sink sink = (p, e) -> {
			fired.add(p.tick() + ":" + (e.raw().has("name") ? e.raw().get("name").getAsString() : e.type()));
			return !e.type().equals("fail");
		};
		pb.start(sink);
		for (int i = 0; i < maxTicks && pb.advance(sink); i++) { }
		return fired;
	}

	@Test
	void eventsFireInTimeThenFileOrder() {
		SceneDefinition s = load("""
				{ "duration": 10, "events": [
				  {"time": 5, "type": "mark", "name": "c"},
				  {"time": 0, "type": "mark", "name": "a"},
				  {"time": 5, "type": "mark", "name": "d"},
				  {"time": 1, "type": "mark", "name": "b"},
				  {"time": 10, "type": "mark", "name": "e"} ] }""");
		ScenePlayback pb = new ScenePlayback(s);
		assertEquals(List.of("0:a", "1:b", "5:c", "5:d", "10:e"), run(pb, 100));
		assertTrue(pb.finished());
		assertEquals(PlaybackState.STOPPED, pb.state());
	}

	@Test
	void pauseFreezesTheClock() {
		SceneDefinition s = load("""
				{ "duration": 10, "events": [ {"time": 3, "type": "mark", "name": "x"} ] }""");
		ScenePlayback pb = new ScenePlayback(s);
		List<String> fired = new ArrayList<>();
		ScenePlayback.Sink sink = (p, e) -> fired.add(p.tick() + ":x");
		pb.start(sink);
		pb.advance(sink);
		pb.advance(sink);
		assertTrue(pb.pause());
		for (int i = 0; i < 50; i++) {
			pb.advance(sink);
		}
		assertEquals(2, pb.tick());
		assertTrue(fired.isEmpty());
		assertTrue(pb.resume());
		pb.advance(sink);
		assertEquals(List.of("3:x"), fired);
	}

	@Test
	void loopingRestartsAndRefiresTimeZero() {
		SceneDefinition s = load("""
				{ "duration": 2, "loop": true, "events": [ {"time": 0, "type": "mark", "name": "z"} ] }""");
		ScenePlayback pb = new ScenePlayback(s);
		List<String> fired = run(pb, 6);
		assertEquals(List.of("0:z", "0:z", "0:z", "0:z"), fired);
		assertEquals(3, pb.loops());
	}

	@Test
	void errorPolicyStopHaltsScene() {
		SceneDefinition s = load("""
				{ "duration": 10, "on_error": "stop", "events": [
				  {"time": 1, "type": "fail"}, {"time": 2, "type": "mark", "name": "never"} ] }""");
		ScenePlayback pb = new ScenePlayback(s);
		assertEquals(List.of("1:fail"), run(pb, 100));
		assertFalse(pb.finished());
		assertEquals(1, pb.failures());
	}

	@Test
	void errorPolicyContinueKeepsPlaying() {
		SceneDefinition s = load("""
				{ "duration": 10, "events": [
				  {"time": 1, "type": "fail"}, {"time": 2, "type": "mark", "name": "after"} ] }""");
		ScenePlayback pb = new ScenePlayback(s);
		assertEquals(List.of("1:fail", "2:after"), run(pb, 100));
		assertTrue(pb.finished());
	}

	@Test
	void invalidEventsAreSkippedWithWarnings() {
		SceneLoader.Result r = loader.load("test", """
				{ "duration": 10, "events": [
				  {"time": 1, "type": "unknown_type"},
				  {"time": 2, "type": "mark"},
				  {"time": 99, "type": "mark", "name": "late"},
				  {"time": 3, "type": "mark", "name": "ok"} ] }""", "t");
		assertTrue(r.scene().isPresent());
		assertEquals(1, r.scene().get().events().size());
		assertEquals(3, r.problems().size());
		assertTrue(r.problems().get(1).message().contains("missing required field 'name'"));
	}

	@Test
	void strictSceneIsRejected() {
		SceneLoader.Result r = loader.load("test", """
				{ "duration": 10, "strict": true, "events": [ {"time": 1, "type": "nope"} ] }""", "t");
		assertTrue(r.scene().isEmpty());
	}

	@Test
	void fatalErrors() {
		assertTrue(loader.load("t", "{ not json", "t").scene().isEmpty());
		assertTrue(loader.load("t", "{\"duration\": 0}", "t").scene().isEmpty());
		assertTrue(loader.load("t", "{\"duration\": 10, \"id\": \"Bad-Id\"}", "t").scene().isEmpty());
		assertTrue(loader.load("t", "{\"duration\": \"long\"}", "t").scene().isEmpty());
	}

	@Test
	void commentsAreAllowed() {
		load("// header comment\n{ \"duration\": 5 }");
	}

	@Test
	void jsonParamsTypeChecks() {
		JsonParams p = new JsonParams(com.google.gson.JsonParser.parseString(
				"{\"pos\":[1,2],\"n\":1.5,\"s\":3}").getAsJsonObject());
		assertThrows(JsonParamException.class, () -> p.requireDoubleArray("pos", 3));
		assertThrows(JsonParamException.class, () -> p.requireInt("n"));
		assertThrows(JsonParamException.class, () -> p.requireString("s"));
		assertEquals(7, p.getInt("missing", 7));
	}

	@Test
	void beatSyncConvertsBeatsToTicks() {
		// 120 bpm → one beat = 10 ticks; bar 2 beat 1 (4/4) = beat 9 → tick 90 (+ offset 5)
		SceneDefinition s = load("""
				{ "duration": 200, "bpm": 120, "beat_offset": 5, "events": [
				  {"bar": 2, "beat": 1, "type": "mark", "name": "drop"}, {"beat": 0.5, "type": "mark", "name": "half"} ] }""");
		assertEquals(10, s.events().get(0).time());
		assertEquals(95, s.events().get(1).time());
	}
}
