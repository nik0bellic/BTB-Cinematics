package de.behindtheblocks.cinematic.client.mixin;

import de.behindtheblocks.cinematic.client.compat.SkinStudioCompat;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.decoration.Mannequin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replay puppets / statists (mannequins) get the BTB Skin Studio look of the actor they stand in for.
 * Priority 1500: runs after Skin Studio's own extraction (which clears the data for non-players).
 */
@Mixin(value = AvatarRenderer.class, priority = 1500)
public abstract class MannequinLookMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
	private void btb$skinStudioLook(Avatar entity, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
		if (entity instanceof Mannequin m) {
			SkinStudioCompat.dress(m, state);
		}
	}
}
