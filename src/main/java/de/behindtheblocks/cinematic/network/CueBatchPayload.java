package de.behindtheblocks.cinematic.network;

import de.behindtheblocks.cinematic.BtbCinematic;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Server → client: all cues produced by one scene in one server tick. Sending them as a batch
 * guarantees that cues scheduled for the same tick are applied in the same client frame
 * (e.g. a white flash and a camera cut stay perfectly in sync).
 *
 * @param sceneId scene that produced the cues, or {@code ""} for manual (command) cues
 * @param tick    scene tick the cues belong to
 */
public record CueBatchPayload(String sceneId, int tick, List<ClientCue> cues) implements CustomPacketPayload {
	public static final int MAX_CUES = 256;

	public static final Type<CueBatchPayload> TYPE =
			new Type<>(Identifier.fromNamespaceAndPath(BtbCinematic.MOD_ID, "cue_batch"));

	public static final StreamCodec<ByteBuf, CueBatchPayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(64), CueBatchPayload::sceneId,
			ByteBufCodecs.VAR_INT, CueBatchPayload::tick,
			ClientCue.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_CUES)), CueBatchPayload::cues,
			CueBatchPayload::new);

	public CueBatchPayload {
		cues = List.copyOf(cues);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
