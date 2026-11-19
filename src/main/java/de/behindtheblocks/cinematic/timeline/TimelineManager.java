package de.behindtheblocks.cinematic.timeline;

import de.behindtheblocks.cinematic.core.CinematicEngine;
import de.behindtheblocks.cinematic.core.CinematicModule;
import de.behindtheblocks.cinematic.core.bus.SceneMessages;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * Scene-synchronised scheduler for multi-step sequences that modules run on the server
 * (portal activation, collapsing structures, showcase choreography ...).
 *
 * <p>Tasks belong to an owner: a scene id, or {@link #MANUAL} for things started by commands.
 * Tasks of a paused scene do not advance; tasks of a stopped or reset scene are cancelled, so a
 * reset never lets half a collapse finish afterwards. All methods run on the server thread.
 */
public final class TimelineManager implements CinematicModule {
	/** Owner id for tasks not bound to a scene. They are never paused. */
	public static final String MANUAL = "";

	/** Scene whose {@code command} event is executing right now (server thread), else null. */
	private static final ThreadLocal<String> COMMAND_SCENE = new ThreadLocal<>();

	/**
	 * Runs a command on behalf of a scene: everything it starts (effects, texts, camera, delayed
	 * tasks) belongs to that scene – it pauses, stops and resets with it, like native events.
	 */
	public static void runForScene(String sceneId, Runnable r) {
		String prev = COMMAND_SCENE.get();
		COMMAND_SCENE.set(sceneId);
		try {
			r.run();
		} finally {
			COMMAND_SCENE.set(prev);
		}
	}

	/** The scene whose command is executing right now, or null (used by the replay recorder). */
	public static String currentCommandScene() {
		return COMMAND_SCENE.get();
	}

	/** {@link #MANUAL} becomes the scene currently running a command, if any. */
	public static String effectiveOwner(String owner) {
		String s = COMMAND_SCENE.get();
		return (owner == null || owner.isEmpty()) && s != null ? s : owner;
	}
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/timeline");

	/** Handle to cancel a scheduled task or repeating track. */
	public interface Handle {
		void cancel();

		boolean isDone();
	}

	private final class Task implements Handle {
		final String owner;
		final String label;
		int remaining;
		final int repeat; // 0 = once; otherwise run every tick for `repeat` ticks
		int step;
		final IntConsumer action;
		boolean cancelled;
		boolean done;

		Task(String owner, String label, int delay, int repeat, IntConsumer action) {
			this.owner = owner;
			this.label = label;
			this.remaining = delay;
			this.repeat = repeat;
			this.action = action;
		}

		@Override
		public void cancel() {
			cancelled = true;
		}

		@Override
		public boolean isDone() {
			return done || cancelled;
		}
	}

	private final List<Task> tasks = new ArrayList<>();
	private final List<Task> added = new ArrayList<>();
	private final Set<String> paused = new HashSet<>();

	@Override
	public String id() {
		return "timeline";
	}

	@Override
	public void initialize(CinematicEngine engine) {
		engine.bus().subscribe(SceneMessages.Paused.class, m -> paused.add(m.sceneId()));
		engine.bus().subscribe(SceneMessages.Resumed.class, m -> paused.remove(m.sceneId()));
		engine.bus().subscribe(SceneMessages.Stopped.class, m -> {
			paused.remove(m.sceneId());
			// A finished scene lets its sequences complete; a manually stopped one cancels them.
			if (!m.finished()) {
				cancelOwner(m.sceneId());
			}
		});
		engine.bus().subscribe(SceneMessages.Reset.class, m -> cancelOwner(m.sceneId()));
	}

	/** Runs {@code action} once after {@code delayTicks} (0 = at the end of this tick). */
	public Handle schedule(String owner, String label, int delayTicks, Runnable action) {
		return add(new Task(effectiveOwner(owner), label, Math.max(0, delayTicks), 0, step -> action.run()));
	}

	/**
	 * Runs {@code action} every tick for {@code durationTicks} ticks, starting after
	 * {@code delayTicks}. The consumer receives the step index 0..duration-1.
	 */
	public Handle repeat(String owner, String label, int delayTicks, int durationTicks, IntConsumer action) {
		return add(new Task(effectiveOwner(owner), label, Math.max(0, delayTicks), Math.max(1, durationTicks), action));
	}

	private Handle add(Task task) {
		added.add(task);
		return task;
	}

	public void cancelOwner(String owner) {
		tasks.forEach(t -> {
			if (t.owner.equals(owner)) {
				t.cancel();
			}
		});
		added.forEach(t -> {
			if (t.owner.equals(owner)) {
				t.cancel();
			}
		});
	}

	public int activeCount() {
		return (int) tasks.stream().filter(t -> !t.isDone()).count() + added.size();
	}

	@Override
	public void tick(MinecraftServer server) {
		tasks.addAll(added);
		added.clear();
		Iterator<Task> it = tasks.iterator();
		while (it.hasNext()) {
			Task t = it.next();
			if (t.cancelled) {
				it.remove();
				continue;
			}
			if (!t.owner.equals(MANUAL) && paused.contains(t.owner)) {
				continue;
			}
			if (t.remaining > 0) {
				t.remaining--;
				continue;
			}
			try {
				t.action.accept(t.step);
			} catch (RuntimeException e) {
				LOG.error("Timeline task '{}' (owner '{}') failed; cancelled", t.label, t.owner, e);
				t.cancel();
			}
			t.step++;
			if (t.repeat == 0 || t.step >= t.repeat) {
				t.done = true;
				it.remove();
			}
		}
	}

	@Override
	public void onServerStopping(MinecraftServer server) {
		tasks.clear();
		added.clear();
		paused.clear();
	}
}
