package de.behindtheblocks.cinematic.network;

import com.google.gson.JsonObject;
import de.behindtheblocks.cinematic.core.CinematicModule;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Collects client cues during a server tick and flushes them at the end of the tick: per player,
 * one {@link CueBatchPayload} per (scene, tick). Players without the mod are skipped silently.
 */
public final class CueBroadcaster implements CinematicModule {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/cues");

	/** @param targets player UUIDs, or {@code null} for everyone online at flush time */
	private record Pending(String sceneId, int tick, ClientCue cue, Set<UUID> targets) {
	}

	private final List<Pending> pending = new ArrayList<>();

	@Override
	public String id() {
		return "cues";
	}

	/** Queues a cue for every player. Must be called on the server thread. */
	public void queue(String sceneId, int tick, String type, JsonObject data) {
		queue(sceneId, tick, type, data, null);
	}

	/**
	 * Queues a cue for specific players.
	 *
	 * @param targets recipients, or {@code null} for all players
	 */
	public void queue(String sceneId, int tick, String type, JsonObject data, Collection<ServerPlayer> targets) {
		sceneId = de.behindtheblocks.cinematic.timeline.TimelineManager.effectiveOwner(sceneId);
		String json = data.toString();
		if (json.length() > ClientCue.MAX_DATA_LENGTH) {
			LOG.warn("Cue '{}' from scene '{}' is too large ({} chars) and was dropped", type, sceneId, json.length());
			return;
		}
		Set<UUID> ids = targets == null ? null : targets.stream().map(ServerPlayer::getUUID).collect(Collectors.toSet());
		pending.add(new Pending(sceneId, tick, new ClientCue(type, json), ids));
	}

	@Override
	public void tick(MinecraftServer server) {
		flush(server);
	}

	public void flush(MinecraftServer server) {
		if (pending.isEmpty()) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!ServerPlayNetworking.canSend(player, CueBatchPayload.TYPE)) {
				continue;
			}
			Map<String, List<ClientCue>> byKey = new LinkedHashMap<>();
			Map<String, Pending> keyInfo = new LinkedHashMap<>();
			for (Pending p : pending) {
				if (p.targets() != null && !p.targets().contains(player.getUUID())) {
					continue;
				}
				String key = p.sceneId() + '\u0000' + p.tick();
				byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(p.cue());
				keyInfo.putIfAbsent(key, p);
			}
			for (Map.Entry<String, List<ClientCue>> e : byKey.entrySet()) {
				Pending info = keyInfo.get(e.getKey());
				List<ClientCue> cues = e.getValue();
				// Split oversized batches instead of dropping cues.
				for (int i = 0; i < cues.size(); i += CueBatchPayload.MAX_CUES) {
					List<ClientCue> chunk = cues.subList(i, Math.min(cues.size(), i + CueBatchPayload.MAX_CUES));
					ServerPlayNetworking.send(player, new CueBatchPayload(info.sceneId(), info.tick(), chunk));
				}
			}
		}
		pending.clear();
	}
}
