package de.behindtheblocks.cinematic.event.builtin;

import de.behindtheblocks.cinematic.json.JsonParamException;
import de.behindtheblocks.cinematic.json.JsonParams;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the {@code "players"} field of an event: {@code "all"} (default) or a list of player
 * names. Missing players are logged, not fatal — an actor may simply not be online during a test.
 */
public record PlayerTargets(boolean all, List<String> names) {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/targets");

	public static PlayerTargets parse(JsonParams p) {
		List<String> list = p.getStringList("players");
		if (list.isEmpty() || list.contains("all") || list.contains("@a")) {
			return new PlayerTargets(true, List.of());
		}
		for (String n : list) {
			if (n.isBlank() || n.length() > 16) {
				throw new JsonParamException("'players' contains invalid player name '" + n + "'");
			}
		}
		return new PlayerTargets(false, list);
	}

	public List<ServerPlayer> resolve(MinecraftServer server) {
		if (all) {
			return List.copyOf(server.getPlayerList().getPlayers());
		}
		List<ServerPlayer> out = new ArrayList<>();
		for (String name : names) {
			ServerPlayer p = server.getPlayerList().getPlayerByName(name);
			if (p != null) {
				out.add(p);
			} else {
				LOG.warn("Player '{}' is not online", name);
			}
		}
		return out;
	}
}
