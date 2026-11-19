package de.behindtheblocks.cinematic.client.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.behindtheblocks.cinematic.network.ClientCue;
import de.behindtheblocks.cinematic.network.CueBatchPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receives {@link CueBatchPayload}s and routes each cue to the client module registered for its
 * type. Runs on the client (render) thread — Fabric invokes play receivers there.
 *
 * <p>Robustness: malformed JSON or a throwing handler only drops that one cue; an unknown cue type
 * is logged once (this happens if the server runs a newer mod version than the client).
 */
public final class ClientCueDispatcher {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/client-cues");

	/** Handles one cue type on the client. */
	@FunctionalInterface
	public interface CueHandler {
		void handle(String sceneId, int tick, JsonObject data);
	}

	private static final Map<String, CueHandler> HANDLERS = new ConcurrentHashMap<>();
	private static final Set<String> WARNED = new HashSet<>();

	private ClientCueDispatcher() {
	}

	public static void register(String cueType, CueHandler handler) {
		if (HANDLERS.putIfAbsent(cueType, handler) != null) {
			throw new IllegalStateException("Client cue handler registered twice: " + cueType);
		}
	}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(CueBatchPayload.TYPE,
				(payload, context) -> dispatch(payload));
	}

	static void dispatch(CueBatchPayload payload) {
		for (ClientCue cue : payload.cues()) {
			CueHandler handler = HANDLERS.get(cue.type());
			if (handler == null) {
				if (WARNED.add(cue.type())) {
					LOG.warn("No client handler for cue type '{}' (mod version mismatch?)", cue.type());
				}
				continue;
			}
			try {
				JsonElement json = JsonParser.parseString(cue.data());
				if (!json.isJsonObject()) {
					LOG.warn("Cue '{}' data is not an object", cue.type());
					continue;
				}
				handler.handle(payload.sceneId(), payload.tick(), json.getAsJsonObject());
			} catch (RuntimeException e) {
				LOG.error("Client cue '{}' from scene '{}' failed", cue.type(), payload.sceneId(), e);
			}
		}
	}
}
