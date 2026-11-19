package de.behindtheblocks.cinematic.camera;

import java.util.Locale;

/** Easing curves mapping 0..1 → 0..1. Names are used in JSON and commands (lowercase). */
public enum Easing {
	LINEAR,
	EASE_IN,
	EASE_OUT,
	EASE_IN_OUT,
	/** Stronger S-curve, good for dramatic reveals. */
	EASE_IN_OUT_CUBIC,
	/** Slight overshoot at the end, for punchy pushes. */
	EASE_OUT_BACK,
	/** Perlin-style smoothstep (C2 continuous): starts/stops perfectly still. */
	SMOOTHER;

	public double apply(double t) {
		t = Math.max(0, Math.min(1, t));
		return switch (this) {
			case LINEAR -> t;
			case EASE_IN -> t * t;
			case EASE_OUT -> 1 - (1 - t) * (1 - t);
			case EASE_IN_OUT -> t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
			case EASE_IN_OUT_CUBIC -> t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
			case EASE_OUT_BACK -> {
				double c1 = 1.70158, c3 = c1 + 1;
				yield 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
			}
			case SMOOTHER -> t * t * t * (t * (t * 6 - 15) + 10);
		};
	}

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	public static Easing byId(String id) {
		for (Easing e : values()) {
			if (e.id().equals(id.toLowerCase(Locale.ROOT))) {
				return e;
			}
		}
		throw new IllegalArgumentException("unknown easing '" + id + "'");
	}
}
