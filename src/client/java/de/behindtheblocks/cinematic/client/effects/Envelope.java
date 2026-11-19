package de.behindtheblocks.cinematic.client.effects;

import de.behindtheblocks.cinematic.camera.Easing;
import de.behindtheblocks.cinematic.client.timeline.ClientTimeline;

/**
 * Attack/hold/release curve on a client timeline clock. Value 0..1.
 * A new envelope can start from the value of the one it replaces ({@code from}), which makes
 * consecutive fades blend seamlessly (e.g. white → black without popping).
 */
final class Envelope {
	private ClientTimeline.Stamp start;
	private final float from;
	private final int in;
	private final int hold;
	private final int out;
	private boolean released;
	private float releaseFrom;
	private int releaseTicks;

	Envelope(String sceneId, float from, int in, int hold, int out) {
		this.start = ClientTimeline.Stamp.now(sceneId);
		this.from = from;
		this.in = in;
		this.hold = hold;
		this.out = out;
	}

	float value(float partial) {
		double t = start.elapsed(partial);
		if (released) {
			return releaseTicks <= 0 ? 0 : (float) (releaseFrom * (1 - Easing.EASE_IN_OUT.apply(t / releaseTicks)));
		}
		if (t < in) {
			return (float) (from + (1 - from) * Easing.EASE_IN_OUT.apply(t / in));
		}
		if (hold < 0 || t < in + hold) {
			return 1;
		}
		if (t < in + hold + out) {
			return (float) (1 - Easing.EASE_IN_OUT.apply((t - in - hold) / out));
		}
		return 0;
	}

	/** Linear progress through the attack phase (0..1); used for color cross-fades. */
	float attackProgress(float partial) {
		return in <= 0 ? 1 : (float) Math.min(1, start.elapsed(partial) / in);
	}

	boolean done(float partial) {
		double t = start.elapsed(partial);
		if (released) {
			return t >= releaseTicks;
		}
		return hold >= 0 && t >= in + hold + out;
	}

	/** Fades out from the current value over {@code ticks}. */
	void release(int ticks, float partial) {
		float current = value(partial);
		String scene = start.sceneId();
		start = ClientTimeline.Stamp.now(scene);
		released = true;
		releaseFrom = current;
		releaseTicks = ticks;
	}
}
