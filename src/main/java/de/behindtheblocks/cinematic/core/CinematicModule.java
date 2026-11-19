package de.behindtheblocks.cinematic.core;

import net.minecraft.server.MinecraftServer;

/**
 * A server-side subsystem managed by the {@link CinematicEngine}. Every manager (scene, camera,
 * sound, portal, ...) implements this so the engine can drive its lifecycle uniformly.
 */
public interface CinematicModule {
	/** Stable short id used in logs, e.g. {@code "scene"}. */
	String id();

	/** Called once while the mod initializes, before any server exists. Register event types here. */
	default void initialize(CinematicEngine engine) {
	}

	default void onServerStarted(MinecraftServer server) {
	}

	/** Called at the end of every server tick, after scene playback advanced. */
	default void tick(MinecraftServer server) {
	}

	default void onServerStopping(MinecraftServer server) {
	}
}
