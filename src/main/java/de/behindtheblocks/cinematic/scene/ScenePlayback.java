package de.behindtheblocks.cinematic.scene;

import java.util.List;

/**
 * Deterministic playback cursor for one running scene.
 *
 * <p>Timing model (all in server ticks):
 * <ul>
 *   <li>{@link #start} fires every event with {@code time == 0} immediately.</li>
 *   <li>Each {@link #advance} increments the tick by one and fires every event whose time has been
 *       reached, in (time, file order). Because the cursor walks a sorted list, no event can be
 *       skipped or fire twice, even when several events share a tick.</li>
 *   <li>When the tick reaches {@code duration} the scene finishes (or restarts if looping).</li>
 *   <li>While paused, {@code advance} does nothing — the scene clock is frozen.</li>
 * </ul>
 *
 * <p>This class has no Minecraft dependencies; the {@link Sink} decides what "firing" means.
 */
public final class ScenePlayback {
	/** Receives events as the cursor reaches them. Return false to signal a failed event. */
	public interface Sink {
		boolean fire(ScenePlayback playback, ScheduledEvent event);
	}

	private final SceneDefinition scene;
	private final List<ScheduledEvent> events;
	private PlaybackState state = PlaybackState.PAUSED;
	private int tick;
	private int cursor;
	private int loops;
	private int failures;
	private boolean finished;

	public ScenePlayback(SceneDefinition scene) {
		this.scene = scene;
		this.events = scene.events();
	}

	public void start(Sink sink) {
		start(sink, 0);
	}

	/** Starts at the given tick: everything before it is skipped (jump into the middle of a scene). */
	public void start(Sink sink, int fromTick) {
		tick = Math.max(0, Math.min(fromTick, scene.duration() - 1));
		cursor = 0;
		while (cursor < events.size() && events.get(cursor).time() < tick) {
			cursor++;
		}
		state = PlaybackState.RUNNING;
		fireDue(sink);
	}

	/**
	 * Advances one tick.
	 *
	 * @return {@code true} if the playback is still active afterwards
	 */
	public boolean advance(Sink sink) {
		if (state != PlaybackState.RUNNING) {
			return state != PlaybackState.STOPPED;
		}
		tick++;
		fireDue(sink);
		if (state == PlaybackState.STOPPED) {
			return false; // an event (or the error policy) stopped us
		}
		if (tick >= scene.duration()) {
			if (scene.loop()) {
				loops++;
				start(sink);
			} else {
				finished = true;
				state = PlaybackState.STOPPED;
				return false;
			}
		}
		return true;
	}

	private void fireDue(Sink sink) {
		while (cursor < events.size() && events.get(cursor).time() <= tick && state == PlaybackState.RUNNING) {
			ScheduledEvent e = events.get(cursor++);
			if (!sink.fire(this, e)) {
				failures++;
				if (scene.errorPolicy() == ErrorPolicy.STOP) {
					state = PlaybackState.STOPPED;
				}
			}
		}
	}

	public boolean pause() {
		if (state != PlaybackState.RUNNING) {
			return false;
		}
		state = PlaybackState.PAUSED;
		return true;
	}

	public boolean resume() {
		if (state != PlaybackState.PAUSED) {
			return false;
		}
		state = PlaybackState.RUNNING;
		return true;
	}

	public void stop() {
		state = PlaybackState.STOPPED;
	}

	public SceneDefinition scene() {
		return scene;
	}

	public PlaybackState state() {
		return state;
	}

	public int tick() {
		return tick;
	}

	public int loops() {
		return loops;
	}

	public int failures() {
		return failures;
	}

	/** True if the playback ended by reaching its duration (as opposed to being stopped). */
	public boolean finished() {
		return finished;
	}
}
