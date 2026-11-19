package de.behindtheblocks.cinematic.client.sound;

import de.behindtheblocks.cinematic.camera.Easing;
import de.behindtheblocks.cinematic.client.timeline.ClientTimeline;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * A sound with a volume envelope (fade-in, fade-out) and the global cinematic master gain.
 * {@code pos == null} plays it non-positional (same loudness everywhere, like a film score).
 */
public final class CinematicSoundInstance extends AbstractTickableSoundInstance {
	final Identifier soundId;
	final String sceneId;
	private final float baseVolume;
	private final int fadeIn;
	private final ClientTimeline.Stamp start;
	private ClientTimeline.Stamp fadeOutStart;
	private int fadeOutTicks;
	private float fadeOutFrom;

	public CinematicSoundInstance(Identifier soundId, String sceneId, SoundSource source, float volume, float pitch,
			Vec3 pos, boolean looping, int fadeIn) {
		super(SoundEvent.createVariableRangeEvent(soundId), source, RandomSource.create());
		this.soundId = soundId;
		this.sceneId = sceneId;
		this.baseVolume = volume;
		this.fadeIn = fadeIn;
		this.pitch = pitch;
		this.looping = looping;
		this.start = ClientTimeline.Stamp.now(sceneId);
		if (pos == null) {
			this.relative = true;
			this.attenuation = SoundInstance.Attenuation.NONE;
			this.x = this.y = this.z = 0;
		} else {
			this.relative = false;
			this.attenuation = SoundInstance.Attenuation.LINEAR;
			this.x = pos.x;
			this.y = pos.y;
			this.z = pos.z;
		}
		this.volume = computeVolume();
	}

	private float computeVolume() {
		float env = 1f;
		if (fadeIn > 0) {
			env = (float) Easing.EASE_IN_OUT.apply(start.elapsed(0f) / fadeIn);
		}
		if (fadeOutStart != null) {
			float p = fadeOutTicks <= 0 ? 1 : (float) Math.min(1, fadeOutStart.elapsed(0f) / fadeOutTicks);
			env = fadeOutFrom * (1 - (float) Easing.EASE_IN_OUT.apply(p));
		}
		return Math.max(0f, baseVolume * env * CinematicSoundPlayer.masterGain());
	}

	/** Fades out from the current level and then stops. */
	public void fadeOut(int ticks) {
		if (fadeOutStart != null) {
			return;
		}
		float current = fadeIn > 0 ? (float) Easing.EASE_IN_OUT.apply(start.elapsed(0f) / fadeIn) : 1f;
		fadeOutFrom = current;
		fadeOutTicks = ticks;
		fadeOutStart = ClientTimeline.Stamp.now(sceneId);
		if (ticks <= 0) {
			stop();
		}
	}

	@Override
	public void tick() {
		volume = computeVolume();
		if (fadeOutStart != null && fadeOutStart.elapsed(0f) >= fadeOutTicks) {
			stop();
		}
	}

	@Override
	public boolean canStartSilent() {
		return true; // needed for fade-ins that start at volume 0
	}

	/** Ticks since creation on the scene clock. */
	public double age() {
		return start.elapsed(0f);
	}

	public void stopNow() {
		stop();
	}
}
