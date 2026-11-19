package de.behindtheblocks.cinematic.event;

import de.behindtheblocks.cinematic.core.CinematicEngine;
import net.minecraft.server.MinecraftServer;

/**
 * Everything an executing event may need.
 *
 * @param sceneId id of the scene whose timeline fired the event
 * @param tick    the scene-local tick at which the event fired
 */
public record EventContext(MinecraftServer server, CinematicEngine engine, String sceneId, int tick) {
}
