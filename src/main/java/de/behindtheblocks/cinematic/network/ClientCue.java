package de.behindtheblocks.cinematic.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A single instruction for the client-side presentation layer (screen effect, HUD text, camera
 * move, ...). {@code data} is a JSON object serialized as string so new cue types can be added
 * without changing the wire format.
 */
public record ClientCue(String type, String data) {
	public static final int MAX_TYPE_LENGTH = 64;
	public static final int MAX_DATA_LENGTH = 262_144; // director state with a full film set is ~30 kB; packets may be up to 1 MB

	public static final StreamCodec<ByteBuf, ClientCue> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(MAX_TYPE_LENGTH), ClientCue::type,
			ByteBufCodecs.stringUtf8(MAX_DATA_LENGTH), ClientCue::data,
			ClientCue::new);
}
