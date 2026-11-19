package de.behindtheblocks.cinematic.sound;

import java.util.Locale;

/**
 * Environmental reverb presets for OpenAL EFX "standard reverb". Values follow the Creative EFX
 * preset table (EFXReverbPresets) where a matching preset exists; NETHER and CATHEDRAL are tuned
 * for this project.
 */
public enum ReverbPreset {
	OFF(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
	ROOM(0.4287f, 1.0f, 0.3162f, 0.5929f, 0.40f, 0.83f, 0.1503f, 0.002f, 1.0629f, 0.003f),
	HALL(1.0f, 1.0f, 0.3162f, 0.5623f, 3.92f, 0.70f, 0.2427f, 0.020f, 0.9977f, 0.029f),
	CAVE(1.0f, 1.0f, 0.3162f, 1.0f, 2.91f, 1.30f, 0.5000f, 0.015f, 0.7063f, 0.022f),
	ARENA(1.0f, 1.0f, 0.3162f, 0.4477f, 7.24f, 0.33f, 0.2612f, 0.020f, 1.0186f, 0.030f),
	CATHEDRAL(1.0f, 1.0f, 0.3162f, 0.30f, 6.50f, 0.60f, 0.2500f, 0.030f, 1.2000f, 0.040f),
	NETHER(1.0f, 0.8f, 0.3162f, 0.40f, 4.50f, 0.50f, 0.3000f, 0.020f, 1.3000f, 0.035f);

	public final float density, diffusion, gain, gainHF, decayTime, decayHFRatio, reflectionsGain, reflectionsDelay,
			lateReverbGain, lateReverbDelay;

	ReverbPreset(float density, float diffusion, float gain, float gainHF, float decayTime, float decayHFRatio,
			float reflectionsGain, float reflectionsDelay, float lateReverbGain, float lateReverbDelay) {
		this.density = density;
		this.diffusion = diffusion;
		this.gain = gain;
		this.gainHF = gainHF;
		this.decayTime = decayTime;
		this.decayHFRatio = decayHFRatio;
		this.reflectionsGain = reflectionsGain;
		this.reflectionsDelay = reflectionsDelay;
		this.lateReverbGain = lateReverbGain;
		this.lateReverbDelay = lateReverbDelay;
	}

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	public static ReverbPreset byId(String id) {
		for (ReverbPreset p : values()) {
			if (p.id().equals(id.toLowerCase(Locale.ROOT))) {
				return p;
			}
		}
		throw new IllegalArgumentException("unknown reverb preset '" + id + "' (off, room, hall, cave, arena, cathedral, nether)");
	}
}
