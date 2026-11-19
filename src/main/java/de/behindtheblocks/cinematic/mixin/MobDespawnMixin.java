package de.behindtheblocks.cinematic.mixin;

import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Creatures the film spawns (scenes, waves, the guardian, puppets, extras) stay in the world even on
 * Peaceful – film crews usually shoot on Peaceful, and vanilla would delete every monster at once.
 */
@Mixin(Mob.class)
public abstract class MobDespawnMixin {
	@Inject(method = "checkDespawn", at = @At("HEAD"), cancellable = true)
	private void btb$keepFilmCreatures(CallbackInfo ci) {
		var tags = ((Mob) (Object) this).entityTags();
		if (tags.contains("btb_spawned") || tags.contains("btb_puppet") || tags.contains("btb_invulnerable") || tags.contains("btb_extra")) {
			ci.cancel();
		}
	}
}
