package de.behindtheblocks.cinematic.client.mixin;

import com.mojang.blaze3d.audio.Channel;
import de.behindtheblocks.cinematic.client.sound.CinematicReverb;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes a source to the EFX reverb slot right before it starts playing (sound thread).
 * Target: {@code com.mojang.blaze3d.audio.Channel.play()} (26.3).
 */
@Mixin(Channel.class)
public abstract class ChannelMixin {
	@Shadow
	@Final
	private int source;

	@Inject(method = "updateStream", at = @At("HEAD"))
	private void btb$muffleStream(CallbackInfo ci) {
		CinematicReverb.onSourceUpdate(source); // music and long sounds follow the muffle every tick
	}

	@Inject(method = "setVolume", at = @At("HEAD"))
	private void btb$muffle(float volume, CallbackInfo ci) {
		CinematicReverb.onSourceUpdate(source);
	}

	@Inject(method = "play", at = @At("HEAD"))
	private void btb$attachReverb(CallbackInfo ci) {
		CinematicReverb.onSourcePlay(source);
	}
}
