package de.behindtheblocks.cinematic.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * Registers all custom payload types. Must run on both physical sides (common initializer).
 *
 * <p>Security model: the server is authoritative. Phase 1 only defines server → client payloads,
 * which carry presentation instructions and never cause world changes on the receiving side.
 * Every client → server payload added later (Director Mode) is permission-checked on the server.
 */
public final class BtbNetworking {
	private BtbNetworking() {
	}

	public static void registerPayloadTypes() {
		PayloadTypeRegistry.clientboundPlay().register(CueBatchPayload.TYPE, CueBatchPayload.STREAM_CODEC);
	}
}
