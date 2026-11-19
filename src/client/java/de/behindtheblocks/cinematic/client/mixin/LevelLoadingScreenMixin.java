package de.behindtheblocks.cinematic.client.mixin;

import de.behindtheblocks.cinematic.client.effects.ScreenEffectRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps an active fade/transition on screen while Minecraft shows its level loading screen
 * (dimension change), so a portal transfer across dimensions stays invisible.
 * Target: {@code LevelLoadingScreen.extractRenderState(GuiGraphicsExtractor, int, int, float)} (26.3).
 */
@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingScreenMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void btb$keepFade(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
		ScreenEffectRenderer.renderCover(graphics, 0f);
	}
}
