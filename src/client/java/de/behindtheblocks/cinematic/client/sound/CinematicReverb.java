package de.behindtheblocks.cinematic.client.sound;

import de.behindtheblocks.cinematic.sound.ReverbPreset;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Environmental reverb through OpenAL EFX (supported by the OpenAL Soft build Minecraft ships).
 *
 * <p>The preset is chosen on the client thread; all OpenAL calls happen on the sound thread from
 * {@code ChannelMixin} just before a source starts playing, where Minecraft's AL context is
 * current. Every source that starts while a preset is active is routed to one shared auxiliary
 * effect slot, so the cost is a single reverb unit regardless of how many sounds play.
 */
public final class CinematicReverb {
	private static final Logger LOG = LoggerFactory.getLogger("btb_cinematic/reverb");

	private static volatile ReverbPreset preset = ReverbPreset.OFF;
	private static volatile float mix = 0.6f;
	private static volatile int version;

	// sound-thread state
	private static boolean initTried;
	private static boolean supported;
	private static int slot;
	private static int effect;
	private static int appliedVersion = -1;

	private CinematicReverb() {
	}

	/** Client thread. */
	public static void set(ReverbPreset newPreset, float newMix) {
		preset = newPreset;
		mix = Math.max(0, Math.min(1, newMix));
		version++;
	}

	public static ReverbPreset preset() {
		return preset;
	}

	// ------------------------------------------------------------------ muffled hearing (low-pass on everything)

	/** 1 = clear, towards 0 = deaf/dull (client thread writes, sound thread reads). */
	private static volatile float muffle = 1f;
	private static int lowpass;
	private static boolean lowpassTried;

	public static void setMuffle(float value) {
		muffle = Math.max(0.02f, Math.min(1f, value));
	}

	/** Sound thread: every playing source passes here each tick (volume update); applies or removes the filter. */
	public static void onSourceUpdate(int source) {
		float m = muffle;
		if (m >= 0.999f && !lowpassTried) {
			return;
		}
		if (!lowpassTried) {
			lowpassTried = true;
			if (!init()) {
				return;
			}
			lowpass = EXTEfx.alGenFilters();
			EXTEfx.alFilteri(lowpass, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
		}
		if (lowpass == 0) {
			return;
		}
		if (m >= 0.999f) {
			AL11.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
			return;
		}
		EXTEfx.alFilterf(lowpass, EXTEfx.AL_LOWPASS_GAIN, 0.45f + 0.55f * m);
		EXTEfx.alFilterf(lowpass, EXTEfx.AL_LOWPASS_GAINHF, m * m);
		AL11.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, lowpass);
	}

	/** Sound thread: called for each source right before it starts playing. */
	public static void onSourcePlay(int source) {
		onSourceUpdate(source);
		ReverbPreset p = preset;
		if (p == ReverbPreset.OFF && !initTried) {
			return; // never used: zero overhead
		}
		if (!init()) {
			return;
		}
		if (appliedVersion != version) {
			applyPreset(p);
			appliedVersion = version;
		}
		if (p != ReverbPreset.OFF) {
			AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, slot, 0, EXTEfx.AL_FILTER_NULL);
		} else {
			AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, EXTEfx.AL_EFFECTSLOT_NULL, 0, EXTEfx.AL_FILTER_NULL);
		}
	}

	private static boolean init() {
		if (initTried) {
			return supported;
		}
		initTried = true;
		try {
			long device = ALC10.alcGetContextsDevice(ALC10.alcGetCurrentContext());
			if (device == 0 || !ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX")) {
				LOG.warn("OpenAL EFX not available — reverb disabled");
				return supported = false;
			}
			slot = EXTEfx.alGenAuxiliaryEffectSlots();
			effect = EXTEfx.alGenEffects();
			EXTEfx.alEffecti(effect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_REVERB);
			supported = EXTEfx.alGetEffecti(effect, EXTEfx.AL_EFFECT_TYPE) == EXTEfx.AL_EFFECT_REVERB;
			LOG.info("OpenAL EFX reverb {}", supported ? "ready" : "unsupported");
		} catch (RuntimeException | UnsatisfiedLinkError e) {
			LOG.warn("OpenAL EFX init failed — reverb disabled", e);
			supported = false;
		}
		return supported;
	}

	private static void applyPreset(ReverbPreset p) {
		if (p == ReverbPreset.OFF) {
			EXTEfx.alAuxiliaryEffectSlotf(slot, EXTEfx.AL_EFFECTSLOT_GAIN, 0f);
			return;
		}
		EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DENSITY, p.density);
		EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DIFFUSION, p.diffusion);
		EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_GAIN, p.gain);
		EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_GAINHF, p.gainHF);
		EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DECAY_TIME, p.decayTime);
		EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DECAY_HFRATIO, p.decayHFRatio);
		EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_REFLECTIONS_GAIN, p.reflectionsGain);
		EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_REFLECTIONS_DELAY, p.reflectionsDelay);
		EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_LATE_REVERB_GAIN, p.lateReverbGain);
		EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_LATE_REVERB_DELAY, p.lateReverbDelay);
		checkError("effect params");
		EXTEfx.alAuxiliaryEffectSloti(slot, EXTEfx.AL_EFFECTSLOT_EFFECT, effect);
		EXTEfx.alAuxiliaryEffectSlotf(slot, EXTEfx.AL_EFFECTSLOT_GAIN, mix);
		checkError("slot setup");
		LOG.info("Reverb preset {} applied (mix {}), sends per source: {}", p.id(), mix,
				ALC10.alcGetInteger(ALC10.alcGetContextsDevice(ALC10.alcGetCurrentContext()), EXTEfx.ALC_MAX_AUXILIARY_SENDS));
	}

	private static void checkError(String what) {
		int err = org.lwjgl.openal.AL10.alGetError();
		if (err != org.lwjgl.openal.AL10.AL_NO_ERROR) {
			LOG.warn("OpenAL error 0x{} during {}", Integer.toHexString(err), what);
		}
	}
}
