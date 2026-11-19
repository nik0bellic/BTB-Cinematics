package de.behindtheblocks.cinematic.showcase;

import java.util.Locale;

/** Animation presets for showcase objects. */
public enum ShowcasePreset {
	/** Gentle hover and slow turn. */
	FLOAT,
	/** Rotation around several axes. */
	ROTATE,
	/** Materialises from nothing: scale-in with overshoot, decelerating spin, burst. */
	REVEAL,
	/** Heartbeat: double pulse with particle ring and sound on every beat. */
	PULSE,
	/** Rises into the air with accelerating spin and a particle trail. */
	ASCEND,
	/** Charges up and shatters into flying fragments. */
	EXPLODE,
	/** Full trailer sequence: reveal → orbiting satellites, background, camera orbit, name card. */
	SHOWCASE;

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	public static ShowcasePreset byId(String id) {
		return valueOf(id.toUpperCase(Locale.ROOT));
	}
}
