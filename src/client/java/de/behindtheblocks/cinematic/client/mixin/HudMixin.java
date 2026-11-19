package de.behindtheblocks.cinematic.client.mixin;

import de.behindtheblocks.cinematic.client.CinematicOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the cinematic overlay (screen effects + HUD texts) after the vanilla HUD — also when the
 * HUD is hidden with F1 or during camera shots, which is exactly when films are recorded.
 * Target: {@code Hud.extractRenderState(GuiGraphicsExtractor, DeltaTracker)} (26.3).
 */
@Mixin(Hud.class)
public abstract class HudMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void btb$drawCinematicOverlay(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		CinematicOverlay.render(graphics, deltaTracker);
	}
}
