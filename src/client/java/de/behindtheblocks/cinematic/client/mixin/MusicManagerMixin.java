package de.behindtheblocks.cinematic.client.mixin;

import de.behindtheblocks.cinematic.client.sound.CinematicSoundPlayer;
import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps vanilla background music from starting while cinematic music plays. Target: {@code MusicManager.tick()}. */
@Mixin(MusicManager.class)
public abstract class MusicManagerMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void btb$suppressVanillaMusic(CallbackInfo ci) {
		if (CinematicSoundPlayer.musicActive()) {
			ci.cancel();
		}
	}
}
