package de.behindtheblocks.cinematic.core.bus;

/**
 * Lifecycle notifications published by the scene system. Other modules subscribe to these to
 * stay in sync with playback (freeze animations while paused, restore world state on reset, ...).
 */
public final class SceneMessages {
	private SceneMessages() {
	}

	public record Started(String sceneId) implements BusMessage {
	}

	public record Paused(String sceneId, int tick) implements BusMessage {
	}

	public record Resumed(String sceneId, int tick) implements BusMessage {
	}

	/** Playback ended, either because it was stopped or because it reached its duration. */
	public record Stopped(String sceneId, int tick, boolean finished) implements BusMessage {
	}

	/** The scene was reset: modules must undo everything the scene changed. */
	public record Reset(String sceneId) implements BusMessage {
	}

	/** Scene definitions were (re)loaded from disk. */
	public record DefinitionsReloaded(int sceneCount, int problemCount) implements BusMessage {
	}
}
